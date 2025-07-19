// daemon/cpp/state_manager.cpp
#include "state_manager.h"
#include "action_executor.h"
#include <android/log.h>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <unistd.h>
#include <algorithm>
#include <sys/stat.h>
#include <unordered_map>
#include <set>
#include <tuple>

#define LOG_TAG "cerberusd_state_v8.1" // Version bump for the fix
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;
using json = nlohmann::json;

static std::string status_to_string(AppRuntimeState::Status status) {
    switch (status) {
        case AppRuntimeState::Status::STOPPED: return "STOPPED";
        case AppRuntimeState::Status::FOREGROUND: return "FOREGROUND";
        case AppRuntimeState::Status::BACKGROUND_IDLE: return "BACKGROUND_IDLE";
        case AppRuntimeState::Status::AWAITING_FREEZE: return "AWAITING_FREEZE";
        case AppRuntimeState::Status::FROZEN: return "FROZEN";
        case AppRuntimeState::Status::EXEMPTED: return "EXEMPTED";
    }
    return "UNKNOWN";
}

StateManager::StateManager(std::shared_ptr<DatabaseManager> db, std::shared_ptr<SystemMonitor> sys, std::shared_ptr<ActionExecutor> act)
    : db_manager_(db), sys_monitor_(sys), action_executor_(act), probe_fd_(-1) {
    LOGI("StateManager (v8.1, Ultimate Unfreeze Model) Initializing...");
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
    reconcile_process_state();
    LOGI("StateManager Initialized.");
}

// [链接器修复] 恢复 on_app_state_changed_from_probe 函数的完整定义
void StateManager::on_app_state_changed_from_probe(const json& payload) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    try {
        std::string pkg = payload.at("package_name").get<std::string>();
        int user_id = payload.at("user_id").get<int>();
        bool is_fg = payload.at("is_foreground").get<bool>();

        AppRuntimeState* app = get_or_create_app_state(pkg, user_id);
        if (!app) return;
        
        if (app->is_foreground != is_fg) {
            app->is_foreground = is_fg;
            // This is a passive update, the main logic is handled by on_top_app_changed
            LOGD("Probe reported is_foreground for %s is now %d", pkg.c_str(), is_fg);
            if (!is_fg && app->current_status == AppRuntimeState::Status::FOREGROUND) {
                transition_state(*app, AppRuntimeState::Status::BACKGROUND_IDLE, "probe reported background");
            }
        }
    } catch (const json::exception& e) {
        LOGE("JSON error in on_app_state_changed: %s", e.what());
    }
}


bool StateManager::tick() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    auto now = std::chrono::steady_clock::now();
    bool probe_needs_update = false;

    reconcile_process_state();
    sys_monitor_->update_global_stats();
    global_stats_ = sys_monitor_->get_global_stats();

    for (auto& [key, app] : managed_apps_) {
        if (!app.pids.empty()) {
            sys_monitor_->update_app_stats(app.pids, app.mem_usage_kb, app.swap_usage_kb, app.cpu_usage_percent);
        }
        
        if (app.is_foreground) {
            if (app.current_status != AppRuntimeState::Status::FOREGROUND && app.config.policy != AppPolicy::EXEMPTED) {
                 transition_state(app, AppRuntimeState::Status::FOREGROUND, "detected foreground flag");
            }
            continue;
        }

        if (app.pids.empty() || 
            app.config.policy == AppPolicy::EXEMPTED || 
            app.config.policy == AppPolicy::IMPORTANT ||
            app.current_status == AppRuntimeState::Status::FROZEN) {
            continue;
        }
        
        bool has_temp_exemption = app.has_audio || app.has_notification;
        if (has_temp_exemption) {
            if(app.current_status == AppRuntimeState::Status::AWAITING_FREEZE) {
                transition_state(app, AppRuntimeState::Status::BACKGROUND_IDLE, "temp exemption started");
            }
            continue;
        }

        switch (app.current_status) {
            case AppRuntimeState::Status::BACKGROUND_IDLE: {
                long timeout_sec = -1;
                switch (app.config.policy) {
                    case AppPolicy::STRICT: timeout_sec = 10; break;
                    case AppPolicy::STANDARD: timeout_sec = is_screen_on_ ? 60 : 30; break;
                    default: break;
                }

                if (timeout_sec > 0) {
                    auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(now - app.last_state_change_time).count();
                    if (elapsed >= timeout_sec) { 
                        transition_state(app, AppRuntimeState::Status::AWAITING_FREEZE, "background timeout");
                    }
                }
                break;
            }
            case AppRuntimeState::Status::AWAITING_FREEZE: {
                auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(now - app.last_state_change_time).count();
                if (elapsed >= 5) {
                    if (action_executor_->freeze(key, app.pids)) {
                        if (transition_state(app, AppRuntimeState::Status::FROZEN, "grace period ended")) {
                           probe_needs_update = true;
                        }
                    } else {
                        LOGE("Freeze failed for %s, returning to idle.", app.package_name.c_str());
                        transition_state(app, AppRuntimeState::Status::BACKGROUND_IDLE, "freeze action failed");
                    }
                }
                break;
            }
            default:
                break;
        }
    }
    return probe_needs_update;
}

