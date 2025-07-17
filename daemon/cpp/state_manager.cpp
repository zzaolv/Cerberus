// daemon/cpp/state_manager.cpp
#include "state_manager.h"
#include <android/log.h>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <unistd.h>
#include <algorithm>
#include <vector>

#define LOG_TAG "cerberusd_state"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;
using json = nlohmann::json;

constexpr int PER_USER_RANGE = 100000;
constexpr int POWER_CHECK_INTERVAL_SECONDS = 60;
constexpr int IDLE_TIMEOUT_SECONDS_DEEP = 60 * 60; // 60分钟进入深度休眠
constexpr int AWAIT_FREEZE_SECONDS = 5; // 后台超时后等待冻结的时间

// Helper to convert status enum to string for logging
static std::string status_to_string(AppRuntimeState::Status status) {
    switch (status) {
        case AppRuntimeState::Status::STOPPED: return "STOPPED";
        case AppRuntimeState::Status::FOREGROUND: return "FOREGROUND";
        case AppRuntimeState::Status::BACKGROUND_ACTIVE: return "BACKGROUND_ACTIVE";
        case AppRuntimeState::Status::BACKGROUND_IDLE: return "BACKGROUND_IDLE";
        case AppRuntimeState::Status::AWAITING_FREEZE: return "AWAITING_FREEZE";
        case AppRuntimeState::Status::FROZEN: return "FROZEN";
        case AppRuntimeState::Status::EXEMPTED: return "EXEMPTED";
    }
    return "UNKNOWN";
}

// Constructor: Initializes the state manager
StateManager::StateManager(std::shared_ptr<DatabaseManager> db, std::shared_ptr<SystemMonitor> sys, std::shared_ptr<ActionExecutor> act)
    : db_manager_(db), sys_monitor_(sys), action_executor_(act) {
    LOGI("StateManager (v1.1.1) Initializing...");
    
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
        "org.protonaosp.deviceconfig.auto_generated_rro_product__",
    };
   
    refresh_app_list_from_db();
    initial_scan();
    last_power_check_time_ = std::chrono::steady_clock::now();
    last_doze_state_change_time_ = std::chrono::steady_clock::now();

    db_manager_->log_event(LogEventType::DAEMON_START, {{"message", "Cerberus daemon started."}});
    LOGI("StateManager Initialized.");
}

// --- Settings ---
void StateManager::set_freezer_type(FreezerType type) {
    current_freezer_type_ = type;
    LOGI("StateManager freezer type set to: %d", static_cast<int>(type));
}

void StateManager::set_periodic_unfreeze_interval(int minutes) {
    periodic_unfreeze_interval_min_ = minutes;
    LOGI("StateManager periodic unfreeze interval set to: %d minutes", minutes);
}

json StateManager::get_current_settings_as_json() const {
    return {
        {"freezer_type", static_cast<int>(current_freezer_type_.load())},
        {"unfreeze_interval", periodic_unfreeze_interval_min_.load()}
    };
}

// --- Core Logic Tick ---
void StateManager::tick() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    check_and_update_foreground_status();
    tick_app_states();
    tick_doze_state();
    tick_power_state();
}

void StateManager::tick_app_states() {
    auto now = std::chrono::steady_clock::now();
    for (auto& [key, app] : managed_apps_) {
        // 1. Safety check: if app becomes empty or is exempted, ensure it's not managed
        if (app.pids.empty() || app.config.policy == AppPolicy::EXEMPTED || is_critical_system_app(app.package_name)) {
            if (app.current_status != AppRuntimeState::Status::EXEMPTED && app.current_status != AppRuntimeState::Status::STOPPED) {
                 transition_state(app, AppRuntimeState::Status::EXEMPTED, "Safety Net or Exempted Policy");
            }
            continue;
        }

        // 2. State transition logic for managed apps
        if (app.current_status == AppRuntimeState::Status::BACKGROUND_IDLE) {
            auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(now - app.last_state_change_time).count();
            // Get timeout based on policy
            long timeout = 30; // Default for STANDARD
            if(app.config.policy == AppPolicy::IMPORTANT) timeout = 180;
            else if(app.config.policy == AppPolicy::STRICT) timeout = 10;
            
            if (elapsed > timeout) {
                transition_state(app, AppRuntimeState::Status::AWAITING_FREEZE, "Background timeout");
            }
        } else if (app.current_status == AppRuntimeState::Status::AWAITING_FREEZE) {
             auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(now - app.last_state_change_time).count();
            if (elapsed > AWAIT_FREEZE_SECONDS) { 
                transition_state(app, AppRuntimeState::Status::FROZEN, "Awaiting period ended");
            }
        }
    }
}

