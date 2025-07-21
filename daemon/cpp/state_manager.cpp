// daemon/cpp/state_manager.cpp
#include "state_manager.h"
#include <android/log.h>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <unistd.h>
#include <algorithm>
#include <sys/stat.h>
#include <unordered_map>
#include <ctime>

#define LOG_TAG "cerberusd_state_v7_final4"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;
using json = nlohmann::json;

static std::string status_to_string(const AppRuntimeState& app) {
    if (app.current_status == AppRuntimeState::Status::STOPPED) return "STOPPED";
    if (app.current_status == AppRuntimeState::Status::FROZEN) return "FROZEN";
    if (app.is_foreground) return "FOREGROUND";
    if (app.config.policy == AppPolicy::EXEMPTED || app.config.policy == AppPolicy::IMPORTANT) return "EXEMPTED_BACKGROUND";
    if (app.background_since > 0) {
        time_t now = time(nullptr);
        // This is a temporary calculation for display, actual timeout is in tick_state_machine
        int timeout_sec = (app.config.policy == AppPolicy::STRICT) ? 15 : 90;
        int remaining = timeout_sec - (now - app.background_since);
        if (remaining < 0) remaining = 0;
        return "PENDING_FREEZE (" + std::to_string(remaining) + "s)";
    }
    if (app.observation_since > 0) return "OBSERVING";
    return "BACKGROUND";
}

