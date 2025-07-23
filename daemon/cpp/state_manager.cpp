// daemon/cpp/state_manager.cpp
#include "state_manager.h"
#include "main.h" // 确保包含 main.h
#include <android/log.h>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <unistd.h>
#include <algorithm>
#include <sys/stat.h>
#include <unordered_map>
#include <ctime>

#define LOG_TAG "cerberusd_state_v13_persist_fix"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;
using json = nlohmann::json;

static std::string status_to_string(const AppRuntimeState& app, const MasterConfig& master_config) {
    if (app.current_status == AppRuntimeState::Status::STOPPED) return "STOPPED";
    if (app.current_status == AppRuntimeState::Status::FROZEN) return "FROZEN";
    if (app.is_foreground) return "FOREGROUND";
    if (app.config.policy == AppPolicy::EXEMPTED || app.config.policy == AppPolicy::IMPORTANT) return "EXEMPTED_BACKGROUND";
    if (app.background_since > 0) {
        time_t now = time(nullptr);
        int timeout_sec = 0;
        if (app.config.policy == AppPolicy::STRICT) {
            timeout_sec = 15;
        } else if (app.config.policy == AppPolicy::STANDARD) {
            timeout_sec = master_config.standard_timeout_sec;
        }
        int remaining = timeout_sec - (now - app.background_since);
        if (remaining < 0) remaining = 0;
        return "PENDING_FREEZE (" + std::to_string(remaining) + "s)";
    }
    if (app.observation_since > 0) {
        time_t now = time(nullptr);
        int remaining = 10 - (now - app.observation_since);
        if (remaining < 0) remaining = 0;
        return "OBSERVING (" + std::to_string(remaining) + "s)";
    }
    return "BACKGROUND";
}