void StateManager::tick_doze_state() {
    auto now = std::chrono::steady_clock::now();
    auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(now - last_doze_state_change_time_).count();
    
    bool is_charging = battery_stats_ && (battery_stats_->status.find("Charging") != std::string::npos);

    if (is_screen_on_ || is_charging) {
        if (doze_state_ != DozeState::ACTIVE) {
            transition_doze_state(DozeState::ACTIVE, is_screen_on_ ? "Screen on" : "Charging");
        }
        return;
    }
    
    if (doze_state_ == DozeState::ACTIVE && elapsed > 60) { // Screen off for 1 minute
        transition_doze_state(DozeState::IDLE, "Screen off and not charging");
    } else if (doze_state_ == DozeState::IDLE && elapsed > IDLE_TIMEOUT_SECONDS_DEEP) {
        transition_doze_state(DozeState::DEEP_IDLE, "Idle timeout reached");
    }
}

void StateManager::tick_power_state() {
    auto now = std::chrono::steady_clock::now();
    auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(now - last_power_check_time_).count();

    if (elapsed < POWER_CHECK_INTERVAL_SECONDS) return;
    
    last_power_check_time_ = now;
    battery_stats_ = sys_monitor_->get_battery_stats();
    if (!battery_stats_) return;

    json payload = {
        {"capacity", battery_stats_->capacity},
        {"temperature", static_cast<float>(battery_stats_->temp_deci_celsius) / 10.0f},
        {"power_watt", battery_stats_->power_watt}
    };
    
    LogEventType type = LogEventType::POWER_UPDATE;
    if (last_capacity_ != -1 && last_capacity_ > battery_stats_->capacity) {
        int capacity_drop = last_capacity_ - battery_stats_->capacity;
        int duration_min = static_cast<int>(elapsed / 60);
        if (duration_min == 0) duration_min = 1;
        payload["consumption_percent"] = capacity_drop;
        payload["consumption_duration_min"] = duration_min;
        if (capacity_drop * 60 / duration_min >= 30) { // Rate > 30%/hr is high
            type = LogEventType::POWER_WARNING;
        }
    }
    db_manager_->log_event(type, payload);
    last_capacity_ = battery_stats_->capacity;
}

// --- Event Handlers ---
void StateManager::handle_probe_event(const nlohmann::json& event) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    try {
        std::string type = event.value("type", "");
        const auto& payload = event["payload"];
        
        if (type == "event.doze_state_changed") {
            handle_doze_event(payload);
            return;
        }

        if (type == "event.screen_on") {
            is_screen_on_ = true;
            db_manager_->log_event(LogEventType::SCREEN_ON, {{"message", "Screen ON"}});
            return;
        } else if (type == "event.screen_off") {
            is_screen_on_ = false;
            db_manager_->log_event(LogEventType::SCREEN_OFF, {{"message", "Screen OFF"}});
            return;
        }

        std::string pkg = payload.value("package_name", "");
        int user_id = payload.value("user_id", 0);
        if (pkg.empty()) return;

        AppRuntimeState* app = get_or_create_app_state(pkg, user_id);
        if (!app) return;

        if (type == "event.notification_post") {
            app->has_notification = true;
            if (app->current_status == AppRuntimeState::Status::FROZEN) {
                 transition_state(*app, AppRuntimeState::Status::BACKGROUND_ACTIVE, "Notification received");
            }
        }

    } catch (const std::exception& e) {
        LOGE("Error handling probe event: %s", e.what());
    }
}