StateManager::StateManager(std::shared_ptr<DatabaseManager> db, std::shared_ptr<SystemMonitor> sys, std::shared_ptr<ActionExecutor> act)
    : db_manager_(db), sys_monitor_(sys), action_executor_(act) {
    LOGI("StateManager Initializing...");
    
    if (fs::exists("/sys/fs/cgroup/cgroup.controllers")) {
        default_freeze_method_ = FreezeMethod::CGROUP_V2;
        LOGI("Default freeze method set to CGROUP_V2.");
    } else {
        default_freeze_method_ = FreezeMethod::METHOD_SIGSTOP;
        LOGI("Default freeze method set to SIGSTOP.");
    }
    
    master_config_ = db_manager_->get_master_config().value_or(MasterConfig{});
    LOGI("Loaded master config: standard_timeout=%ds", master_config_.standard_timeout_sec);

        critical_system_apps_ = {
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
        "com.baidu.input",
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
        "com.iflytek.inputmethod",
        "com.iflytek.inputmethod.miui",
        "com.iflytek.inputmethod.googleplay",
        "com.iflytek.inputmethod.smartisan",
        "com.iflytek.inputmethod.oppo",
        "com.iflytek.inputmethod.oem",
        "com.iflytek.inputmethod.custom",
        "com.iflytek.inputmethod.blackshark",
        "com.iflytek.inputmethod.zte",
        "com.tencent.qqpinyin",
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

void StateManager::update_master_config(const MasterConfig& config) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    master_config_ = config;
    db_manager_->set_master_config(config);
    LOGI("Master config updated: standard_timeout=%ds", master_config_.standard_timeout_sec);
}

bool StateManager::update_foreground_state(const std::set<int>& top_pids) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    if (top_pids == last_known_top_pids_) {
        return false;
    }
    
    LOGD("Foreground PIDs changed. Old count: %zu, New count: %zu", last_known_top_pids_.size(), top_pids.size());
    last_known_top_pids_ = top_pids;
    
    std::set<AppInstanceKey> current_foreground_keys;
    for (int pid : top_pids) {
        auto it = pid_to_app_map_.find(pid);
        if (it != pid_to_app_map_.end()) {
            current_foreground_keys.insert({it->second->package_name, it->second->user_id});
        }
    }
    
    bool state_has_changed = false;
    time_t now = time(nullptr);
    for (auto& [key, app] : managed_apps_) {
        bool is_now_foreground = current_foreground_keys.count(key);
        if (app.is_foreground != is_now_foreground) {
            state_has_changed = true;
            app.is_foreground = is_now_foreground;
            // [修复] 严格的状态转换逻辑
            if (is_now_foreground) {
                // 进入前台: 清除所有计时器
                if (app.observation_since > 0 || app.background_since > 0) {
                    LOGI("State: Timers cancelled for %s (foreground).", key.first.c_str());
                }
                app.observation_since = 0; // <--- 关键修复
                app.background_since = 0;  // <--- 关键修复

                if (app.current_status == AppRuntimeState::Status::FROZEN) {
                    LOGI("State: Unfreezing %s (foreground).", key.first.c_str());
                    action_executor_->unfreeze(key, app.pids);
                    app.current_status = AppRuntimeState::Status::RUNNING;
                }
            } else {
                // 进入后台: 清除冻结计时器，启动观察期
                app.background_since = 0; // <--- 关键修复
                if (app.current_status == AppRuntimeState::Status::RUNNING && (app.config.policy == AppPolicy::STANDARD || app.config.policy == AppPolicy::STRICT) && !app.pids.empty()) {
                    LOGI("State: Observation period started for %s.", key.first.c_str());
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
    bool changed = false;
    time_t now = time(nullptr);

    for (auto& [key, app] : managed_apps_) {
        // [修复] 移除保险检查，因为它现在是多余的，且可能导致逻辑错误
        
        // 1. 处理观察期
        if (app.observation_since > 0 && !app.is_foreground && app.current_status == AppRuntimeState::Status::RUNNING) {
            if (now - app.observation_since >= 10) { // 10秒观察期结束
                LOGI("TICK: Observation for %s ended. Starting freeze timer.", app.package_name.c_str());
                app.observation_since = 0;
                app.background_since = now;
                changed = true;
            }
        }
        
        // 2. 处理冻结倒计时
        if (app.background_since > 0 && !app.is_foreground && app.current_status == AppRuntimeState::Status::RUNNING) {
            int timeout_sec = 0;
            if(app.config.policy == AppPolicy::STRICT) {
                timeout_sec = 15;
            } else if(app.config.policy == AppPolicy::STANDARD) {
                timeout_sec = master_config_.standard_timeout_sec;
            }
            
            if (timeout_sec > 0 && (now - app.background_since >= timeout_sec)) {
                LOGI("TICK: Timeout for %s. Freezing.", app.package_name.c_str());
                if (action_executor_->freeze(key, app.pids, default_freeze_method_)) {
                    app.current_status = AppRuntimeState::Status::FROZEN;
                    changed = true;
                }
                app.background_since = 0;
            }
        }
    }
    return changed;
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
                LOGD("App %s confirmed stopped after grace period.", app.package_name.c_str());
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
    
    for (const auto& policy_item : payload["policies"]) {
        AppConfig new_config;
        new_config.package_name = policy_item.value("package_name", "");
        new_config.user_id = policy_item.value("user_id", 0);
        new_config.policy = static_cast<AppPolicy>(policy_item.value("policy", 0));

        if (new_config.package_name.empty()) continue;
        db_manager_->set_app_config(new_config);
        
        AppRuntimeState* app = get_or_create_app_state(new_config.package_name, new_config.user_id);
        if (app) {
            if (app->config.policy != new_config.policy && app->current_status == AppRuntimeState::Status::FROZEN && (new_config.policy == AppPolicy::EXEMPTED || new_config.policy == AppPolicy::IMPORTANT)) {
                 LOGI("Policy for frozen app %s changed to exempt. Unfreezing.", app->package_name.c_str());
                 action_executor_->unfreeze({app->package_name, app->user_id}, app->pids);
                 app->current_status = AppRuntimeState::Status::RUNNING;
            }
            app->config = new_config;
        }
    }
    LOGI("New configuration applied.");
    return true; 
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
        json app_json;
        app_json["package_name"] = app.package_name;
        app_json["app_name"] = app.app_name;
        app_json["user_id"] = app.user_id;
        // [修复] 将 master_config 传递给状态字符串函数
        app_json["display_status"] = status_to_string(app, master_config_);
        app_json["mem_usage_kb"] = app.mem_usage_kb;
        app_json["swap_usage_kb"] = app.swap_usage_kb;
        app_json["cpu_usage_percent"] = app.cpu_usage_percent;
        app_json["is_whitelisted"] = app.config.policy == AppPolicy::EXEMPTED || app.config.policy == AppPolicy::IMPORTANT;
        app_json["is_foreground"] = app.is_foreground;
        apps_state.push_back(app_json);
    }
    payload["apps_runtime_state"] = apps_state;
    return payload;
}

json StateManager::get_full_config_for_ui() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    auto all_db_configs = db_manager_->get_all_app_configs();
    json response;
    response["master_config"] = {{"is_enabled", true}, {"freeze_on_screen_off", true}};
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
    AppRuntimeState new_state;
    new_state.package_name = package_name;
    new_state.user_id = user_id;
    new_state.app_name = package_name;
    auto config_opt = db_manager_->get_app_config(package_name, user_id);
    if (is_critical_system_app(package_name)) {
        new_state.config = AppConfig{package_name, user_id, AppPolicy::EXEMPTED};
    } else {
        new_state.config = config_opt.value_or(AppConfig{package_name, user_id, AppPolicy::EXEMPTED});
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
            // Keep the state, let the deep scan handle it after grace period
        }
    }
}

bool StateManager::is_critical_system_app(const std::string& package_name) const {
    return critical_system_apps_.count(package_name) > 0;
}