StateManager::StateManager(std::shared_ptr<DatabaseManager> db, std::shared_ptr<SystemMonitor> sys, std::shared_ptr<ActionExecutor> act)
    : db_manager_(db), sys_monitor_(sys), action_executor_(act), unfrozen_timeline_(4096, 0) {
    LOGI("StateManager Initializing...");
    
    master_config_ = db_manager_->get_master_config().value_or(MasterConfig{});
    LOGI("Loaded master config: standard_timeout=%ds", master_config_.standard_timeout_sec);
    
    if (fs::exists("/sys/fs/cgroup/cgroup.controllers")) {
        default_freeze_method_ = FreezeMethod::CGROUP_V2;
    } else {
        default_freeze_method_ = FreezeMethod::METHOD_SIGSTOP;
    }
    LOGI("Default freeze method set to %s.", default_freeze_method_ == FreezeMethod::CGROUP_V2 ? "CGROUP_V2" : "SIGSTOP");
   
    critical_system_apps_ = {
        "com.google.android.inputmethod.latin", // Gboard
        "com.baidu.input",
        "com.sohu.inputmethod.sogou",
        "com.iflytek.inputmethod",
        "com.tencent.qqpinyin",
        "com.xiaomi.mibrain.speech",
        "com.xiaomi.scanner",
        "zygote",
        "zygote64",
        "com.xiaomi.xmsf",
        "com.xiaomi.xmsfkeeper",
        "com.xiaomi.misettings",
        "com.xiaomi.barrage",
        "com xiaomi.aireco",
        "com.xiaomi.account",
        "com.miui.notes",
        "com.miui.calculator",
        "com.miui.compass",
        "com.miui.mediaeditor",
        "com.miui.personalassistant",
        "com.miui.vipservice",
        "com.miui.systemAdSolution",
        "com.miui.home",
        "com.miui.carlink",
        "com.miui.packageinstaller",
        "com.miui.accessibility",
        "com.miui.core",
        "com.miui.privacycomputing",
        "com.miui.securityadd",
        "com.miui.securityinputmethod",
        "com.miui.system",
        "com.miui.vpnsdkmanager",
        "com.mfashiongallery.emag",
        "com.huawei.hwid",
        "cn.litiaotiao.app",
        "com.litiaotiao.app",
        "hello.litiaotiao.app",
        "com.zfdang.touchhelper",
        "com.giftedcat.adskiphelper",
        "com.merxury.blocker",
        "com.wpengapp.lightstart",
        "li.songe.gkd",
        "com.sevtinge.hyperceiler",
        "com.topjohnwu.magisk",
        "org.lsposed.manager",
        "name.monwf.customiuizer",
        "name.mikanoshi.customiuizer",
        "com.android.vending",
        "org.meowcat.xposed.mipush",
        "top.trumeet.mipush",
        "one.yufz.hmspush",
        "app.lawnchair",
        "com.microsoft.launcher",
        "com.teslacoilsw.launcher",
        "com.hola.launcher",
        "com.transsion.XOSLauncher",
        "com.mi.android.globallauncher",
        "com.gau.go.launcherex",
        "bitpit.launcher",
        "com.oppo.launcher",
        "me.weishu.kernelsu",
        "top.canyie.dreamland.manager",
        "com.coloros.packageinstaller",
        "com.oplus.packageinstaller",
        "com.iqoo.packageinstaller",
        "com.vivo.packageinstaller",
        "com.google.android.packageinstaller",
        "com.baidu.input_huawei",
        "com.baidu.input_oppo",
        "com.baidu.input_vivo",
        "com.baidu.input_yijia",
        "com.sohu.inputmethod.sogou.xiaomi",
        "com.sohu.inputmethod.sogou.meizu",
        "com.sohu.inputmethod.sogou.nubia",
        "com.sohu.inputmethod.sogou.chuizi",
        "com.sohu.inputmethod.sogou.moto",
        "com.sohu.inputmethod.sogou.zte",
        "com.sohu.inputmethod.sogou.samsung",
        "com.sohu.input_yijia",
        "com.iflytek.inputmethod.miui",
        "com.iflytek.inputmethod.googleplay",
        "com.iflytek.inputmethod.smartisan",
        "com.iflytek.inputmethod.oppo",
        "com.iflytek.inputmethod.oem",
        "com.iflytek.inputmethod.custom",
        "com.iflytek.inputmethod.blackshark",
        "com.iflytek.inputmethod.zte",
        "com.touchtype.swiftkey",
        "com.touchtype.swiftkey.beta",
        "im.weshine.keyboard",
        "com.komoxo.octopusime",
        "com.qujianpan.duoduo",
        "com.lxlm.lhl.softkeyboard",
        "com.jinkey.unfoldedime",
        "com.iflytek.inputmethods.DungkarIME",
        "com.oyun.qingcheng",
        "com.ziipin.softkeyboard",
        "com.kongzue.secretinput",
        "com.google.android.ext.services",
        "com.google.android.ext.shared",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.systemui.gxoverlay",
        "com.google.android.tag",
        "com.google.android.documentsui",
        "com.google.android.captiveportallogin",
        "com.google.android.printservice.recommendation",
        "com.google.android.gms.supervision",
        "com.google.android.as.oss",
        "com.google.android.configupdater",
        "com.google.android.apps.restore",
        "com.google.android.onetimeinitializer",
        "com.google.android.odad",
        "com.google.android.settings.intelligence",
        "com.google.android.partnersetup",
        "com.google.android.projection.gearhead",
        "com.google.android.apps.wellbeing",
        "com.google.android.as",
        "com.google.android.apps.messaging",
        "com.google.android.googlequicksearchbox",
        "com.google.android.webview",
        "com.google.android.tts",
        "com.google.android.deskclock",
        "com.google.android.markup",
        "com.google.android.calendar",
        "com.google.android.soundpicker",
        "com.google.android.apps.wallpaper.nexus",
        "com.google.android.modulemetadata",
        "com.google.android.contacts",
        "com.google.android.apps.customization.pixel",
        "com.google.android.apps.photos",
        "com.google.android.feedback",
        "com.google.android.apps.wallpaper",
        "com.google.android.providers.media.module",
        "com.google.android.wifi.resources",
        "com.google.android.hotspot2.osulogin",
        "com.google.android.safetycenter.resources",
        "com.google.android.permissioncontroller",
        "com.google.android.ondevicepersonalization.services",
        "com.google.android.adservices.api",
        "com.google.android.devicelockcontroller",
        "com.google.android.connectivity.resources",
        "com.google.android.healthconnect.controller",
        "com.google.android.cellbroadcastreceiver",
        "com.google.android.uwb.resources",
        "com.google.android.rkpdapp",
        "com.android.launcher",
        "com.android.launcher2",
        "com.android.launcher4",
        "com.android.camera",
        "com.android.camera2",
        "com.android.apps.tag",
        "com.android.bips",
        "com.android.bluetoothmidiservice",
        "com.android.cameraextensions",
        "com.android.carrierdefaultapp",
        "com.android.certinstaller",
        "com.android.companiondevicemanager",
        "com.android.dreams.basic",
        "com.android.egg",
        "com.android.emergency",
        "com.android.externalstorage",
        "com.android.htmlviewer",
        "com.android.internal.display.cutout.emulation.corner",
        "com.android.internal.display.cutout.emulation.double",
        "com.android.internal.display.cutout.emulation.hole",
        "com.android.internal.display.cutout.emulation.tall",
        "com.android.internal.display.cutout.emulation.waterfall",
        "com.android.internal.systemui.navbar.gestural",
        "com.android.internal.systemui.navbar.gestural_extra_wide_back",
        "com.android.internal.systemui.navbar.gestural_narrow_back",
        "com.android.internal.systemui.navbar.gestural_wide_back",
        "com.android.internal.systemui.navbar.threebutton",
        "com.android.managedprovisioning",
        "com.android.mms",
        "com.android.mtp",
        "com.android.musicfx",
        "com.android.networkstack.inprocess.overlay",
        "com.android.networkstack.overlay",
        "com.android.networkstack.tethering.inprocess.overlay",
        "com.android.networkstack.tethering.overlay",
        "com.android.packageinstaller",
        "com.android.pacprocessor",
        "com.android.printspooler",
        "com.android.providers.calendar",
        "com.android.providers.contacts",
        "com.android.providers.downloads.ui",
        "com.android.proxyhandler",
        "com.android.server.telecom.overlay.miui",
        "com.android.simappdialog",
        "com.android.soundrecorder",
        "com.android.statementservice",
        "com.android.storagemanager",
        "com.android.theme.font.notoserifsource",
        "com.android.traceur",
        "com.android.vpndialogs",
        "com.android.wallpaper.livepicker",
        "com.debug.loggerui",
        "com.fingerprints.sensortesttool",
        "com.lbe.security.miui",
        "com.mediatek.callrecorder",
        "com.mediatek.duraspeed",
        "com.mediatek.engineermode",
        "com.mediatek.lbs.em2.ui",
        "com.mediatek.location.mtkgeofence",
        "com.mediatek.mdmconfig",
        "com.mediatek.mdmlsample",
        "com.mediatek.miravision.ui",
        "com.mediatek.op01.telecom",
        "com.mediatek.op09clib.phone.plugin",
        "com.mediatek.op09clib.telecom",
        "com.mediatek.ygps",
        "com.tencent.soter.soterserver",
        "com.unionpay.tsmservice.mi",
        "android.ext.services",
        "android.ext.shared",
        "com.android.bookmarkprovider",
        "com.android.cellbroadcastreceiver.module",
        "com.android.dreams.phototable",
        "com.android.intentresolver",
        "com.android.internal.display.cutout.emulation.noCutout",
        "com.android.internal.systemui.navbar.twobutton",
        "com.android.messaging",
        "com.android.wallpaper",
        "com.qualcomm.qti.cne",
        "com.qualcomm.qti.poweroffalarm",
        "com.qualcomm.wfd.service",
        "org.lineageos.aperture",
        "org.lineageos.audiofx",
        "org.lineageos.backgrounds",
        "org.lineageos.customization",
        "org.lineageos.eleven",
        "org.lineageos.etar",
        "org.lineageos.jelly",
        "org.lineageos.overlay.customization.blacktheme",
        "org.lineageos.overlay.font.lato",
        "org.lineageos.overlay.font.rubik",
        "org.lineageos.profiles",
        "org.lineageos.recorder",
        "org.lineageos.updater",
        "org.protonaosp.deviceconfig",
        "android.aosp.overlay",
        "android.miui.home.launcher.res",
        "android.miui.overlay",
        "com.android.carrierconfig",
        "com.android.carrierconfig.overlay.miui",
        "com.android.incallui.overlay",
        "com.android.managedprovisioning.overlay",
        "com.android.overlay.cngmstelecomm",
        "com.android.overlay.gmscontactprovider",
        "com.android.overlay.gmssettingprovider",
        "com.android.overlay.gmssettings",
        "com.android.overlay.gmstelecomm",
        "com.android.overlay.gmstelephony",
        "com.android.overlay.systemui",
        "com.android.phone.overlay.miui",
        "com.android.providers.settings.overlay",
        "com.android.sdksandbox",
        "com.android.settings.overlay.miui",
        "com.android.stk.overlay.miui",
        "com.android.systemui.gesture.line.overlay",
        "com.android.systemui.navigation.bar.overlay",
        "com.android.systemui.overlay.miui",
        "com.android.wallpapercropper",
        "com.android.wallpaperpicker",
        "com.android.wifi.dialog",
        "com.android.wifi.resources.overlay",
        "com.android.wifi.resources.xiaomi",
        "com.android.wifi.system.mainline.resources.overlay",
        "com.android.wifi.system.resources.overlay",
        "com.google.android.cellbroadcastreceiver.overlay.miui",
        "com.google.android.cellbroadcastservice.overlay.miui",
        "com.google.android.overlay.gmsconfig",
        "com.google.android.overlay.modules.ext.services",
        "com.google.android.trichromelibrary_511209734",
        "com.google.android.trichromelibrary_541411734",
        "com.mediatek.FrameworkResOverlayExt",
        "com.mediatek.SettingsProviderResOverlay",
        "com.mediatek.batterywarning",
        "com.mediatek.cellbroadcastuiresoverlay",
        "com.mediatek.frameworkresoverlay",
        "com.mediatek.gbaservice",
        "com.mediatek.voiceunlock",
        "com.miui.core.internal.services",
        "com.miui.face.overlay.miui",
        "com.miui.miwallpaper.overlay.customize",
        "com.miui.miwallpaper.wallpaperoverlay.config.overlay",
        "com.miui.rom",
        "com.miui.settings.rro.device.config.overlay",
        "com.miui.settings.rro.device.hide.statusbar.overlay",
        "com.miui.settings.rro.device.type.overlay",
        "com.miui.system.overlay",
        "com.miui.systemui.carriers.overlay",
        "com.miui.systemui.devices.overlay",
        "com.miui.systemui.overlay.devices.android",
        "com.miui.translation.kingsoft",
        "com.miui.translation.xmcloud",
        "com.miui.translationservice",
        "com.miui.voiceassistoverlay",
        "com.xiaomi.bluetooth.rro.device.config.overlay",
        "android.auto_generated_rro_product__",
        "android.auto_generated_rro_vendor__",
        "com.android.backupconfirm",
        "com.android.carrierconfig.auto_generated_rro_vendor__",
        "com.android.cts.ctsshim",
        "com.android.cts.priv.ctsshim",
        "com.android.documentsui.auto_generated_rro_product__",
        "com.android.emergency.auto_generated_rro_product__",
        "com.android.imsserviceentitlement",
        "com.android.imsserviceentitlement.auto_generated_rro_product__",
        "com.android.inputmethod.latin.auto_generated_rro_product__",
        "com.android.launcher3.overlay",
        "com.android.managedprovisioning.auto_generated_rro_product__",
        "com.android.nearby.halfsheet",
        "com.android.phone.auto_generated_rro_vendor__",
        "com.android.providers.settings.auto_generated_rro_product__",
        "com.android.providers.settings.auto_generated_rro_vendor__",
        "com.android.settings.auto_generated_rro_product__",
        "com.android.sharedstoragebackup",
        "com.android.smspush",
        "com.android.storagemanager.auto_generated_rro_product__",
        "com.android.systemui.auto_generated_rro_product__",
        "com.android.systemui.auto_generated_rro_vendor__",
        "com.android.systemui.plugin.globalactions.wallet",
        "com.android.wallpaper.auto_generated_rro_product__",
        "com.android.wifi.resources.oneplus_sdm845",
        "com.qualcomm.timeservice",
        "lineageos.platform.auto_generated_rro_product__",
        "lineageos.platform.auto_generated_rro_vendor__",
        "org.codeaurora.ims",
        "org.lineageos.aperture.auto_generated_rro_vendor__",
        "org.lineageos.lineageparts.auto_generated_rro_product__",
        "org.lineageos.lineagesettings.auto_generated_rro_product__",
        "org.lineageos.lineagesettings.auto_generated_rro_vendor__",
        "org.lineageos.overlay.customization.navbar.nohint",
        "org.lineageos.settings.device.auto_generated_rro_product__",
        "org.lineageos.settings.doze.auto_generated_rro_product__",
        "org.lineageos.settings.doze.auto_generated_rro_vendor__",
        "org.lineageos.setupwizard.auto_generated_rro_product__",
        "org.lineageos.updater.auto_generated_rro_product__",
        "org.protonaosp.deviceconfig.auto_generated_rro_product__"
    };
   
   
    
    load_all_configs();
    reconcile_process_state_full();
    LOGI("StateManager Initialized.");
}