void StateManager::handle_doze_event(const nlohmann::json& payload) {
    std::string state_name = payload.value("state", "");
    std::string debug_info = payload.value("debug", "");

    db_manager_->log_event(LogEventType::DOZE_STATE_CHANGE, {
        {"status", state_name}, {"debug_info", debug_info}
    });

    if (state_name.find("IDLE") != std::string::npos && doze_state_ != DozeState::DEEP_IDLE) {
        transition_doze_state(DozeState::IDLE, "Probe event: " + state_name);
    } else if (state_name == "FINISH" || state_name == "ACTIVE") {
        transition_doze_state(DozeState::ACTIVE, "Probe event: " + state_name);
    }
}

void StateManager::process_event_handler(ProcessEventType type, int pid, int ppid) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    if (type == ProcessEventType::EXIT) {
        remove_pid_from_app(pid);
    } else if (type == ProcessEventType::FORK) {
        AppRuntimeState* parent_app = find_app_by_pid(ppid);
        if (parent_app) {
             int uid = -1, user_id = -1;
             std::string pkg_name = get_package_name_from_pid(pid, uid, user_id);
             if(!pkg_name.empty() && pkg_name == parent_app->package_name){
                add_pid_to_app(pid, parent_app->package_name, parent_app->user_id, parent_app->uid);
             }
        }
    }
}


// --- State Transition & Logging ---
void StateManager::transition_state(AppRuntimeState& app, AppRuntimeState::Status new_status, const std::string& reason) {
    if (app.current_status == new_status) return;
    
    auto old_status = app.current_status;
    auto now = std::chrono::steady_clock::now();
    
    json log_payload;
    log_payload["app_name"] = app.app_name;
    log_payload["package_name"] = app.package_name;
    log_payload["reason"] = reason;
    
    LogEventType event_type = LogEventType::GENERIC_INFO;
    bool was_active = (old_status != AppRuntimeState::Status::STOPPED && old_status != AppRuntimeState::Status::FROZEN && old_status != AppRuntimeState::Status::EXEMPTED);

    if (was_active && (new_status == AppRuntimeState::Status::STOPPED || new_status == AppRuntimeState::Status::FROZEN)) {
        auto duration = std::chrono::duration_cast<std::chrono::seconds>(now - app.active_session_start_time).count();
        if (duration >= 0) { // Allow 0 duration for very short-lived processes
            db_manager_->update_app_runtime(app.package_name, duration);
            auto updated_config = db_manager_->get_app_config(app.package_name);
            if(updated_config) {
                app.config = *updated_config;
                log_payload["session_duration_s"] = duration;
                log_payload["cumulative_duration_s"] = app.config.cumulative_runtime_seconds;
            }
        }
    }
    
    if (!was_active && (new_status == AppRuntimeState::Status::FOREGROUND || new_status == AppRuntimeState::Status::BACKGROUND_ACTIVE || new_status == AppRuntimeState::Status::BACKGROUND_IDLE)) {
        app.active_session_start_time = now;
    }

    switch(new_status) {
        case AppRuntimeState::Status::FOREGROUND:
            event_type = LogEventType::APP_FOREGROUND;
            if (old_status == AppRuntimeState::Status::FROZEN) {
                action_executor_->unfreeze_pids(app.pids, current_freezer_type_.load());
            }
            break;
        case AppRuntimeState::Status::STOPPED:
            event_type = LogEventType::APP_STOP;
            break;
        case AppRuntimeState::Status::FROZEN:
            event_type = LogEventType::APP_FROZEN;
            log_payload["pid_count"] = app.pids.size();
            if (action_executor_->freeze_pids(app.pids, current_freezer_type_.load())) {
                 db_manager_->log_event(event_type, log_payload);
            }
            break;
        case AppRuntimeState::Status::EXEMPTED:
        case AppRuntimeState::Status::BACKGROUND_IDLE:
        case AppRuntimeState::Status::BACKGROUND_ACTIVE:
            if (old_status == AppRuntimeState::Status::FROZEN) {
                action_executor_->unfreeze_pids(app.pids, current_freezer_type_.load());
                db_manager_->log_event(LogEventType::APP_UNFROZEN, {
                    {"app_name", app.app_name}, {"pid_count", app.pids.size()}, {"reason", reason}
                });
            }
            break;
        default: break;
    }
    
    if (new_status != AppRuntimeState::Status::FROZEN) {
        db_manager_->log_event(event_type, log_payload);
    }
    
    LOGI("State transition for %s (user %d): %s -> %s", app.package_name.c_str(), app.user_id, status_to_string(old_status).c_str(), status_to_string(new_status).c_str());
    app.current_status = new_status;
    app.last_state_change_time = now;
}