bool StateManager::on_top_app_changed(const json& payload) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    try {
        std::string pkg = payload.at("package_name").get<std::string>();
        int user_id = payload.at("user_id").get<int>();
        AppInstanceKey key = {pkg, user_id};

        LOGI("Probe reported top app is now: %s (user %d)", pkg.c_str(), user_id);

        for (auto& [other_key, other_app] : managed_apps_) {
            if (other_key != key && other_app.is_foreground) {
                LOGD("Clearing foreground flag for %s", other_key.first.c_str());
                other_app.is_foreground = false;
                if(other_app.current_status == AppRuntimeState::Status::FOREGROUND) {
                    transition_state(other_app, AppRuntimeState::Status::BACKGROUND_IDLE, "another app became top");
                }
            }
        }

        AppRuntimeState* app_ptr = get_or_create_app_state(pkg, user_id);
        if (!app_ptr) return false;
        AppRuntimeState& app = *app_ptr;

        if (!app.is_foreground) {
            app.is_foreground = true;
        }

        if (app.current_status == AppRuntimeState::Status::FROZEN) {
            LOGW("Focused app %s (user %d) was FROZEN. Unfreezing immediately!", pkg.c_str(), user_id);
            if (!action_executor_->unfreeze_and_cleanup(key)) {
                LOGE("ActionExecutor failed to unfreeze %s (user %d)", pkg.c_str(), user_id);
            }
            return transition_state(app, AppRuntimeState::Status::FOREGROUND, "became top activity");
        }
        
        if (app.current_status == AppRuntimeState::Status::AWAITING_FREEZE) {
            LOGI("Focused app %s (user %d) was AWAITING_FREEZE. Cancelling freeze.", pkg.c_str(), user_id);
            return transition_state(app, AppRuntimeState::Status::FOREGROUND, "became top while pending");
        }
    } catch (const json::exception& e) {
        LOGE("JSON error in on_top_app_changed: %s", e.what());
    }
    return false;
}

AppRuntimeState* StateManager::get_or_create_app_state(const std::string& package_name, int user_id) {
    if (package_name.empty()) return nullptr;
    AppInstanceKey key = {package_name, user_id};

    auto it = managed_apps_.find(key);
    if (it != managed_apps_.end()) {
        return &it->second;
    }
    
    LOGD("Creating new state for %s (user %d)...", package_name.c_str(), user_id);
    AppRuntimeState new_state;
    new_state.package_name = package_name;
    new_state.user_id = user_id;
    new_state.app_name = package_name;

    auto specific_config_opt = db_manager_->get_app_config(package_name, user_id);
    if (specific_config_opt) {
        new_state.config = *specific_config_opt;
    } else {
        AppConfig default_config;
        default_config.package_name = package_name;
        default_config.user_id = user_id;
        default_config.policy = AppPolicy::STANDARD;
        new_state.config = default_config;
    }
    
    if (is_critical_system_app(package_name)) {
        new_state.config.policy = AppPolicy::EXEMPTED;
    }
    
    new_state.current_status = (new_state.config.policy == AppPolicy::EXEMPTED) 
                               ? AppRuntimeState::Status::EXEMPTED 
                               : AppRuntimeState::Status::STOPPED;
    
    auto [map_iterator, success] = managed_apps_.emplace(key, new_state);
    if (!success) {
        LOGE("FATAL: Failed to emplace a new app state for %s.", package_name.c_str());
        return nullptr;
    }
    
    LOGD("Successfully created new state for %s (user %d). Policy: %d, Status: %s", 
        package_name.c_str(), user_id, 
        static_cast<int>(map_iterator->second.config.policy),
        status_to_string(map_iterator->second.current_status).c_str());
        
    return &map_iterator->second;
}