void StateManager::on_wakeup_request(const json& payload) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    try {
        std::string package_name = payload.value("package_name", "");
        int user_id = payload.value("user_id", 0);

        if (package_name.empty()) return;

        AppInstanceKey key = {package_name, user_id};
        auto it = managed_apps_.find(key);
        if (it != managed_apps_.end()) {
            unfreeze_and_observe(it->second, "WAKEUP_REQUEST");
        }
    } catch (const json::exception& e) {
        LOGE("Error processing wakeup request: %s", e.what());
    }
}

// --- [新增] 新的IPC接口实现 ---

void StateManager::on_temp_unfreeze_request_by_pkg(const json& payload) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    try {
        std::string package_name = payload.value("package_name", "");
        if (package_name.empty()) return;

        for (auto& [key, app] : managed_apps_) {
            if (key.first == package_name) {
                unfreeze_and_observe(app, "FCM");
            }
        }
    } catch (const json::exception& e) {
        LOGE("Error processing temp unfreeze by pkg: %s", e.what());
    }
}

void StateManager::on_temp_unfreeze_request_by_uid(const json& payload) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    try {
        int uid = payload.value("uid", -1);
        if (uid < 0) return;

        for (auto& [key, app] : managed_apps_) {
            if (app.uid == uid) {
                unfreeze_and_observe(app, "AUDIO_FOCUS");
                // 通常一个UID只对应一个实例，找到后即可退出
                break; 
            }
        }
    } catch (const json::exception& e) {
        LOGE("Error processing temp unfreeze by uid: %s", e.what());
    }
}