// --- Doze Management ---
void StateManager::transition_doze_state(DozeState new_state, const std::string& reason) {
    if (doze_state_ == new_state) return;
    DozeState old_state = doze_state_;
    doze_state_ = new_state;
    last_doze_state_change_time_ = std::chrono::steady_clock::now();
    
    if (old_state == DozeState::DEEP_IDLE && new_state != DozeState::DEEP_IDLE) exit_deep_doze_actions();
    if (new_state == DozeState::DEEP_IDLE) enter_deep_doze_actions();

    LOGI("Doze state transition: %d -> %d. Reason: %s", (int)old_state, (int)new_state, reason.c_str());
}

void StateManager::enter_deep_doze_actions() {
    LOGI("Entering DEEP_IDLE. Applying aggressive optimizations.");
    db_manager_->log_event(LogEventType::DOZE_STATE_CHANGE, {{"status", "深度模式"}, {"debug_info", "系统已进入深度Doze"}});
    start_doze_resource_snapshot();
    json batch_payload = {{"title", "以下为进入深度Doze后的集中处理:"}, {"actions", json::array()}};
    for (auto& [key, app] : managed_apps_) {
        if (app.pids.empty() || app.config.policy == AppPolicy::EXEMPTED || is_critical_system_app(app.package_name)) continue;
        if (action_executor_->block_network(app.uid)) {
            app.is_network_blocked = true;
            batch_payload["actions"].push_back({{"type", "network_block"}, {"app_name", app.app_name}});
        }
        if (app.current_status != AppRuntimeState::Status::FROZEN) {
            transition_state(app, AppRuntimeState::Status::FROZEN, "Deep Doze");
        }
    }
    if (!batch_payload["actions"].empty()) {
        db_manager_->log_event(LogEventType::BATCH_OPERATION_START, batch_payload);
    }
}

void StateManager::exit_deep_doze_actions() {
    LOGI("Exiting DEEP_IDLE. Reverting optimizations.");
    generate_doze_exit_report();
    for (auto& [key, app] : managed_apps_) {
        if (app.is_network_blocked) {
            action_executor_->unblock_network(app.uid);
            app.is_network_blocked = false;
        }
        if (app.current_status == AppRuntimeState::Status::FROZEN) {
            transition_state(app, AppRuntimeState::Status::BACKGROUND_IDLE, "Exiting Deep Doze");
        }
    }
}

void StateManager::start_doze_resource_snapshot() {
    doze_cpu_snapshot_.clear();
    for (const auto& [pid, app_ptr] : pid_to_app_map_) {
        if(app_ptr->pids.empty()) continue;
        doze_cpu_snapshot_[pid] = sys_monitor_->get_app_stats(pid, "", 0).cpu_time_jiffies;
    }
}