bool StateManager::on_config_changed_from_ui(const AppConfig& new_config) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    if (is_critical_system_app(new_config.package_name) && new_config.policy != AppPolicy::EXEMPTED) {
        LOGW("Attempt to set non-exempt policy for critical system app %s was denied.", new_config.package_name.c_str());
        return false;
    }
    
    db_manager_->set_app_config(new_config);
    AppRuntimeState* app_state_ptr = get_or_create_app_state(new_config.package_name, new_config.user_id);

    if (app_state_ptr) {
        AppRuntimeState& app_state = *app_state_ptr;
        app_state.config = new_config;
        LOGI("Applied new config for %s. Policy is now %d.", app_state.package_name.c_str(), (int)app_state.config.policy);

        if (app_state.config.policy == AppPolicy::EXEMPTED) {
            return transition_state(app_state, AppRuntimeState::Status::EXEMPTED, "policy changed to exempted");
        } else {
            if (app_state.current_status == AppRuntimeState::Status::EXEMPTED && !app_state.pids.empty()) {
                return transition_state(app_state, app_state.is_foreground ? AppRuntimeState::Status::FOREGROUND : AppRuntimeState::Status::BACKGROUND_IDLE, "policy changed to managed");
            }
        }
    }
    return false;
}

void StateManager::reconcile_process_state() {
    std::unordered_map<int, std::tuple<std::string, int, int>> current_pids;
    for (const auto& entry : fs::directory_iterator("/proc")) {
        if (!entry.is_directory()) continue;
        try {
            int pid = std::stoi(entry.path().filename().string());
            int uid = -1, user_id = -1;
            std::string pkg_name = get_package_name_from_pid(pid, uid, user_id);
            if (uid >= 10000 && !pkg_name.empty()) {
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
    for (int pid : dead_pids) {
        remove_pid_from_app(pid);
    }

    for(const auto& [pid, info_tuple] : current_pids) {
        if (pid_to_app_map_.find(pid) == pid_to_app_map_.end()) {
            const auto& [pkg_name, user_id, uid] = info_tuple;
            add_pid_to_app(pid, pkg_name, user_id, uid);
        }
    }
}

void StateManager::add_pid_to_app(int pid, const std::string& package_name, int user_id, int uid) {
    AppRuntimeState* app = get_or_create_app_state(package_name, user_id);
    if (!app) return;
    if (app->uid == -1) app->uid = uid;
    
    if (app->app_name.empty() || app->app_name == app->package_name) {
        app->app_name = sys_monitor_->get_app_name_from_pid(pid);
        if(app->app_name.empty() || app->app_name == app->package_name) app->app_name = package_name;
    }

    auto pid_it = std::find(app->pids.begin(), app->pids.end(), pid);
    if (pid_it == app->pids.end()) {
        app->pids.push_back(pid);
        pid_to_app_map_[pid] = app;

        if (app->current_status == AppRuntimeState::Status::STOPPED) {
            if(app->config.policy == AppPolicy::EXEMPTED) {
                 transition_state(*app, AppRuntimeState::Status::EXEMPTED, "process started");
            } else {
                 transition_state(*app, AppRuntimeState::Status::BACKGROUND_IDLE, "process started");
            }
        } else if (app->current_status == AppRuntimeState::Status::FROZEN) {
            LOGW("New PID %d born into frozen app %s. Freezing immediately.", pid, app->package_name.c_str());
            action_executor_->move_pids_to_instance_cgroup({app->package_name, user_id}, {pid});
        }
    }
}

void StateManager::remove_pid_from_app(int pid) {
    auto it = pid_to_app_map_.find(pid);
    if (it == pid_to_app_map_.end()) return;

    AppRuntimeState* app = it->second;
    pid_to_app_map_.erase(it);
    auto& pids = app->pids;
    pids.erase(std::remove(pids.begin(), pids.end(), pid), pids.end());

    if (pids.empty() && app->current_status != AppRuntimeState::Status::STOPPED) {
        transition_state(*app, AppRuntimeState::Status::STOPPED, "all processes exited");
        app->mem_usage_kb = 0;
        app->swap_usage_kb = 0;
        app->cpu_usage_percent = 0.0f;
    }
}

bool StateManager::transition_state(AppRuntimeState& app, AppRuntimeState::Status new_status, const std::string& reason) {
    if (app.current_status == new_status) return false;
    AppInstanceKey key = {app.package_name, app.user_id};
    LOGI("State transition for %s (user %d): %s -> %s. Reason: %s", app.package_name.c_str(), app.user_id, status_to_string(app.current_status).c_str(), status_to_string(new_status).c_str(), reason.c_str());
    bool was_frozen = app.current_status == AppRuntimeState::Status::FROZEN;
    if (was_frozen && new_status != AppRuntimeState::Status::FROZEN) {
        action_executor_->unfreeze_and_cleanup(key);
    }
    app.current_status = new_status;
    app.last_state_change_time = std::chrono::steady_clock::now();
    bool is_now_frozen = new_status == AppRuntimeState::Status::FROZEN;
    return was_frozen != is_now_frozen;
}

void StateManager::on_probe_hello(int probe_fd) { std::lock_guard<std::mutex> lock(state_mutex_); probe_fd_ = probe_fd; }
void StateManager::on_probe_disconnect() { std::lock_guard<std::mutex> lock(state_mutex_); probe_fd_ = -1; }
void StateManager::on_system_state_changed_from_probe(const json& payload) { std::lock_guard<std::mutex> lock(state_mutex_); if (payload.contains("screen_on")) { is_screen_on_ = payload.at("screen_on").get<bool>(); } }

std::string StateManager::get_package_name_from_pid(int pid, int& uid, int& user_id) {
    constexpr int PER_USER_RANGE = 100000;
    uid = -1; user_id = -1;
    struct stat st;
    std::string proc_path = "/proc/" + std::to_string(pid);
    if (stat(proc_path.c_str(), &st) != 0) return "";
    
    uid = st.st_uid;
    user_id = uid / PER_USER_RANGE;
    
    std::ifstream cmdline_file(proc_path + "/cmdline");
    if (!cmdline_file.is_open()) return "";
    
    std::string cmdline;
    std::getline(cmdline_file, cmdline, '\0'); 
    
    if (cmdline.find('.') == std::string::npos || cmdline.length() < 3) {
        return "";
    }
    
    auto pos = cmdline.find(':');
    if(pos != std::string::npos) {
        return cmdline.substr(0, pos);
    }
    return cmdline;
}

bool StateManager::is_critical_system_app(const std::string& package_name) const {
    if (package_name.empty()) return false;
    return critical_system_apps_.count(package_name) > 0;
}

void StateManager::load_all_configs() {
    auto configs = db_manager_->get_all_app_configs();
    for(const auto& db_config : configs){
        get_or_create_app_state(db_config.package_name, db_config.user_id);
    }
}

json StateManager::get_dashboard_payload() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    json payload;
    payload["global_stats"] = { {"total_cpu_usage_percent", global_stats_.total_cpu_usage_percent}, {"total_mem_kb", global_stats_.total_mem_kb}, {"avail_mem_kb", global_stats_.avail_mem_kb}, {"swap_total_kb", global_stats_.swap_total_kb}, {"swap_free_kb", global_stats_.swap_free_kb}, {"active_profile_name", is_screen_on_ ? "⚡️ 性能模式" : "🧊 息屏模式"} };
    json apps_state = json::array();
    for (const auto& [key, app] : managed_apps_) {
        if (app.pids.empty() && app.current_status == AppRuntimeState::Status::STOPPED) continue;
        json app_json;
        app_json["package_name"] = app.package_name;
        app_json["app_name"] = app.app_name;
        app_json["user_id"] = app.user_id;
        app_json["display_status"] = status_to_string(app.current_status);
        app_json["mem_usage_kb"] = app.mem_usage_kb;
        app_json["swap_usage_kb"] = app.swap_usage_kb;
        app_json["cpu_usage_percent"] = app.cpu_usage_percent;
        app_json["is_whitelisted"] = app.config.policy == AppPolicy::EXEMPTED;
        app_json["is_foreground"] = app.is_foreground;
        app_json["hasPlayback"] = app.has_audio;
        app_json["hasNotification"] = app.has_notification;
        app_json["hasNetworkActivity"] = false;
        int pending_sec = 0;
        if (app.current_status == AppRuntimeState::Status::AWAITING_FREEZE) {
            auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(std::chrono::steady_clock::now() - app.last_state_change_time).count();
            pending_sec = std::max(0LL, 5LL - elapsed);
        }
        app_json["pendingFreezeSec"] = pending_sec;
        apps_state.push_back(app_json);
    }
    payload["apps_runtime_state"] = apps_state;
    return payload;
}

json StateManager::get_full_config_for_ui() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    json response;
    response["hard_safety_net"] = json(critical_system_apps_);
    auto all_db_configs = db_manager_->get_all_app_configs();
    json policies = json::array();
    for (const auto& config : all_db_configs) {
        json app_policy;
        app_policy["package_name"] = config.package_name;
        app_policy["user_id"] = config.user_id;
        app_policy["policy"] = static_cast<int>(config.policy);
        app_policy["force_playback_exempt"] = config.force_playback_exempt;
        app_policy["force_network_exempt"] = config.force_network_exempt;
        policies.push_back(app_policy);
    }
    response["policies"] = policies;
    return response;
}

json StateManager::get_probe_config_payload() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    json payload;
    json frozen_apps = json::array();
    for(const auto& [key, app] : managed_apps_) {
        if (app.current_status == AppRuntimeState::Status::FROZEN) {
            frozen_apps.push_back({{"package_name", app.package_name}, {"user_id", app.user_id}});
        }
    }
    payload["policies"] = json::array();
    payload["frozen_apps"] = frozen_apps;
    return payload;
}