void StateManager::on_temp_unfreeze_request_by_pid(const json& payload) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    try {
        int pid = payload.value("pid", -1);
        if (pid < 0) return;
        
        auto it = pid_to_app_map_.find(pid);
        if (it != pid_to_app_map_.end()) {
            unfreeze_and_observe(*(it->second), "SIGKILL_PROTECT");
        }
    } catch (const json::exception& e) {
        LOGE("Error processing temp unfreeze by pid: %s", e.what());
    }
}

// --- [新增] 内部通用解冻逻辑 ---
void StateManager::unfreeze_and_observe(AppRuntimeState& app, const std::string& reason) {
    if (app.current_status == AppRuntimeState::Status::FROZEN) {
        LOGI("UNFREEZE [%s]: Unfreezing %s (user %d).", 
             reason.c_str(), app.package_name.c_str(), app.user_id);
        
        action_executor_->unfreeze({app.package_name, app.user_id}, app.pids);
        app.current_status = AppRuntimeState::Status::RUNNING;
        
        // 关键：利用现有的观察期机制实现“自动重冻结”
        app.observation_since = time(nullptr);
        app.background_since = 0; // 确保清除旧的冻结计时器
        
        broadcast_dashboard_update();
        notify_probe_of_config_change(); // 解冻后状态变化，通知Probe
    }
}