void StateManager::generate_doze_exit_report() {
    if (doze_cpu_snapshot_.empty()) return;
    std::map<std::string, long long> app_cpu_delta;
    long jiffy_hz = sysconf(_SC_CLK_TCK);
    for (const auto& [pid, app_ptr] : pid_to_app_map_) {
         if (app_ptr->pids.empty()) continue;
         auto it = doze_cpu_snapshot_.find(pid);
         if (it != doze_cpu_snapshot_.end()) {
             long long end_jiffies = sys_monitor_->get_app_stats(pid, "", 0).cpu_time_jiffies;
             if (end_jiffies > it->second) {
                 app_cpu_delta[app_ptr->app_name] += (end_jiffies - it->second);
             }
         }
    }
    if (app_cpu_delta.empty()) return;

    std::vector<std::pair<std::string, long long>> sorted_apps(app_cpu_delta.begin(), app_cpu_delta.end());
    std::sort(sorted_apps.begin(), sorted_apps.end(), [](const auto& a, const auto& b) { return a.second > b.second; });
    
    json report_payload = {{"title", "Doze期间应用的CPU活跃时间:"}, {"entries", json::array()}};
    for (const auto& pair : sorted_apps) {
        float active_time_sec = static_cast<float>(pair.second) / jiffy_hz;
        if (active_time_sec > 0.1) {
             report_payload["entries"].push_back({{"app_name", pair.first}, {"active_time_sec", active_time_sec}});
        }
    }
    if(!report_payload["entries"].empty()) {
        db_manager_->log_event(LogEventType::DOZE_RESOURCE_REPORT, report_payload);
    }
    doze_cpu_snapshot_.clear();
}

// --- App Management & UI Interaction ---
void StateManager::update_app_config_from_ui(const AppConfig& new_config) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    if (is_critical_system_app(new_config.package_name)) {
        LOGW("UI tried to set policy for critical app %s. Rejected.", new_config.package_name.c_str());
        return;
    }
    if (!db_manager_->set_app_config(new_config)) {
        LOGE("Failed to save app config to DB for %s", new_config.package_name.c_str());
    }
    for (auto& [key, app] : managed_apps_) {
        if (key.first == new_config.package_name) {
            app.config = new_config;
            if (app.config.policy == AppPolicy::EXEMPTED && app.current_status != AppRuntimeState::Status::EXEMPTED) {
                transition_state(app, AppRuntimeState::Status::EXEMPTED, "Policy changed by user");
            } else if (app.config.policy != AppPolicy::EXEMPTED && app.current_status == AppRuntimeState::Status::EXEMPTED) {
                transition_state(app, AppRuntimeState::Status::BACKGROUND_IDLE, "Policy changed by user");
            }
        }
    }
}

const std::unordered_set<std::string>& StateManager::get_safety_net_list() const {
    return critical_system_apps_;
}

nlohmann::json StateManager::get_dashboard_payload() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    json payload;
    payload["global_stats"] = {
        {"total_cpu_usage_percent", global_stats_.total_cpu_usage_percent},
        {"total_mem_kb", global_stats_.total_mem_kb},
        {"avail_mem_kb", global_stats_.avail_mem_kb},
        {"swap_total_kb", global_stats_.swap_total_kb},
        {"swap_free_kb", global_stats_.swap_free_kb},
        {"active_profile_name", (doze_state_ == DozeState::DEEP_IDLE ? "🌙 深度休眠" : (is_screen_on_ ? "平衡模式" : "⚡️ 省电模式"))}
    };

    json apps_state = json::array();
    for (const auto& [key, app] : managed_apps_) {
        if (app.current_status != AppRuntimeState::Status::STOPPED) { 
            json app_json;
            app_json["package_name"] = app.package_name;
            app_json["app_name"] = app.app_name;
            app_json["user_id"] = app.user_id;
            app_json["display_status"] = status_to_string(app.current_status);
            app_json["mem_usage_kb"] = app.mem_usage_kb;
            app_json["swap_usage_kb"] = app.swap_usage_kb;
            app_json["cpu_usage_percent"] = app.cpu_usage_percent;
            app_json["is_whitelisted"] = (app.config.policy == AppPolicy::EXEMPTED || is_critical_system_app(app.package_name));
            app_json["is_foreground"] = (app.current_status == AppRuntimeState::Status::FOREGROUND);
            app_json["hasPlayback"] = false;
            app_json["hasNotification"] = app.has_notification;
            app_json["hasNetworkActivity"] = app.has_network_activity;
            app_json["pendingFreezeSec"] = 0;
            if (app.current_status == AppRuntimeState::Status::AWAITING_FREEZE) {
                auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(std::chrono::steady_clock::now() - app.last_state_change_time).count();
                app_json["pendingFreezeSec"] = AWAIT_FREEZE_SECONDS - elapsed > 0 ? AWAIT_FREEZE_SECONDS - elapsed : 0;
            }
            apps_state.push_back(app_json);
        }
    }
    payload["apps_runtime_state"] = apps_state;
    return payload;
}

// --- Helper Functions ---
void StateManager::refresh_app_list_from_db() {
    auto configs = db_manager_->get_all_app_configs();
    for(const auto& config : configs){
        get_or_create_app_state(config.package_name, 0)->config = config;
    }
}

void StateManager::initial_scan() {
    LOGI("Performing initial process scan...");
    for (const auto& entry : fs::directory_iterator("/proc")) {
        if (!entry.is_directory()) continue;
        try {
            int pid = std::stoi(entry.path().filename().string());
            int uid = -1, user_id = -1;
            std::string pkg_name = get_package_name_from_pid(pid, uid, user_id);
            if (!pkg_name.empty()) {
                add_pid_to_app(pid, pkg_name, user_id, uid);
            }
        } catch (const std::invalid_argument&) { continue; }
    }
    LOGI("Initial scan completed. Found %zu tracked processes.", pid_to_app_map_.size());
}

AppRuntimeState* StateManager::get_or_create_app_state(const std::string& package_name, int user_id) {
    AppInstanceKey key = {package_name, user_id};
    if (auto it = managed_apps_.find(key); it != managed_apps_.end()) return &it->second;
    
    AppRuntimeState new_state;
    new_state.package_name = package_name;
    new_state.user_id = user_id;
    new_state.app_name = package_name; // Placeholder
    
    if (is_critical_system_app(package_name)) {
        new_state.config.policy = AppPolicy::EXEMPTED;
    } else {
        new_state.config = db_manager_->get_app_config(package_name).value_or(AppConfig{package_name, AppPolicy::EXEMPTED});
    }
    
    auto [it, inserted] = managed_apps_.emplace(key, new_state);
    return &it->second;
}

void StateManager::add_pid_to_app(int pid, const std::string& package_name, int user_id, int uid) {
    AppRuntimeState* app = get_or_create_app_state(package_name, user_id);
    if (!app) return;

    if (app->uid == -1) app->uid = uid;
    if (app->app_name == app->package_name) {
        std::ifstream stat_file("/proc/" + std::to_string(pid) + "/stat");
        if (stat_file.is_open()) {
            std::string temp, name;
            stat_file >> temp >> name;
            if (name.length() > 2) app->app_name = name.substr(1, name.length() - 2);
        }
    }

    if (std::find(app->pids.begin(), app->pids.end(), pid) == app->pids.end()) {
        app->pids.push_back(pid);
        pid_to_app_map_[pid] = app;
        if (app->current_status == AppRuntimeState::Status::STOPPED) {
             std::string reason = "New process discovered";
             if (app->config.policy == AppPolicy::EXEMPTED || is_critical_system_app(package_name)) {
                 transition_state(*app, AppRuntimeState::Status::EXEMPTED, reason);
             } else {
                 transition_state(*app, AppRuntimeState::Status::BACKGROUND_IDLE, reason);
             }
        }
    }
}