// --- [修改] get_probe_config_payload ---
json StateManager::get_probe_config_payload() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    json payload = get_full_config_for_ui();
    json frozen_uids = json::array();
    json frozen_pids = json::array(); // 新增

    for (const auto& [key, app] : managed_apps_) {
        if (app.current_status == AppRuntimeState::Status::FROZEN) {
            frozen_uids.push_back(app.uid);
            // 将该应用的所有PID添加到列表中
            for (int pid : app.pids) {
                frozen_pids.push_back(pid);
            }
        }
    }
    payload["frozen_uids"] = frozen_uids;
    payload["frozen_pids"] = frozen_pids; // 新增
    return payload;
}


void StateManager::update_master_config(const MasterConfig& config) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    master_config_ = config;
    db_manager_->set_master_config(config);
    LOGI("Master config updated: standard_timeout=%ds", master_config_.standard_timeout_sec);
}

void StateManager::on_temp_unfreeze_request(const json& payload) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    try {
        std::string package_name = payload.value("package_name", "");
        if (package_name.empty()) {
            return;
        }

        for (auto& [key, app] : managed_apps_) {
            if (key.first == package_name) {
                if (app.current_status == AppRuntimeState::Status::FROZEN) {
                    LOGI("FCM: Temp unfreezing %s (user %d) for push message.",
                         package_name.c_str(), app.user_id);
                    
                    action_executor_->unfreeze(key, app.pids);
                    app.current_status = AppRuntimeState::Status::RUNNING;
                    
                    app.observation_since = time(nullptr);
                    app.background_since = 0;
                    
                    broadcast_dashboard_update();
                }
            }
        }
    } catch (const json::exception& e) {
        LOGE("Error processing temp unfreeze request: %s", e.what());
    }
}

bool StateManager::update_foreground_state(const std::set<int>& top_pids) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    if (top_pids == last_known_top_pids_) {
        return false;
    }
    
    LOGD("Foreground PIDs changed. Raw count: %zu", top_pids.size());
    last_known_top_pids_ = top_pids;
    
    std::set<AppInstanceKey> top_apps;
    for (int pid : top_pids) {
        auto it = pid_to_app_map_.find(pid);
        if (it != pid_to_app_map_.end()) {
            top_apps.insert({it->second->package_name, it->second->user_id});
        }
    }

    std::set<AppInstanceKey> final_foreground_keys;

    if (top_apps.size() <= 1) {
        final_foreground_keys = top_apps;
    } else {
        std::string current_ime_pkg = sys_monitor_->get_current_ime_package();
        
        std::set<AppInstanceKey> non_ime_top_apps;
        for (const auto& key : top_apps) {
            if (key.first != current_ime_pkg) {
                non_ime_top_apps.insert(key);
            }
        }

        if (!non_ime_top_apps.empty()) {
            final_foreground_keys = non_ime_top_apps;
            LOGD("IME filtered. Final foreground count: %zu", final_foreground_keys.size());
        } else {
            final_foreground_keys = top_apps;
            LOGD("Only IME is in foreground. Final foreground count: %zu", final_foreground_keys.size());
        }
    }

    bool state_has_changed = false;
    time_t now = time(nullptr);
    for (auto& [key, app] : managed_apps_) {
        bool is_now_foreground = final_foreground_keys.count(key);
        if (app.is_foreground != is_now_foreground) {
            state_has_changed = true;
            app.is_foreground = is_now_foreground;
            if (is_now_foreground) {
                if (app.observation_since > 0 || app.background_since > 0) {
                    LOGI("State: Timers cancelled for %s (became foreground).", key.first.c_str());
                }
                app.observation_since = 0;
                app.background_since = 0;

                if (app.current_status == AppRuntimeState::Status::FROZEN) {
                    LOGI("State: Unfreezing %s (became foreground).", key.first.c_str());
                    action_executor_->unfreeze(key, app.pids);
                    app.current_status = AppRuntimeState::Status::RUNNING;
                }
            } else {
                app.background_since = 0;
                if (app.current_status == AppRuntimeState::Status::RUNNING && (app.config.policy == AppPolicy::STANDARD || app.config.policy == AppPolicy::STRICT) && !app.pids.empty()) {
                    LOGI("State: Observation period started for %s (became background).", key.first.c_str());
                    app.observation_since = now;
                } else {
                    app.observation_since = 0;
                }
            }
        }
    }
    return state_has_changed;
}

bool StateManager::tick_state_machine() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    bool changed1 = check_timers();
    bool changed2 = check_timed_unfreeze();
    return changed1 || changed2;
}

bool StateManager::is_app_playing_audio(const AppRuntimeState& app) {
    return sys_monitor_->is_uid_playing_audio(app.uid);
}