void StateManager::remove_pid_from_app(int pid) {
    auto it = pid_to_app_map_.find(pid);
    if (it == pid_to_app_map_.end()) return;
    AppRuntimeState* app = it->second;
    pid_to_app_map_.erase(it);
    if (app) {
        auto pid_it = std::remove(app->pids.begin(), app->pids.end(), pid);
        app->pids.erase(pid_it, app->pids.end());
        if (app->pids.empty() && app->current_status != AppRuntimeState::Status::STOPPED) {
            transition_state(*app, AppRuntimeState::Status::STOPPED, "All processes exited");
        }
    }
}

std::string StateManager::get_package_name_from_pid(int pid, int& uid, int& user_id) {
    struct stat st;
    std::string proc_path = "/proc/" + std::to_string(pid);
    if (stat(proc_path.c_str(), &st) != 0) return "";
    uid = st.st_uid;
    user_id = uid / PER_USER_RANGE;
    std::ifstream cmdline_file(proc_path + "/cmdline");
    if (!cmdline_file.is_open()) return "";
    std::string cmdline;
    std::getline(cmdline_file, cmdline, '\0'); 
    if (cmdline.empty()) return "";
    if (auto pos = cmdline.find(':'); pos != std::string::npos) cmdline = cmdline.substr(0, pos);
    if (auto pos = cmdline.find('@'); pos != std::string::npos) cmdline = cmdline.substr(0, pos);
    return cmdline;
}

void StateManager::check_and_update_foreground_status() {
    std::string fg_pkg;
    int fg_uid = -1;
    if (std::ifstream fg_file("/dev/cpuset/foreground/tasks"); fg_file.is_open() && fg_file.peek() != EOF) {
        int fg_pid;
        fg_file >> fg_pid;
        int user_id = -1;
        fg_pkg = get_package_name_from_pid(fg_pid, fg_uid, user_id);
    }
    
    for(auto& [key, app] : managed_apps_){
        if(app.pids.empty()) continue;
        bool is_foreground = (!fg_pkg.empty() && app.package_name == fg_pkg && app.uid == fg_uid);
        if(is_foreground && app.current_status != AppRuntimeState::Status::FOREGROUND){
             transition_state(app, AppRuntimeState::Status::FOREGROUND, "App became foreground");
        } else if (!is_foreground && app.current_status == AppRuntimeState::Status::FOREGROUND) {
            transition_state(app, AppRuntimeState::Status::BACKGROUND_IDLE, "App moved to background");
        }
    }
}

AppRuntimeState* StateManager::find_app_by_pid(int pid) {
    auto it = pid_to_app_map_.find(pid);
    return it != pid_to_app_map_.end() ? it->second : nullptr;
}

void StateManager::update_all_resource_stats() {
    sys_monitor_->update_global_stats();
    global_stats_ = sys_monitor_->get_global_stats();
    for(auto& [key, app] : managed_apps_){
        if(app.pids.empty()){
            app.mem_usage_kb = 0; app.swap_usage_kb = 0; app.cpu_usage_percent = 0.0f;
            continue;
        }
        AppStatsData total_stats;
        for(int pid : app.pids){
            AppStatsData pid_stats = sys_monitor_->get_app_stats(pid, app.package_name, app.user_id);
            total_stats.mem_usage_kb += pid_stats.mem_usage_kb;
            total_stats.swap_usage_kb += pid_stats.swap_usage_kb;
            total_stats.cpu_usage_percent += pid_stats.cpu_usage_percent;
        }
        app.mem_usage_kb = total_stats.mem_usage_kb;
        app.swap_usage_kb = total_stats.swap_usage_kb;
        app.cpu_usage_percent = total_stats.cpu_usage_percent;
    }
}

bool StateManager::is_critical_system_app(const std::string& package_name) const {
    if (package_name.rfind("com.android.", 0) == 0) return true;
    return critical_system_apps_.count(package_name) > 0;
}