bool StateManager::check_timers() {
    bool changed = false;
    time_t now = time(nullptr);
    const double NETWORK_THRESHOLD_KBPS = 100.0;

    for (auto& [key, app] : managed_apps_) {
        if (app.is_foreground || app.config.policy == AppPolicy::EXEMPTED || app.config.policy == AppPolicy::IMPORTANT) {
            if (app.observation_since > 0 || app.background_since > 0) {
                app.observation_since = 0;
                app.background_since = 0;
                changed = true;
            }
            continue;
        }

        if (app.observation_since > 0 && now - app.observation_since >= 10) {
            LOGD("TICK: Observation for %s ended. Checking all exemption conditions...", app.package_name.c_str());
            app.observation_since = 0;

            sys_monitor_->update_audio_state();
            if (is_app_playing_audio(app)) {
                LOGI("TICK: %s is playing audio, deferring freeze.", app.package_name.c_str());
                app.observation_since = now;
                changed = true;
                continue;
            }

            if (sys_monitor_->is_uid_using_location(app.uid)) {
                LOGI("TICK: %s is using location, deferring freeze.", app.package_name.c_str());
                app.observation_since = now;
                changed = true;
                continue;
            }

            NetworkSpeed speed = sys_monitor_->get_cached_network_speed(app.uid);
            if (speed.download_kbps > NETWORK_THRESHOLD_KBPS || speed.upload_kbps > NETWORK_THRESHOLD_KBPS) {
                LOGI("TICK: %s has high network activity (DL: %.1f, UL: %.1f KB/s), deferring freeze.",
                     app.package_name.c_str(), speed.download_kbps, speed.upload_kbps);
                app.observation_since = now;
                changed = true;
                continue;
            }

            LOGI("TICK: %s is silent and inactive, starting freeze timer.", app.package_name.c_str());
            app.background_since = now;
            changed = true;
        }
        
        if (app.background_since > 0) {
            int timeout_sec = 0;
            if(app.config.policy == AppPolicy::STRICT) timeout_sec = 15;
            else if(app.config.policy == AppPolicy::STANDARD) timeout_sec = master_config_.standard_timeout_sec;

            if (timeout_sec > 0 && (now - app.background_since >= timeout_sec)) {
                LOGI("TICK: Timeout for %s. Freezing.", app.package_name.c_str());
                if (action_executor_->freeze(key, app.pids, default_freeze_method_)) {
                    app.current_status = AppRuntimeState::Status::FROZEN;
                    schedule_timed_unfreeze(app);
                }
                app.background_since = 0;
                changed = true;
            }
        }
    }
    return changed;
}

void StateManager::schedule_timed_unfreeze(AppRuntimeState& app) {
    const int unfreeze_interval_sec = 1800;
    if (unfreeze_interval_sec <= 0) return;
    
    uint32_t next_idx = (timeline_idx_ + unfreeze_interval_sec) % unfrozen_timeline_.size();
    for (size_t i = 0; i < unfrozen_timeline_.size(); ++i) {
        if (unfrozen_timeline_[next_idx] == 0) {
            unfrozen_timeline_[next_idx] = app.uid;
            LOGD("Scheduled timed unfreeze for %s at timeline index %u.", app.package_name.c_str(), next_idx);
            return;
        }
        next_idx = (next_idx + 1) % unfrozen_timeline_.size();
    }
    LOGW("Unfreeze timeline is full! Cannot schedule for %s.", app.package_name.c_str());
}

bool StateManager::check_timed_unfreeze() {
    timeline_idx_ = (timeline_idx_ + 1) % unfrozen_timeline_.size();
    int uid_to_unfreeze = unfrozen_timeline_[timeline_idx_];
    
    if (uid_to_unfreeze == 0) return false;
    
    unfrozen_timeline_[timeline_idx_] = 0;

    for (auto& [key, app] : managed_apps_) {
        if (app.uid == uid_to_unfreeze) {
            if (app.current_status == AppRuntimeState::Status::FROZEN && !app.is_foreground) {
                LOGI("TIMED UNFREEZE: Waking up %s.", app.package_name.c_str());
                action_executor_->unfreeze(key, app.pids);
                app.current_status = AppRuntimeState::Status::RUNNING;
                app.observation_since = time(nullptr);
                return true;
            }
            break;
        }
    }
    return false;
}

bool StateManager::perform_deep_scan() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    
    LOGD("Performing deep scan...");
    bool changed = reconcile_process_state_full();
    
    sys_monitor_->update_global_stats();
    global_stats_ = sys_monitor_->get_global_stats();
    
    time_t now = time(nullptr);
    for (auto& [key, app] : managed_apps_) {
        if (!app.pids.empty()) {
            app.undetected_since = 0;
            sys_monitor_->update_app_stats(app.pids, app.mem_usage_kb, app.swap_usage_kb, app.cpu_usage_percent);
        } else if (app.current_status != AppRuntimeState::Status::STOPPED) {
            if (app.undetected_since == 0) {
                app.undetected_since = now;
            } else if (now - app.undetected_since >= 3) {
                if (app.current_status == AppRuntimeState::Status::FROZEN) {
                     LOGI("Frozen app %s no longer has active PIDs. Marking as STOPPED.", app.package_name.c_str());
                }
                app.current_status = AppRuntimeState::Status::STOPPED;
                app.is_foreground = false;
                app.background_since = 0;
                app.observation_since = 0;
                app.mem_usage_kb = 0;
                app.swap_usage_kb = 0;
                app.cpu_usage_percent = 0.0f;
                app.undetected_since = 0;
                changed = true;
            }
        }
    }
    return changed;
}

bool StateManager::on_config_changed_from_ui(const json& payload) { 
    std::lock_guard<std::mutex> lock(state_mutex_);
    if (!payload.contains("policies")) return false;
    
    LOGI("Applying new configuration from UI...");
    db_manager_->clear_all_policies();
    
    bool state_changed = false;
    for (const auto& policy_item : payload["policies"]) {
        AppConfig new_config;
        new_config.package_name = policy_item.value("package_name", "");
        new_config.user_id = policy_item.value("user_id", 0);
        new_config.policy = static_cast<AppPolicy>(policy_item.value("policy", 0));

        if (new_config.package_name.empty()) continue;
        db_manager_->set_app_config(new_config);
        
        AppRuntimeState* app = get_or_create_app_state(new_config.package_name, new_config.user_id);
        if (app) {
            bool policy_changed = app->config.policy != new_config.policy;
            app->config = new_config;

            if (policy_changed && app->current_status == AppRuntimeState::Status::FROZEN && (new_config.policy == AppPolicy::EXEMPTED || new_config.policy == AppPolicy::IMPORTANT)) {
                 LOGI("Policy for frozen app %s changed to exempt. Unfreezing.", app->package_name.c_str());
                 action_executor_->unfreeze({app->package_name, app->user_id}, app->pids);
                 app->current_status = AppRuntimeState::Status::RUNNING;
                 state_changed = true;
            }
        }
    }
    LOGI("New configuration applied.");
    return state_changed;
}

json StateManager::get_dashboard_payload() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    json payload;
    payload["global_stats"] = {
        {"total_cpu_usage_percent", global_stats_.total_cpu_usage_percent},
        {"total_mem_kb", global_stats_.total_mem_kb},
        {"avail_mem_kb", global_stats_.avail_mem_kb},
        {"swap_total_kb", global_stats_.swap_total_kb},
        {"swap_free_kb", global_stats_.swap_free_kb},
    };
    json apps_state = json::array();
    for (auto& [key, app] : managed_apps_) {
        if (app.pids.empty() && app.config.policy == AppPolicy::EXEMPTED && !app.is_foreground) {
            continue;
        }
        json app_json;
        app_json["package_name"] = app.package_name;
        app_json["app_name"] = app.app_name;
        app_json["user_id"] = app.user_id;
        app_json["display_status"] = status_to_string(app, master_config_);
        app_json["mem_usage_kb"] = app.mem_usage_kb;
        app_json["swap_usage_kb"] = app.swap_usage_kb;
        app_json["cpu_usage_percent"] = app.cpu_usage_percent;
        app_json["is_whitelisted"] = app.config.policy == AppPolicy::EXEMPTED || app.config.policy == AppPolicy::IMPORTANT;
        app_json["is_foreground"] = app.is_foreground;
        if (app.current_status == AppRuntimeState::Status::RUNNING && !app.is_foreground) {
            if (is_app_playing_audio(app)) {
                app_json["exemption_reason"] = "PLAYING_AUDIO";
            }
        }
        apps_state.push_back(app_json);
    }
    payload["apps_runtime_state"] = apps_state;
    return payload;
}

json StateManager::get_full_config_for_ui() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    auto all_db_configs = db_manager_->get_all_app_configs();
    json response;
    response["master_config"] = {{"is_enabled", true}, {"freeze_on_screen_off", true}, {"standard_timeout_sec", master_config_.standard_timeout_sec}};
    response["exempt_config"] = {{"exempt_foreground_services", true}};
    json policies = json::array();
    for (const auto& config : all_db_configs) {
        policies.push_back({
            {"package_name", config.package_name},
            {"user_id", config.user_id},
            {"policy", static_cast<int>(config.policy)}
        });
    }
    response["policies"] = policies;
    return response;
}

json StateManager::get_probe_config_payload() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    json payload = get_full_config_for_ui();
    json frozen_uids = json::array();
    for (const auto& [key, app] : managed_apps_) {
        if (app.current_status == AppRuntimeState::Status::FROZEN) {
            frozen_uids.push_back(app.uid);
        }
    }
    payload["frozen_uids"] = frozen_uids;
    return payload;
}

bool StateManager::reconcile_process_state_full() {
    bool changed = false;
    std::unordered_map<int, std::tuple<std::string, int, int>> current_pids;
    for (const auto& entry : fs::directory_iterator("/proc")) {
        if (!entry.is_directory()) continue;
        try {
            int pid = std::stoi(entry.path().filename().string());
            int uid = -1, user_id = -1;
            std::string pkg_name = get_package_name_from_pid(pid, uid, user_id);
            if (!pkg_name.empty()) {
                current_pids[pid] = {pkg_name, user_id, uid};
            }
        } catch (...) { continue; }
    }

    std::vector<int> dead_pids;
    for(const auto& [pid, app_ptr] : pid_to_app_map_) {
        if (current_pids.find(pid) == current_pids.end()) {
            dead_pids.push_back(pid);
        }
    }
    if (!dead_pids.empty()) {
        changed = true;
        for (int pid : dead_pids) remove_pid_from_app(pid);
    }
    
    for(const auto& [pid, info_tuple] : current_pids) {
        if (pid_to_app_map_.find(pid) == pid_to_app_map_.end()) {
            changed = true;
            const auto& [pkg_name, user_id, uid] = info_tuple;
            add_pid_to_app(pid, pkg_name, user_id, uid);
        }
    }
    return changed;
}

void StateManager::load_all_configs() {
    auto configs = db_manager_->get_all_app_configs();
    for (const auto& db_config : configs) {
        get_or_create_app_state(db_config.package_name, db_config.user_id);
    }
}

std::string StateManager::get_package_name_from_pid(int pid, int& uid, int& user_id) {
    constexpr int PER_USER_RANGE = 100000;
    uid = -1; user_id = -1;

    char path_buffer[64];
    snprintf(path_buffer, sizeof(path_buffer), "/proc/%d", pid);

    struct stat st;
    if (stat(path_buffer, &st) != 0) return "";
    uid = st.st_uid;

    if (uid < 10000) return "";
    user_id = uid / PER_USER_RANGE;
    
    snprintf(path_buffer, sizeof(path_buffer), "/proc/%d/cmdline", pid);
    std::ifstream cmdline_file(path_buffer);
    if (!cmdline_file.is_open()) return "";
    
    std::string cmdline;
    std::getline(cmdline_file, cmdline, '\0');

    if (cmdline.empty() || cmdline.find('.') == std::string::npos) {
        return "";
    }

    size_t colon_pos = cmdline.find(':');
    if (colon_pos != std::string::npos) {
        return cmdline.substr(0, colon_pos);
    }
    
    return cmdline;
}

AppRuntimeState* StateManager::get_or_create_app_state(const std::string& package_name, int user_id) {
    if (package_name.empty()) return nullptr;
    AppInstanceKey key = {package_name, user_id};
    auto it = managed_apps_.find(key);
    if (it != managed_apps_.end()) return &it->second;

    // --- 核心修复点 ---
    // 如果应用状态在内存中不存在，意味着它可能是一个新发现的应用
    AppRuntimeState new_state;
    new_state.package_name = package_name;
    new_state.user_id = user_id;
    new_state.app_name = package_name; // 临时名称，后续会被更新

    // 尝试从数据库加载配置
    auto config_opt = db_manager_->get_app_config(package_name, user_id);
    if (config_opt) {
        // 如果数据库中有配置，直接使用
        new_state.config = *config_opt;
    } else {
        // 如果数据库中没有，创建一个默认配置，并【立即写回数据库】
        LOGI("New app instance discovered: %s (user %d). Creating default DB entry.", package_name.c_str(), user_id);
        if (is_critical_system_app(package_name)) {
            new_state.config = AppConfig{package_name, user_id, AppPolicy::EXEMPTED};
        } else {
            // 默认给豁免策略，让用户手动配置，避免误杀
            new_state.config = AppConfig{package_name, user_id, AppPolicy::EXEMPTED};
        }
        // 这就是关键的修复步骤！
        db_manager_->set_app_config(new_state.config);
    }
    
    new_state.current_status = AppRuntimeState::Status::STOPPED;
    auto [map_iterator, success] = managed_apps_.emplace(key, new_state);
    return &map_iterator->second;
}

void StateManager::add_pid_to_app(int pid, const std::string& package_name, int user_id, int uid) {
    AppRuntimeState* app = get_or_create_app_state(package_name, user_id);
    if (!app) return;
    if (app->uid == -1) app->uid = uid;
    if (app->app_name == package_name) {
        app->app_name = sys_monitor_->get_app_name_from_pid(pid);
    }
    if (std::find(app->pids.begin(), app->pids.end(), pid) == app->pids.end()) {
        app->pids.push_back(pid);
        pid_to_app_map_[pid] = app;
        if (app->current_status == AppRuntimeState::Status::STOPPED) {
           app->current_status = AppRuntimeState::Status::RUNNING;
        }
    }
}

void StateManager::remove_pid_from_app(int pid) {
    auto it = pid_to_app_map_.find(pid);
    if (it == pid_to_app_map_.end()) return;
    AppRuntimeState* app = it->second;
    pid_to_app_map_.erase(it);
    if (app) {
        auto& pids = app->pids;
        pids.erase(std::remove(pids.begin(), pids.end(), pid), pids.end());
        if (pids.empty()) {
            app->mem_usage_kb = 0;
            app->swap_usage_kb = 0;
            app->cpu_usage_percent = 0.0f;
            app->is_foreground = false;
            app->background_since = 0;
            app->observation_since = 0;
            app->undetected_since = 0;
        }
    }
}

bool StateManager::is_critical_system_app(const std::string& package_name) const {
    return critical_system_apps_.count(package_name) > 0;
}