// daemon/cpp/state_manager.cpp
#include "state_manager.h"
#include "adj_mapper.h"
#include "memory_butler.h"
#include "main.h" // 修正：包含 main.h 以使用全局函数
#include <android/log.h>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <unistd.h>
#include <algorithm>
#include <sys/stat.h>
#include <unordered_map>
#include <ctime>
#include <iomanip>

#define LOG_TAG "cerberusd_state_v45_audit_fix" // 版本号更新
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;
using json = nlohmann::json;
const double NETWORK_THRESHOLD_KBPS = 500.0;

static std::string status_to_string(const AppRuntimeState& app, const MasterConfig& master_config) {
    if (app.current_status == AppRuntimeState::Status::STOPPED) return "未运行";
    if (app.current_status == AppRuntimeState::Status::FROZEN) {
        switch (app.freeze_method) {
            case AppRuntimeState::FreezeMethod::CGROUP: return "已冻结 (V2)";
            case AppRuntimeState::FreezeMethod::SIG_STOP: return "已冻结 (SIG)";
            default: return "已冻结";
        }
    }
    if (app.is_foreground) return "前台运行";
    if (app.config.policy == AppPolicy::EXEMPTED || app.config.policy == AppPolicy::IMPORTANT) return "后台运行 (已豁免)";
    if (app.background_since > 0) {
        time_t now = time(nullptr);
        int timeout_sec = 0;
        if (app.config.policy == AppPolicy::STRICT) {
            timeout_sec = 15;
        } else if (app.config.policy == AppPolicy::STANDARD) {
            timeout_sec = master_config.standard_timeout_sec;
        }
        if (app.freeze_retry_count > 0) {
            timeout_sec += (5 * app.freeze_retry_count);
        }
        int remaining = timeout_sec - (now - app.background_since);
        if (remaining < 0) remaining = 0;
        return "等待冻结 (" + std::to_string(remaining) + "s)";
    }
    if (app.observation_since > 0) {
        time_t now = time(nullptr);
        int remaining = 10 - (now - app.observation_since);
        if (remaining < 0) remaining = 0;
        return "后台观察中 (" + std::to_string(remaining) + "s)";
    }
    return "后台运行";
}


DozeManager::DozeManager(std::shared_ptr<Logger> logger, std::shared_ptr<ActionExecutor> executor)
    : logger_(logger), action_executor_(executor) {
    state_change_timestamp_ = std::chrono::steady_clock::now();
}

void DozeManager::enter_state(State new_state, const MetricsRecord& record) {
    if (new_state == current_state_) return;

    current_state_ = new_state;
    state_change_timestamp_ = std::chrono::steady_clock::now();

    switch(new_state) {
        case State::AWAKE:
            break;
        case State::IDLE:
             logger_->log(LogLevel::DOZE, "Doze", "进入IDLE (息屏, 未充电)");
            break;
        case State::INACTIVE:
            logger_->log(LogLevel::DOZE, "Doze", "进入INACTIVE (检查期)");
            break;
        case State::DEEP_DOZE:
            deep_doze_start_time_ = std::chrono::steady_clock::now();
            logger_->log(LogLevel::DOZE, "Doze", "😴 进入深度Doze");
            break;
    }
}

DozeManager::DozeEvent DozeManager::process_metrics(const MetricsRecord& record) {
    auto now = std::chrono::steady_clock::now();
    auto duration_in_state = std::chrono::duration_cast<std::chrono::seconds>(now - state_change_timestamp_).count();
    State old_state = current_state_;

    if (record.is_screen_on || record.is_charging) {
        enter_state(State::AWAKE, record);
    } else {
        if (current_state_ == State::AWAKE) {
            enter_state(State::IDLE, record);
        } else if (current_state_ == State::IDLE && duration_in_state > 30) {
            if (record.is_audio_playing || record.is_location_active) {
                state_change_timestamp_ = now;
            } else {
                enter_state(State::INACTIVE, record);
            }
        } else if (current_state_ == State::INACTIVE && duration_in_state > 60) {
             if (record.is_audio_playing || record.is_location_active) {
                enter_state(State::IDLE, record);
            } else {
                enter_state(State::DEEP_DOZE, record);
            }
        }
    }

    if (old_state == State::DEEP_DOZE && current_state_ != State::DEEP_DOZE) {
        auto doze_duration = std::chrono::duration_cast<std::chrono::seconds>(now - deep_doze_start_time_);
        long minutes = doze_duration.count() / 60;
        long seconds = doze_duration.count() % 60;
        std::stringstream ss;
        ss << "🤪 退出深度Doze，持续时长 " << minutes << "分" << seconds << "秒";
        logger_->log(LogLevel::DOZE, "Doze", ss.str());
        return DozeEvent::EXITED_DEEP_DOZE;
    }

    if (old_state != State::DEEP_DOZE && current_state_ == State::DEEP_DOZE) {
        return DozeEvent::ENTERED_DEEP_DOZE;
    }

    return DozeEvent::NONE;
}

StateManager::StateManager(std::shared_ptr<DatabaseManager> db,
                           std::shared_ptr<SystemMonitor> sys,
                           std::shared_ptr<ActionExecutor> act,
                           std::shared_ptr<Logger> logger,
                           std::shared_ptr<TimeSeriesDatabase> ts_db,
                           std::shared_ptr<AdjMapper> adj_mapper,
                           std::shared_ptr<MemoryButler> mem_butler)
    : db_manager_(db), sys_monitor_(sys), action_executor_(act), logger_(logger), ts_db_(ts_db), adj_mapper_(adj_mapper), memory_butler_(mem_butler) {
    LOGI("StateManager Initializing...");

    unfrozen_timeline_.resize(3600 * 2, 0);
    master_config_ = db_manager_->get_master_config().value_or(MasterConfig{});
    doze_manager_ = std::make_unique<DozeManager>(logger_, action_executor_);

    LOGI("Loaded master config: standard_timeout=%ds, timed_unfreeze_enabled=%d, timed_unfreeze_interval=%ds",
        master_config_.standard_timeout_sec, master_config_.is_timed_unfreeze_enabled, master_config_.timed_unfreeze_interval_sec);

    critical_system_apps_ = {
        "zygote",
        "zygote64",
        "com.xiaomi.xmsf",
        "com.xiaomi.xmsfkeeper",
//        "com.xiaomi.misettings",
//        "com.xiaomi.barrage",
//        "com xiaomi.aireco",
//        "com.xiaomi.account",
//        "com.miui.notes",
//        "com.miui.calculator",
//        "com.miui.compass",
//        "com.miui.mediaeditor",
//        "com.miui.personalassistant",
//        "com.miui.vipservice",
//        "com.miui.systemAdSolution",
        "com.miui.home",
//        "com.miui.carlink",
        "com.miui.packageinstaller",
//        "com.miui.accessibility",
        "com.miui.core",
//        "com.miui.privacycomputing",
//        "com.miui.securityadd",
//        "com.miui.securityinputmethod",
//        "com.miui.system",
//        "com.miui.vpnsdkmanager",
//        "com.mfashiongallery.emag",
//        "com.huawei.hwid",
//        "cn.litiaotiao.app",
//       "com.litiaotiao.app",
//        "hello.litiaotiao.app",
//        "com.zfdang.touchhelper",
//        "com.giftedcat.adskiphelper",
//        "com.merxury.blocker",
//        "com.wpengapp.lightstart",
//        "li.songe.gkd",
//        "com.sevtinge.hyperceiler",
        "com.topjohnwu.magisk",
        "org.lsposed.manager",
        "name.monwf.customiuizer",
        "name.mikanoshi.customiuizer",
        "com.android.vending",
        "org.meowcat.xposed.mipush",
        "top.trumeet.mipush",
//        "one.yufz.hmspush",
        "app.lawnchair",
        "com.microsoft.launcher",
//        "com.teslacoilsw.launcher",
        "com.hola.launcher",
        "com.transsion.XOSLauncher",
        "com.mi.android.globallauncher",
//        "com.gau.go.launcherex",
//        "bitpit.launcher",
        "com.oppo.launcher",
        "me.weishu.kernelsu",
        "top.canyie.dreamland.manager",
        "com.coloros.packageinstaller",
        "com.oplus.packageinstaller",
        "com.iqoo.packageinstaller",
        "com.vivo.packageinstaller",
        "com.google.android.packageinstaller",
//        "com.baidu.input_huawei",
//        "com.baidu.input_oppo",
//        "com.baidu.input_vivo",
//        "com.baidu.input_yijia",
//        "com.sohu.inputmethod.sogou.xiaomi",
//        "com.sohu.inputmethod.sogou.meizu",
//        "com.sohu.inputmethod.sogou.nubia",
//        "com.sohu.inputmethod.sogou.chuizi",
//        "com.sohu.inputmethod.sogou.moto",
//        "com.sohu.inputmethod.sogou.zte",
//        "com.sohu.inputmethod.sogou.samsung",
//        "com.sohu.input_yijia",
//        "com.iflytek.inputmethod.miui",
//        "com.iflytek.inputmethod.googleplay",
//        "com.iflytek.inputmethod.smartisan",
//        "com.iflytek.inputmethod.oppo",
//        "com.iflytek.inputmethod.oem",
//        "com.iflytek.inputmethod.custom",
//        "com.iflytek.inputmethod.blackshark",
//        "com.iflytek.inputmethod.zte",
//        "com.touchtype.swiftkey",
//        "com.touchtype.swiftkey.beta",
//        "im.weshine.keyboard",
        "com.komoxo.octopusime",
//        "com.qujianpan.duoduo",
//        "com.lxlm.lhl.softkeyboard",
//        "com.jinkey.unfoldedime",
//        "com.iflytek.inputmethods.DungkarIME",
//        "com.oyun.qingcheng",
//        "com.ziipin.softkeyboard",
//        "com.kongzue.secretinput",
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
//        "com.tencent.soter.soterserver",
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
    next_scan_iterator_ = managed_apps_.begin();
    last_battery_level_info_ = std::nullopt;
    LOGI("StateManager Initialized. Ready for warmup.");
}

void StateManager::reload_adj_rules() {
    LOGI("Reloading adj_rules.json by request...");
    if (adj_mapper_) {
        adj_mapper_->load_rules();
        logger_->log(LogLevel::EVENT, "配置", "OOM策略已从文件热重载");
    }
}

void StateManager::initial_full_scan_and_warmup() {
    LOGI("Starting initial full scan and data warmup...");
    std::lock_guard<std::mutex> lock(state_mutex_);

    reconcile_process_state_full();

    int warmed_up_count = 0;
    for (auto& [key, app] : managed_apps_) {
        if (!app.pids.empty()) {
            sys_monitor_->update_app_stats(app.pids, app.mem_usage_kb, app.swap_usage_kb, app.cpu_usage_percent);
            warmed_up_count++;
        }
    }

    LOGI("Warmup complete. Populated initial stats for %d running app instances.", warmed_up_count);
    logger_->log(LogLevel::EVENT, "Daemon", "启动预热完成，已填充初始数据");
}

bool StateManager::perform_staggered_stats_scan() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    if (managed_apps_.empty()) {
        return false;
    }

    const int APPS_PER_TICK = 2;

    for (int i = 0; i < APPS_PER_TICK; ++i) {
        if (next_scan_iterator_ == managed_apps_.end()) {
            next_scan_iterator_ = managed_apps_.begin();
        }
        if (next_scan_iterator_ == managed_apps_.end()) {
            break;
        }

        auto& app = next_scan_iterator_->second;
        if (!app.pids.empty()) {
            sys_monitor_->update_app_stats(app.pids, app.mem_usage_kb, app.swap_usage_kb, app.cpu_usage_percent);
        }

        ++next_scan_iterator_;
    }

    return true;
}

bool StateManager::evaluate_and_execute_strategy() {
    bool state_has_changed = false;
    
    auto visible_app_keys = sys_monitor_->get_visible_app_keys();
    
    // 步骤1：更新前后台状态
    state_has_changed |= update_foreground_state(visible_app_keys);

    // 步骤2：对后台应用进行策略审计，确保它们都在监管中
    audit_background_apps();

    // 步骤3：对应用内部的进程结构进行审计（例如“斩首行动”）
    if (state_has_changed) {
        auto process_tree = sys_monitor_->get_full_process_tree();
        audit_app_structures(process_tree);
    }

    return state_has_changed;
}

bool StateManager::handle_top_app_change_fast() {
    auto top_pids = sys_monitor_->read_top_app_pids();
    return update_foreground_state_from_pids(top_pids);
}

void StateManager::process_new_metrics(const MetricsRecord& record) {
    update_memory_health(record);
    std::lock_guard<std::mutex> lock(state_mutex_);

    auto doze_event = doze_manager_->process_metrics(record);

    if (doze_event == DozeManager::DozeEvent::ENTERED_DEEP_DOZE) {
        doze_start_process_info_.clear();
        for (const auto& [key, app] : managed_apps_) {
            for (int pid : app.pids) {
                doze_start_process_info_[pid] = {
                    .start_jiffies = sys_monitor_->get_total_cpu_jiffies_for_pids({pid}),
                    .process_name = sys_monitor_->get_app_name_from_pid(pid),
                    .package_name = app.package_name,
                    .user_id = app.user_id
                };
            }
        }
    } else if (doze_event == DozeManager::DozeEvent::EXITED_DEEP_DOZE) {
        generate_doze_exit_report();
        doze_start_process_info_.clear();
    }

    if (last_metrics_record_) {
        handle_charging_state_change(*last_metrics_record_, record);
        analyze_battery_change(*last_metrics_record_, record);
    }

    last_metrics_record_ = record;
}

void StateManager::update_memory_health(const MetricsRecord& record) {
    if (record.mem_total_kb <= 0) return;

    double available_mem_percent = 100.0 * static_cast<double>(record.mem_available_kb) / record.mem_total_kb;
    MemoryHealth old_health = memory_health_;

    if (available_mem_percent < 10.0) {
        memory_health_ = MemoryHealth::CRITICAL;
    } else if (available_mem_percent < 20.0) {
        memory_health_ = MemoryHealth::CONCERN;
    } else {
        memory_health_ = MemoryHealth::HEALTHY;
    }

    if (old_health != memory_health_) {
        std::string health_str = (memory_health_ == MemoryHealth::CRITICAL) ? "CRITICAL" : 
                                 (memory_health_ == MemoryHealth::CONCERN) ? "CONCERN" : "HEALTHY";
        LOGI("Memory health changed from %d to %s (available: %.1f%%).", (int)old_health, health_str.c_str(), available_mem_percent);
        if (memory_health_ == MemoryHealth::CRITICAL) {
             logger_->log(LogLevel::WARN, "内存", "系统可用内存严重不足，已进入CRITICAL状态");
        }
    }
}

void StateManager::run_memory_butler_tasks() {
    if (!memory_butler_ || !memory_butler_->is_supported() || memory_health_ == MemoryHealth::HEALTHY) {
        return;
    }

    std::vector<std::pair<AppInstanceKey, size_t>> candidates;
    std::map<AppInstanceKey, std::vector<int>> app_pids;

    {
        std::lock_guard<std::mutex> lock(state_mutex_);
        for (const auto& [key, app] : managed_apps_) {
            // 只考虑后台运行或已冻结的应用
            if (!app.is_foreground && !app.pids.empty()) {
                candidates.emplace_back(key, app.mem_usage_kb);
                app_pids[key] = app.pids;
            }
        }
    }
    
    std::sort(candidates.begin(), candidates.end(), [](const auto& a, const auto& b){
        return a.second > b.second;
    });

    MemoryButler::CompressionLevel level = (memory_health_ == MemoryHealth::CRITICAL) ?
                                           MemoryButler::CompressionLevel::AGGRESSIVE :
                                           MemoryButler::CompressionLevel::LIGHT;
    
    int processed_count = 0;
    const int max_to_process = (level == MemoryButler::CompressionLevel::AGGRESSIVE) ? 5 : 2;

    for(const auto& cand : candidates) {
        if (processed_count >= max_to_process) break;
        
        auto it = app_pids.find(cand.first);
        if (it != app_pids.end()) {
            for(int pid : it->second) {
                memory_butler_->compress_memory(pid, level);
            }
        }
        processed_count++;
    }
}

void StateManager::generate_doze_exit_report() {
    struct ProcessActivity {
        std::string process_name;
        double cpu_seconds;
    };

    struct AppActivitySummary {
        std::string app_name;
        std::string package_name;
        int user_id;
        double total_cpu_seconds = 0.0;
        std::vector<ProcessActivity> processes;
    };

    std::map<AppInstanceKey, AppActivitySummary> grouped_activities;
    const long TCK = sysconf(_SC_CLK_TCK);
    if (TCK <= 0) return;

    for (const auto& [pid, start_record] : doze_start_process_info_) {
        std::string base_package_name = start_record.package_name;
        size_t colon_pos = base_package_name.find(':');
        if (colon_pos != std::string::npos) {
            base_package_name = base_package_name.substr(0, colon_pos);
        }

        long long end_jiffies = sys_monitor_->get_total_cpu_jiffies_for_pids({pid});
        if (end_jiffies > start_record.start_jiffies) {
            double cpu_seconds = static_cast<double>(end_jiffies - start_record.start_jiffies) / TCK;
            if (cpu_seconds > 0.01) {
                AppInstanceKey key = {base_package_name, start_record.user_id};
                if (grouped_activities.find(key) == grouped_activities.end()) {
                    auto it = managed_apps_.find(key);
                    grouped_activities[key].app_name = (it != managed_apps_.end() && !it->second.app_name.empty()) ? it->second.app_name : key.first;
                    grouped_activities[key].package_name = key.first;
                    grouped_activities[key].user_id = key.second;
                }
                grouped_activities[key].processes.push_back({start_record.process_name, cpu_seconds});
                grouped_activities[key].total_cpu_seconds += cpu_seconds;
            }
        }
    }

    if (grouped_activities.empty()) {
        logger_->log(LogLevel::BATCH_PARENT, "报告", "Doze期间无明显应用活动。");
        return;
    }
    
    std::vector<LogEntry> batch_log_entries;
    long long now_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();

    batch_log_entries.push_back({now_ms, LogLevel::BATCH_PARENT, "报告", "Doze期间应用的CPU活跃时间：", "", -1});

    std::vector<AppActivitySummary> sorted_apps;
    for (auto const& [key, val] : grouped_activities) {
        sorted_apps.push_back(val);
    }
    std::sort(sorted_apps.begin(), sorted_apps.end(),
              [](const auto& a, const auto& b) { return a.total_cpu_seconds > b.total_cpu_seconds; });

    const int REPORT_LIMIT = 7;
    int count = 0;
    for (const auto& summary : sorted_apps) {
        if (++count > REPORT_LIMIT) break;

        std::stringstream report_ss;
        report_ss << summary.app_name << " 总计: "
                  << std::fixed << std::setprecision(3) << summary.total_cpu_seconds << "s";
        
        if (!summary.processes.empty()) {
            report_ss << "\n包括:";
        }
        
        for (const auto& proc : summary.processes) {
            report_ss << "\n- " << proc.process_name << ": " << std::fixed << std::setprecision(3) << proc.cpu_seconds << "s";
        }
        batch_log_entries.push_back({now_ms, LogLevel::REPORT, "报告", report_ss.str(), summary.package_name, summary.user_id});
    }

    logger_->log_batch(batch_log_entries);
}


void StateManager::handle_charging_state_change(const MetricsRecord& old_record, const MetricsRecord& new_record) {
    if (old_record.is_charging != new_record.is_charging) {
        if (new_record.is_charging) {
            logger_->log(LogLevel::BATTERY, "充电", "⚡️ 开始充电 (当前电量: " + std::to_string(new_record.battery_level) + "%)");
        } else {
            logger_->log(LogLevel::BATTERY, "充电", "🔌 停止充电 (当前电量: " + std::to_string(new_record.battery_level) + "%)");
        }
    }
}

void StateManager::analyze_battery_change(const MetricsRecord& old_record, const MetricsRecord& new_record) {
    if (new_record.is_charging || new_record.battery_level < 0) {
        last_battery_level_info_ = std::nullopt;
        return;
    }

    if (!last_battery_level_info_) {
        last_battery_level_info_ = {new_record.battery_level, new_record.timestamp_ms};
        return;
    }

    if (new_record.battery_level < last_battery_level_info_->first) {
        long long time_delta_ms = new_record.timestamp_ms - last_battery_level_info_->second;
        int level_delta = last_battery_level_info_->first - new_record.battery_level;

        if (time_delta_ms <= 0 || level_delta <= 0) return;

        long long time_per_percent_ms = time_delta_ms / level_delta;

        std::stringstream ss;
        ss << "[当前: " << new_record.battery_level << "%] [消耗: " << level_delta << "%/"
           << (time_delta_ms / 1000 / 60) << "m " << (time_delta_ms / 1000) % 60 << "s] "
           << "[功率: " << std::fixed << std::setprecision(2) << new_record.battery_power_watt << "W] "
           << "[温度: " << std::fixed << std::setprecision(1) << new_record.battery_temp_celsius << "°C]";

        LogLevel level = (time_per_percent_ms < 300000) ? LogLevel::WARN : LogLevel::BATTERY;
        std::string category = (time_per_percent_ms < 300000) ? "电量警告" : "电量";
        if (level == LogLevel::WARN) ss << " (耗电较快)";
        else ss << " (状态更新)";

        logger_->log(level, category, ss.str());

        last_battery_level_info_ = {new_record.battery_level, new_record.timestamp_ms};
    }
}

bool StateManager::unfreeze_and_observe_nolock(AppRuntimeState& app, const std::string& reason, WakeupPolicy policy) {
    cancel_timed_unfreeze(app);

    if (app.current_status == AppRuntimeState::Status::FROZEN) {
        std::string msg = "因 " + reason + " 而解冻";
        logger_->log(LogLevel::ACTION_UNFREEZE, "解冻", msg, app.package_name, app.user_id);
        action_executor_->unfreeze(app.pids);
        action_executor_->cleanup_cgroup({app.package_name, app.user_id});
        
        app.current_status = AppRuntimeState::Status::RUNNING;
        app.freeze_method = AppRuntimeState::FreezeMethod::NONE;
        app.is_oom_protected = false;
        
        time_t now = time(nullptr);
        int observation_seconds = 0;
        switch(policy) {
            case WakeupPolicy::SHORT_OBSERVATION:
                observation_seconds = 3;
                break;
            case WakeupPolicy::STANDARD_OBSERVATION:
                observation_seconds = 10;
                break;
            case WakeupPolicy::LONG_OBSERVATION:
                observation_seconds = 20;
                break;
            case WakeupPolicy::UNFREEZE_UNTIL_BACKGROUND:
                app.observation_since = 0;
                app.background_since = 0;
                LOGI("Smart Unfreeze: %s un-frozen by policy until next background event.", app.package_name.c_str());
                return true;
            default:
                observation_seconds = 10;
                break;
        }

        if (observation_seconds > 0) {
            app.observation_since = now - (10 - observation_seconds);
            LOGI("Smart Unfreeze: %s gets %ds observation for %s.", app.package_name.c_str(), observation_seconds, reason.c_str());
        }

        app.background_since = 0;
        app.freeze_retry_count = 0;

        return true;
    } else {
        LOGD("UNFREEZE [%s]: Request for %s ignored. Reason: App not frozen (current state: %d).",
            reason.c_str(), app.package_name.c_str(), static_cast<int>(app.current_status));
        return false;
    }
}

WakeupPolicy StateManager::decide_wakeup_policy_for_probe(WakeupPolicy event_type) {
    switch (event_type) {
        case WakeupPolicy::FROM_FCM:
            return WakeupPolicy::LONG_OBSERVATION;
        case WakeupPolicy::FROM_NOTIFICATION:
            return WakeupPolicy::SHORT_OBSERVATION;
        case WakeupPolicy::FROM_PROBE_START:
            return WakeupPolicy::UNFREEZE_UNTIL_BACKGROUND;
        default:
            return WakeupPolicy::STANDARD_OBSERVATION;
    }
}

WakeupPolicy StateManager::decide_wakeup_policy_for_kernel(const ReKernelBinderEvent& event) {
    if (event.rpc_name.find("android.app.INotificationManager") != std::string::npos) {
        const std::unordered_set<int> notification_transaction_codes = { 1, 2, 7 };
        if (notification_transaction_codes.count(event.code)) {
            LOGI("Policy: Whitelisted Notification Binder call (code %d) to frozen app from UID %d. Waking up.", event.code, event.from_uid);
            return WakeupPolicy::SHORT_OBSERVATION;
        }
    }

    ignored_rpc_stats_[event.rpc_name]++;
    LOGD("Policy: Ignoring non-whitelisted Binder event from PID %d to %d (rpc: %s, code: %d).",
         event.from_pid, event.target_pid, event.rpc_name.c_str(), event.code);
         
    return WakeupPolicy::IGNORE;
}


WakeupPolicy StateManager::decide_wakeup_policy_for_kernel(const ReKernelSignalEvent& event) {
    if (event.signal == 9 /*SIGKILL*/) {
        LOGI("Policy: High-priority SIGKILL for PID %d. Applying standard observation.", event.dest_pid);
        return WakeupPolicy::STANDARD_OBSERVATION;
    }
    if (event.signal == 15 /*SIGTERM*/) {
        LOGI("Policy: Termination signal for PID %d. Applying short observation.", event.dest_pid);
        return WakeupPolicy::SHORT_OBSERVATION;
    }

    if (event.signal == 6 /*SIGABRT*/) {
        LOGI("Policy: Abort/Quit signal (%d) for PID %d. Applying short observation.", event.signal, event.dest_pid);
        return WakeupPolicy::SHORT_OBSERVATION;
    }
    return WakeupPolicy::IGNORE;
}


void StateManager::on_signal_from_rekernel(const ReKernelSignalEvent& event) {
    bool state_changed = false;
    {
        std::lock_guard<std::mutex> lock(state_mutex_);
        auto it = pid_to_app_map_.find(event.dest_pid);
        if (it != pid_to_app_map_.end()) {
            AppRuntimeState* app = it->second;
            if (app && app->current_status == AppRuntimeState::Status::FROZEN) {
                const time_t now = time(nullptr);
                
                WakeupPolicy policy = decide_wakeup_policy_for_kernel(event);
                if (policy == WakeupPolicy::IGNORE) return;

                if (now - app->last_wakeup_timestamp > 60) {
                    app->wakeup_count_in_window = 1;
                } else {
                    app->wakeup_count_in_window++;
                }
                app->last_wakeup_timestamp = now;

                if (app->wakeup_count_in_window > 5) {
                    LOGW("Throttling: Kernel SIGNAL for %s ignored. Triggered %d times in last 60s.", app->package_name.c_str(), app->wakeup_count_in_window);
                    logger_->log(LogLevel::WARN, "节流阀", "内核信号唤醒过于频繁，已临时忽略", app->package_name, app->user_id);
                    return;
                }

                std::stringstream reason_ss;
                reason_ss << "内核信号 " << event.signal << " (from PID " << event.killer_pid << ")";
                logger_->log(LogLevel::WARN, "内核事件", reason_ss.str(), app->package_name, app->user_id);

                if (unfreeze_and_observe_nolock(*app, "Kernel Signal", policy)) {
                    state_changed = true;
                }
            }
        }
    }

    if (state_changed) {
        broadcast_dashboard_update();
        notify_probe_of_config_change();
    }
}

void StateManager::on_binder_from_rekernel(const ReKernelBinderEvent& event) {
    bool state_changed = false;
    {
        std::lock_guard<std::mutex> lock(state_mutex_);
        auto it = pid_to_app_map_.find(event.target_pid);
        if (it != pid_to_app_map_.end()) {
            AppRuntimeState* app = it->second;
            if (app && app->current_status == AppRuntimeState::Status::FROZEN) {
                const time_t now = time(nullptr);

                if (now - app->last_successful_wakeup_timestamp <= 2) {
                    LOGD("Debounce: Ignoring kernel BINDER for %s, likely part of recent wakeup burst.", app->package_name.c_str());
                    return;
                }

                WakeupPolicy policy = decide_wakeup_policy_for_kernel(event);
                if (policy == WakeupPolicy::IGNORE) return;

                if (now - app->last_wakeup_timestamp > 60) {
                    app->wakeup_count_in_window = 1;
                } else {
                    app->wakeup_count_in_window++;
                }
                app->last_wakeup_timestamp = now;

                if (app->wakeup_count_in_window > 10) {
                    LOGW("Throttling: Whitelisted Kernel BINDER for %s ignored. Triggered %d times in last 60s.", app->package_name.c_str(), app->wakeup_count_in_window);
                    logger_->log(LogLevel::WARN, "节流阀", "白名单Binder唤醒过于频繁，已临时忽略", app->package_name, app->user_id);
                    return;
                }
                
                std::stringstream reason_ss;
                reason_ss << "白名单内核Binder (RPC:" << (event.rpc_name.empty() ? "N/A" : event.rpc_name) << ", Code:" << event.code << ")";
                logger_->log(LogLevel::INFO, "内核事件", reason_ss.str(), app->package_name, app->user_id);

                if (unfreeze_and_observe_nolock(*app, "Whitelisted Kernel Binder", policy)) {
                    app->last_successful_wakeup_timestamp = now;
                    state_changed = true;
                }
            }
        }
    }
    if (state_changed) {
        broadcast_dashboard_update();
        notify_probe_of_config_change();
    }
}

void StateManager::on_wakeup_request_from_probe(const json& payload) {
    bool state_changed = false;
    try {
        int uid = payload.value("uid", -1);
        if (uid < 0) return;
        
        auto event_type_int = payload.value("type_int", 3);
        WakeupPolicy event_type = WakeupPolicy::STANDARD_OBSERVATION;
        if (event_type_int == 0) event_type = WakeupPolicy::FROM_NOTIFICATION;
        else if (event_type_int == 1) event_type = WakeupPolicy::FROM_FCM;
        
        LOGD("Received wakeup request from probe for UID: %d, Type: %d", uid, event_type_int);

        std::lock_guard<std::mutex> lock(state_mutex_);
        AppRuntimeState* target_app = nullptr;
        for (auto& [key, app] : managed_apps_) {
            if (app.uid == uid) {
                target_app = &app;
                break;
            }
        }
        
        if (target_app) {
            const time_t now = time(nullptr);
            if (now - target_app->last_wakeup_timestamp > 60) {
                target_app->wakeup_count_in_window = 1;
            } else {
                target_app->wakeup_count_in_window++;
            }
            target_app->last_wakeup_timestamp = now;

            if (target_app->wakeup_count_in_window > 10) {
                LOGW("Throttling: Probe wakeup for %s ignored. Triggered %d times in last 60s.", target_app->package_name.c_str(), target_app->wakeup_count_in_window);
                logger_->log(LogLevel::WARN, "节流阀", "Probe唤醒过于频繁，已临时忽略", target_app->package_name, target_app->user_id);
                return;
            }

            WakeupPolicy policy = decide_wakeup_policy_for_probe(event_type);
            if (unfreeze_and_observe_nolock(*target_app, "Probe Request", policy)) {
                state_changed = true;
            }
        } else {
            LOGW("Wakeup request for unknown UID: %d", uid);
        }
    } catch (const json::exception& e) {
        LOGE("Error processing wakeup request from probe: %s", e.what());
    }

    if (state_changed) {
        broadcast_dashboard_update();
        notify_probe_of_config_change();
    }
}


void StateManager::audit_app_structures(const std::map<int, ProcessInfo>& process_tree) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    for(auto& [key, app] : managed_apps_) {
        app.has_rogue_structure = false;
        app.rogue_puppet_pid = -1;
        app.rogue_master_pid = -1;

        if (app.is_foreground || app.pids.size() < 2) continue;

        for (int pid : app.pids) {
            auto it_pid = process_tree.find(pid);
            if (it_pid == process_tree.end()) continue;

            const auto& child_info = it_pid->second;
            if (child_info.oom_score_adj <= 0) {
                auto it_ppid = process_tree.find(child_info.ppid);
                if (it_ppid != process_tree.end() && it_ppid->second.pkg_name == app.package_name) {
                    const auto& parent_info = it_ppid->second;
                    if (parent_info.oom_score_adj > 200) {
                        if (!app.has_logged_rogue_warning) {
                            LOGW("AUDIT: Rogue structure detected in %s! Puppet: pid=%d (adj=%d), Master: pid=%d (adj=%d)",
                                app.package_name.c_str(), child_info.pid, child_info.oom_score_adj,
                                parent_info.pid, parent_info.oom_score_adj);
                            logger_->log(LogLevel::WARN, "审计", "检测到流氓进程结构", app.package_name, app.user_id);
                            app.has_logged_rogue_warning = true;
                        }
                        app.has_rogue_structure = true;
                        app.rogue_puppet_pid = child_info.pid;
                        app.rogue_master_pid = parent_info.pid;
                        break;
                    }
                }
            }
        }
    }
}

bool StateManager::update_foreground_state(const std::set<AppInstanceKey>& visible_app_keys) {
    bool state_has_changed = false;
    bool probe_config_needs_update = false;

    {
        std::lock_guard<std::mutex> lock(state_mutex_);
        if (visible_app_keys == last_known_visible_app_keys_) {
            return false;
        }

        last_known_visible_app_keys_ = visible_app_keys;

        std::string current_ime_pkg = sys_monitor_->get_current_ime_package();
        if (!current_ime_pkg.empty()) {
            last_known_visible_app_keys_.insert({current_ime_pkg, 0});
        }

        std::set<AppInstanceKey> prev_foreground_keys;
        for (const auto& [key, app] : managed_apps_) {
            if (app.is_foreground) prev_foreground_keys.insert(key);
        }

        const auto& final_foreground_keys = last_known_visible_app_keys_;

        time_t now = time(nullptr);
        for (auto& [key, app] : managed_apps_) {
            bool is_now_foreground = final_foreground_keys.count(key);
            if (app.is_foreground != is_now_foreground) {
                state_has_changed = true;
                app.is_foreground = is_now_foreground;

                if (is_now_foreground) {
                    app.has_logged_rogue_warning = false;
                    if (prev_foreground_keys.find(key) == prev_foreground_keys.end()) {
                         logger_->log(LogLevel::ACTION_OPEN, "打开", "已打开 (权威)", app.package_name, app.user_id);
                         app.last_foreground_timestamp_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
                    }
                    if (unfreeze_and_observe_nolock(app, "切换至前台(权威)", WakeupPolicy::UNFREEZE_UNTIL_BACKGROUND)) {
                        probe_config_needs_update = true;
                    }
                    app.observation_since = 0;
                    app.background_since = 0;
                    app.freeze_retry_count = 0;
                } else {
                     if (prev_foreground_keys.count(key) > 0) {
                        long long now_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
                        long long current_runtime_ms = (app.last_foreground_timestamp_ms > 0) ? (now_ms - app.last_foreground_timestamp_ms) : 0;
                        app.total_runtime_ms += current_runtime_ms;
                        long total_seconds = app.total_runtime_ms / 1000;
                        std::stringstream ss_msg;
                        ss_msg << "已关闭 [本次: " << (current_runtime_ms / 1000) << "s] [累计: "
                               << (total_seconds / 3600) << "h" << ((total_seconds % 3600) / 60) << "m" << (total_seconds % 60) << "s]";
                        logger_->log(LogLevel::ACTION_CLOSE, "关闭", ss_msg.str(), app.package_name, app.user_id);
                    }
                    if (app.current_status == AppRuntimeState::Status::RUNNING && (app.config.policy == AppPolicy::STANDARD || app.config.policy == AppPolicy::STRICT) && !app.pids.empty()) {
                        app.observation_since = now;
                    }
                }
            }
        }
    }

    if (probe_config_needs_update) {
        notify_probe_of_config_change();
    }
    return state_has_changed;
}

bool StateManager::update_foreground_state_from_pids(const std::set<int>& top_pids) {
    bool state_has_changed = false;
    bool probe_config_needs_update = false;

    {
        std::lock_guard<std::mutex> lock(state_mutex_);

        std::map<int, AppInstanceKey> pid_to_key_map;
        std::set<AppInstanceKey> top_app_keys;
        for (int pid : top_pids) {
            int uid = -1, user_id = -1;
            std::string pkg_name = get_package_name_from_pid(pid, uid, user_id);
            if (!pkg_name.empty() && user_id != -1) {
                AppInstanceKey key = {pkg_name, user_id};
                top_app_keys.insert(key);
                pid_to_key_map[pid] = key;
            }
        }

        std::string current_ime_pkg = sys_monitor_->get_current_ime_package();
        if (!current_ime_pkg.empty()) {
            top_app_keys.insert({current_ime_pkg, 0});
        }

        for (const auto& key : top_app_keys) {
            if (managed_apps_.find(key) == managed_apps_.end()) {
                LOGI("Discovered new top app via fast path: %s (user %d). Creating state...", key.first.c_str(), key.second);
                AppRuntimeState* new_app = get_or_create_app_state(key.first, key.second);
                if (new_app) {
                    for (const auto& [pid, mapped_key] : pid_to_key_map) {
                        if (mapped_key == key) {
                            int uid = -1, user_id_ignored = -1;
                            get_package_name_from_pid(pid, uid, user_id_ignored);
                            add_pid_to_app(pid, key.first, key.second, uid);
                        }
                    }
                    LOGI("State created and PIDs populated for new app %s.", key.first.c_str());
                    state_has_changed = true;
                }
            }
        }

        std::set<AppInstanceKey> prev_foreground_keys;
        for (const auto& [key, app] : managed_apps_) {
            if (app.is_foreground) prev_foreground_keys.insert(key);
        }

        if (top_app_keys == prev_foreground_keys && !state_has_changed) {
            return false;
        }

        const auto& final_foreground_keys = top_app_keys;
        
        time_t now = time(nullptr);
        for (auto& [key, app] : managed_apps_) {
            bool is_now_foreground = final_foreground_keys.count(key);
            if (app.is_foreground != is_now_foreground) {
                state_has_changed = true;
                app.is_foreground = is_now_foreground;

                if (is_now_foreground) {
                    app.has_logged_rogue_warning = false;
                    if (prev_foreground_keys.find(key) == prev_foreground_keys.end()) {
                         logger_->log(LogLevel::ACTION_OPEN, "打开", "已打开 (快速)", app.package_name, app.user_id);
                         app.last_foreground_timestamp_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
                    }
                    if (unfreeze_and_observe_nolock(app, "切换至前台(快速)", WakeupPolicy::UNFREEZE_UNTIL_BACKGROUND)) {
                        probe_config_needs_update = true;
                    }
                    app.observation_since = 0;
                    app.background_since = 0;
                    app.freeze_retry_count = 0;
                } else {
                     if (prev_foreground_keys.count(key) > 0) {
                        long long now_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
                        long long current_runtime_ms = (app.last_foreground_timestamp_ms > 0) ? (now_ms - app.last_foreground_timestamp_ms) : 0;
                        app.total_runtime_ms += current_runtime_ms;
                        long total_seconds = app.total_runtime_ms / 1000;
                        std::stringstream ss_msg;
                        ss_msg << "已关闭 [本次: " << (current_runtime_ms / 1000) << "s] [累计: "
                               << (total_seconds / 3600) << "h" << ((total_seconds % 3600) / 60) << "m" << (total_seconds % 60) << "s]";
                        logger_->log(LogLevel::ACTION_CLOSE, "关闭", ss_msg.str(), app.package_name, app.user_id);
                    }
                    if (app.current_status == AppRuntimeState::Status::RUNNING && (app.config.policy == AppPolicy::STANDARD || app.config.policy == AppPolicy::STRICT) && !app.pids.empty()) {
                        app.observation_since = now;
                    }
                }
            }
        }
    }

    if (probe_config_needs_update) {
        notify_probe_of_config_change();
    }
    return state_has_changed;
}

void StateManager::on_app_foreground_event(const json& payload) {
    try {
        std::string package_name = payload.value("package_name", "");
        int user_id = payload.value("user_id", 0);
        if (package_name.empty()) return;

        LOGD("EVENT: Received foreground event for %s (user %d), issuing refresh ticket.", package_name.c_str(), user_id);
        g_top_app_refresh_tickets = 1;

    } catch (const json::exception& e) {
        LOGE("Error processing foreground event: %s", e.what());
    }
}

void StateManager::on_app_background_event(const json& payload) {
    try {
        std::string package_name = payload.value("package_name", "");
        int user_id = payload.value("user_id", 0);
        if (package_name.empty()) return;

        LOGD("EVENT: Received background event for %s (user %d), issuing refresh ticket.", package_name.c_str(), user_id);

        g_top_app_refresh_tickets = 1;

    } catch (const json::exception& e) {
        LOGE("Error processing background event: %s", e.what());
    }
}


void StateManager::on_proactive_unfreeze_request(const json& payload) {
    try {
        std::string package_name = payload.value("package_name", "");
        int user_id = payload.value("user_id", 0);
        if (package_name.empty()) return;

        LOGD("PROACTIVE: Received unfreeze request for %s (user %d)", package_name.c_str(), user_id);

        std::lock_guard<std::mutex> lock(state_mutex_);
        AppInstanceKey key = {package_name, user_id};
        auto it = managed_apps_.find(key);
        if (it != managed_apps_.end()) {
            if (it->second.current_status == AppRuntimeState::Status::FROZEN) {
                 if(unfreeze_and_observe_nolock(it->second, "PROACTIVE_START", WakeupPolicy::FROM_PROBE_START)) {
                    broadcast_dashboard_update();
                    notify_probe_of_config_change();
                 }
            }
        }
    } catch (const json::exception& e) {
        LOGE("Error processing proactive unfreeze request: %s", e.what());
    }
}

void StateManager::on_wakeup_request(const json& payload) {
    bool state_changed = false;
    try {
        std::string package_name = payload.value("package_name", "");
        int user_id = payload.value("user_id", 0);
        if (package_name.empty()) return;

        LOGD("Received wakeup request for %s (user %d)", package_name.c_str(), user_id);

        std::lock_guard<std::mutex> lock(state_mutex_);
        AppInstanceKey key = {package_name, user_id};
        auto it = managed_apps_.find(key);
        if (it != managed_apps_.end()) {
            state_changed = unfreeze_and_observe_nolock(it->second, "WAKEUP_REQUEST (Legacy)", WakeupPolicy::STANDARD_OBSERVATION);
        } else {
            LOGW("Wakeup request for unknown app: %s", package_name.c_str());
        }
    } catch (const json::exception& e) {
        LOGE("Error processing wakeup request: %s", e.what());
    }

    if (state_changed) {
        broadcast_dashboard_update();
        notify_probe_of_config_change();
    }
}

void StateManager::on_temp_unfreeze_request_by_pkg(const json& payload) {
    bool state_changed = false;
    try {
        std::string package_name = payload.value("package_name", "");
        if (package_name.empty()) return;

        LOGD("Received temp unfreeze request by package: %s", package_name.c_str());

        std::lock_guard<std::mutex> lock(state_mutex_);
        bool app_found = false;
        for (auto& [key, app] : managed_apps_) {
            if (key.first == package_name) {
                app_found = true;
                if (unfreeze_and_observe_nolock(app, "FCM", WakeupPolicy::FROM_FCM)) {
                    state_changed = true;
                }
            }
        }
        if (!app_found) {
            LOGW("Temp unfreeze request for unknown package: %s", package_name.c_str());
        }
    } catch (const json::exception& e) {
        LOGE("Error processing temp unfreeze by pkg: %s", e.what());
    }

    if (state_changed) {
        broadcast_dashboard_update();
        notify_probe_of_config_change();
    }
}

void StateManager::on_temp_unfreeze_request_by_uid(const json& payload) {
    bool state_changed = false;
    try {
        int uid = payload.value("uid", -1);
        if (uid < 0) return;

        LOGD("Received temp unfreeze request by UID: %d", uid);

        std::lock_guard<std::mutex> lock(state_mutex_);
        bool app_found = false;
        for (auto& [key, app] : managed_apps_) {
            if (app.uid == uid) {
                app_found = true;
                if (unfreeze_and_observe_nolock(app, "AUDIO_FOCUS", WakeupPolicy::STANDARD_OBSERVATION)) {
                    state_changed = true;
                }
                break;
            }
        }
        if(!app_found) {
            LOGW("Temp unfreeze request for unknown UID: %d", uid);
        }
    } catch (const json::exception& e) {
        LOGE("Error processing temp unfreeze by uid: %s", e.what());
    }

    if (state_changed) {
        broadcast_dashboard_update();
        notify_probe_of_config_change();
    }
}

void StateManager::on_temp_unfreeze_request_by_pid(const json& payload) {
    bool state_changed = false;
    try {
        int pid = payload.value("pid", -1);
        if (pid < 0) return;

        LOGD("Received temp unfreeze request by PID: %d", pid);

        std::lock_guard<std::mutex> lock(state_mutex_);
        auto it = pid_to_app_map_.find(pid);
        if (it != pid_to_app_map_.end()) {
            if (unfreeze_and_observe_nolock(*(it->second), "SIGKILL_PROTECT", WakeupPolicy::STANDARD_OBSERVATION)) {
                state_changed = true;
            }
        } else {
            LOGW("Temp unfreeze request for unknown PID: %d", pid);
        }
    } catch (const json::exception& e) {
        LOGE("Error processing temp unfreeze by pid: %s", e.what());
    }

    if (state_changed) {
        broadcast_dashboard_update();
        notify_probe_of_config_change();
    }
}

void StateManager::update_master_config(const MasterConfig& config) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    master_config_ = config;
    db_manager_->set_master_config(config);
    LOGI("Master config updated: standard_timeout=%ds, timed_unfreeze_enabled=%d, timed_unfreeze_interval=%ds",
        master_config_.standard_timeout_sec, master_config_.is_timed_unfreeze_enabled, master_config_.timed_unfreeze_interval_sec);
    logger_->log(LogLevel::EVENT, "配置", "核心配置已更新");
}

bool StateManager::tick_state_machine() {
    bool changed1 = tick_state_machine_timers();
    bool changed2 = check_timed_unfreeze();
    return changed1 || changed2;
}

// 这是您原始代码中已经存在的函数，我将它放在这里以保持文件结构的完整性
void StateManager::audit_background_apps() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    time_t now = time(nullptr);

    for (auto& [key, app] : managed_apps_) {
        // 检查是否是需要被监管的后台应用
        bool is_candidate = !app.is_foreground &&
                            app.current_status == AppRuntimeState::Status::RUNNING &&
                            (app.config.policy == AppPolicy::STANDARD || app.config.policy == AppPolicy::STRICT) &&
                            !app.pids.empty();

        if (is_candidate) {
            // 如果它是一个合格的候选者，但没有任何计时器在运行，说明它“逃逸”了
            if (app.observation_since == 0 && app.background_since == 0) {
                LOGW("AUDIT: Found background app %s (user %d) without active timer. Placing under observation.",
                     app.package_name.c_str(), app.user_id);
                logger_->log(LogLevel::INFO, "审计", "发现逃逸的后台应用，已置于观察期", app.package_name, app.user_id);
                app.observation_since = now;
            }
        }
    }
}


bool StateManager::is_app_playing_audio(const AppRuntimeState& app) {
    return sys_monitor_->is_uid_playing_audio(app.uid);
}

void StateManager::validate_pids_nolock(AppRuntimeState& app) {
    if (app.pids.empty()) return;

    auto it = app.pids.begin();
    while (it != app.pids.end()) {
        fs::path proc_path("/proc/" + std::to_string(*it));
        if (!fs::exists(proc_path)) {
            LOGI("Sync: PID %d for %s no longer exists. Removing from state.", *it, app.package_name.c_str());
            pid_to_app_map_.erase(*it);
            it = app.pids.erase(it);
        } else {
            ++it;
        }
    }
}

bool StateManager::tick_state_machine_timers() {
    bool changed = false;
    bool probe_config_needs_update = false;
    const int MAX_FREEZE_RETRIES = 3;
    const int RETRY_DELAY_BASE_SEC = 5;

    {
        std::lock_guard<std::mutex> lock(state_mutex_);
        time_t now = time(nullptr);    

        for (auto& [key, app] : managed_apps_) {
            if (!app.is_foreground && !app.pids.empty()) {
                validate_pids_nolock(app);
            }

            if (app.is_foreground || app.config.policy == AppPolicy::EXEMPTED || app.config.policy == AppPolicy::IMPORTANT) {
                if (app.observation_since > 0 || app.background_since > 0) {
                    app.observation_since = 0;
                    app.background_since = 0;
                    app.freeze_retry_count = 0;
                    changed = true;
                }
                continue;
            }

            if (app.observation_since > 0 && now - app.observation_since >= 10) {
                app.observation_since = 0;

                std::vector<std::string> active_reasons;
                if (is_app_playing_audio(app)) active_reasons.push_back("音频");
                if (sys_monitor_->is_uid_using_location(app.uid)) active_reasons.push_back("定位");
                if (sys_monitor_->get_cached_network_speed(app.uid).download_kbps > NETWORK_THRESHOLD_KBPS) active_reasons.push_back("网络");

                if (!active_reasons.empty()) {
                    std::string reason_str;
                    for (size_t i = 0; i < active_reasons.size(); ++i) {
                        reason_str += active_reasons[i] + (i < active_reasons.size() - 1 ? " / " : "");
                    }
                    std::string log_msg = "因 " + reason_str + " 活跃而推迟冻结";
                    logger_->log(LogLevel::ACTION_DELAY, "延迟", log_msg, app.package_name, app.user_id);
                    app.observation_since = now;
                    changed = true;
                    continue;
                }

                app.background_since = now;
                app.freeze_retry_count = 0;
                changed = true;
            }

            if (app.background_since > 0) {
                int timeout_sec = 0;
                if(app.config.policy == AppPolicy::STRICT) timeout_sec = 15;
                else if(app.config.policy == AppPolicy::STANDARD) timeout_sec = master_config_.standard_timeout_sec;
                if (app.freeze_retry_count > 0) timeout_sec += (RETRY_DELAY_BASE_SEC * app.freeze_retry_count);

                if (timeout_sec > 0 && (now - app.background_since >= timeout_sec)) {
                
                    size_t total_pids = app.pids.size();
                    std::vector<int> pids_to_freeze;
                    std::string strategy_log_msg;

                    if (app.pids.empty()) {
                        LOGI("Freeze skipped for %s as all its processes have died.", app.package_name.c_str());
                        app.background_since = 0;
                        app.freeze_retry_count = 0;
                        continue;
                    }

                    if (app.has_rogue_structure) {
                        strategy_log_msg = "检测到流氓结构，执行“斩首行动”";
                        for (int pid : app.pids) {
                            if (pid != app.rogue_puppet_pid) {
                                pids_to_freeze.push_back(pid);
                            }
                        }
                    } else {
                        strategy_log_msg = "执行“常规打击”";
                        pids_to_freeze = app.pids;
                    }
                    size_t frozen_pids_count = pids_to_freeze.size();

                    logger_->log(LogLevel::INFO, "冻结", strategy_log_msg, app.package_name, app.user_id);
                    int freeze_result = action_executor_->freeze(key, app.pids);
                    
                    std::stringstream log_msg_ss;
                    log_msg_ss << "[" << frozen_pids_count << "/" << total_pids << "] ";

                    switch (freeze_result) {
                        case 0:
                            app.current_status = AppRuntimeState::Status::FROZEN;
                            app.freeze_method = AppRuntimeState::FreezeMethod::CGROUP;
                            log_msg_ss << "因后台超时被冻结 (Cgroup)";
                            logger_->log(LogLevel::ACTION_FREEZE, "冻结", log_msg_ss.str(), app.package_name, app.user_id);
                            schedule_timed_unfreeze(app);
                            probe_config_needs_update = true;
                            app.background_since = 0;
                            app.freeze_retry_count = 0;
                            break;
                        case 1:
                            app.current_status = AppRuntimeState::Status::FROZEN;
                            app.freeze_method = AppRuntimeState::FreezeMethod::SIG_STOP;
                            log_msg_ss << "因后台超时被冻结 (SIGSTOP)";
                            logger_->log(LogLevel::ACTION_FREEZE, "冻结", log_msg_ss.str(), app.package_name, app.user_id);
                            schedule_timed_unfreeze(app);
                            probe_config_needs_update = true;
                            app.background_since = 0;
                            app.freeze_retry_count = 0;
                            break;
                        case 2:
                            app.freeze_retry_count++;
                            if (app.freeze_retry_count > MAX_FREEZE_RETRIES) {
                                logger_->log(LogLevel::WARN, "冻结", "多次尝试冻结失败，已放弃", app.package_name, app.user_id);
                                app.background_since = 0;
                                app.freeze_retry_count = 0;
                            } else {
                                logger_->log(LogLevel::INFO, "冻结", "冻结遇到软失败，将重试", app.package_name, app.user_id);
                                app.background_since = now;
                            }
                            break;
                        default:
                             logger_->log(LogLevel::ERROR, "冻结", "冻结遇到致命错误，已中止", app.package_name, app.user_id);
                            app.background_since = 0;
                            app.freeze_retry_count = 0;
                            break;
                    }
                    changed = true;
                }
            }
        }
    }

    if (probe_config_needs_update) {
        notify_probe_of_config_change();
    }
    return changed;
}

void StateManager::cancel_timed_unfreeze(AppRuntimeState& app) {
    if (app.scheduled_unfreeze_idx != -1) {
        if (app.scheduled_unfreeze_idx < unfrozen_timeline_.size()) {
            if (unfrozen_timeline_[app.scheduled_unfreeze_idx] == app.uid) {
                unfrozen_timeline_[app.scheduled_unfreeze_idx] = 0;
                LOGD("TIMELINE: Cancelled scheduled unfreeze for %s at index %d.", app.package_name.c_str(), app.scheduled_unfreeze_idx);
            }
        }
        app.scheduled_unfreeze_idx = -1;
    }
}

void StateManager::schedule_timed_unfreeze(AppRuntimeState& app) {
    if (!master_config_.is_timed_unfreeze_enabled || master_config_.timed_unfreeze_interval_sec <= 0 || app.uid < 0) {
        return;
    }
    cancel_timed_unfreeze(app);
    uint32_t future_index = (timeline_idx_ + master_config_.timed_unfreeze_interval_sec) % unfrozen_timeline_.size();
    for (size_t i = 0; i < unfrozen_timeline_.size(); ++i) {
        uint32_t current_index = (future_index + i) % unfrozen_timeline_.size();
        if (unfrozen_timeline_[current_index] == 0) {
            unfrozen_timeline_[current_index] = app.uid;
            app.scheduled_unfreeze_idx = current_index;
            LOGD("TIMELINE: Scheduled timed unfreeze for %s (uid %d) at index %u.", app.package_name.c_str(), app.uid, current_index);
            return;
        }
    }
    LOGW("TIMELINE: Could not find empty slot for %s. Timeline is full!", app.package_name.c_str());
}

bool StateManager::check_timed_unfreeze() {
    bool state_changed = false;
    int uid_to_unfreeze;
    {
        std::lock_guard<std::mutex> lock(state_mutex_);
        timeline_idx_ = (timeline_idx_ + 1) % unfrozen_timeline_.size();
        uid_to_unfreeze = unfrozen_timeline_[timeline_idx_];
        if (uid_to_unfreeze == 0) return false;
        unfrozen_timeline_[timeline_idx_] = 0;
    }
    {
        std::lock_guard<std::mutex> lock(state_mutex_);
        for (auto& [key, app] : managed_apps_) {
            if (app.uid == uid_to_unfreeze) {
                if (app.current_status == AppRuntimeState::Status::FROZEN && !app.is_foreground) {
                    LOGI("TIMELINE: Executing timed unfreeze for %s.", app.package_name.c_str());
                    logger_->log(LogLevel::TIMER, "定时器", "执行定时解冻", app.package_name, app.user_id);
                    if(unfreeze_and_observe_nolock(app, "定时器唤醒", WakeupPolicy::STANDARD_OBSERVATION)) {
                       state_changed = true;
                    }
                }
                app.scheduled_unfreeze_idx = -1;
                break;
            }
        }
    }
    if (state_changed) {
        broadcast_dashboard_update();
        notify_probe_of_config_change();
    }
    return state_changed;
}

bool StateManager::perform_deep_scan() {
    bool changed = false;
    {
        std::lock_guard<std::mutex> lock(state_mutex_);

        changed = reconcile_process_state_full();

        time_t now = time(nullptr);

        for (auto& [key, app] : managed_apps_) {
            if (app.current_status == AppRuntimeState::Status::FROZEN && !app.pids.empty()) {
                action_executor_->verify_and_reapply_oom_scores(app.pids);
            }

            if (app.pids.empty() && app.current_status != AppRuntimeState::Status::STOPPED) {
                if (app.undetected_since == 0) {
                    app.undetected_since = now;
                } else if (now - app.undetected_since >= 3) {
                    if (app.current_status == AppRuntimeState::Status::FROZEN) {
                         LOGI("Frozen app %s no longer has active PIDs. Marking as STOPPED.", app.package_name.c_str());
                         cancel_timed_unfreeze(app);
                         action_executor_->verify_and_reapply_oom_scores(app.pids); 
                    }
                    app.current_status = AppRuntimeState::Status::STOPPED;
                    app.freeze_method = AppRuntimeState::FreezeMethod::NONE;
                    app.is_foreground = false;
                    app.background_since = 0;
                    app.observation_since = 0;
                    app.freeze_retry_count = 0;
                    app.mem_usage_kb = 0;
                    app.swap_usage_kb = 0;
                    app.cpu_usage_percent = 0.0f;
                    app.undetected_since = 0;
                    changed = true;
                }
            } else if (!app.pids.empty()) {
                app.undetected_since = 0;
            }
        }
    }
    return changed;
}

bool StateManager::on_config_changed_from_ui(const json& payload) {
    bool probe_config_needs_update = false;

    {
        std::lock_guard<std::mutex> lock(state_mutex_);
        if (!payload.contains("policies")) return false;

        LOGI("Applying new configuration from UI...");
        
        std::vector<AppConfig> new_configs;
        for (const auto& policy_item : payload["policies"]) {
            AppConfig new_config;
            new_config.package_name = policy_item.value("package_name", "");
            new_config.user_id = policy_item.value("user_id", 0);
            new_config.policy = static_cast<AppPolicy>(policy_item.value("policy", 0));
            if (!new_config.package_name.empty()) {
                new_configs.push_back(new_config);
            }
        }

        if (!db_manager_->update_all_app_policies(new_configs)) {
            LOGE("Failed to apply new configuration atomically. Old config remains.");
            return false;
        }

        for (const auto& new_config : new_configs) {
            AppRuntimeState* app = get_or_create_app_state(new_config.package_name, new_config.user_id);
            if (app) {
                bool policy_changed = app->config.policy != new_config.policy;
                app->config = new_config;

                if (policy_changed && app->current_status == AppRuntimeState::Status::FROZEN && (new_config.policy == AppPolicy::EXEMPTED || new_config.policy == AppPolicy::IMPORTANT)) {
                     if (unfreeze_and_observe_nolock(*app, "策略变更", WakeupPolicy::UNFREEZE_UNTIL_BACKGROUND)) {
                         probe_config_needs_update = true;
                     }
                }
            }
        }
        logger_->log(LogLevel::EVENT, "配置", "应用策略已从UI原子化更新");
        LOGI("New configuration applied atomically.");
    }

    if (probe_config_needs_update) {
        notify_probe_of_config_change();
    }
    return true;
}


json StateManager::get_dashboard_payload() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    json payload;
    if (last_metrics_record_) {
        payload["global_stats"] = {
            {"total_cpu_usage_percent", last_metrics_record_->total_cpu_usage_percent},
            {"total_mem_kb", last_metrics_record_->mem_total_kb},
            {"avail_mem_kb", last_metrics_record_->mem_available_kb},
            {"swap_total_kb", last_metrics_record_->swap_total_kb},
            {"swap_free_kb", last_metrics_record_->swap_free_kb},
        };
    } else {
        payload["global_stats"] = json::object();
    }

    json apps_state = json::array();
    for (auto& [key, app] : managed_apps_) {
        if (app.pids.empty() && app.current_status == AppRuntimeState::Status::STOPPED) {
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
        app_json["is_playing_audio"] = is_app_playing_audio(app);
        app_json["is_using_location"] = sys_monitor_->is_uid_using_location(app.uid);
        app_json["has_high_network_usage"] = sys_monitor_->get_cached_network_speed(app.uid).download_kbps > NETWORK_THRESHOLD_KBPS;        
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
    auto db_master_config = db_manager_->get_master_config().value_or(MasterConfig{});
    auto all_db_configs = db_manager_->get_all_app_configs();
    json response;
    response["master_config"] = {
        {"is_enabled", true},
        {"freeze_on_screen_off", true},
        {"standard_timeout_sec", db_master_config.standard_timeout_sec},
        {"is_timed_unfreeze_enabled", db_master_config.is_timed_unfreeze_enabled},
        {"timed_unfreeze_interval_sec", db_master_config.timed_unfreeze_interval_sec}
    };
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
    json frozen_pids = json::array();

    for (const auto& [key, app] : managed_apps_) {
        if (app.current_status == AppRuntimeState::Status::FROZEN) {
            if (app.uid != -1) {
                frozen_uids.push_back(app.uid);
            }
            for (int pid : app.pids) {
                frozen_pids.push_back(pid);
            }
        }
    }
    payload["frozen_uids"] = frozen_uids;
    payload["frozen_pids"] = frozen_pids;
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
    if (config_opt) {
        new_state.config = *config_opt;
    } else {
        LOGI("New app instance discovered: %s (user %d). Creating default DB entry.", package_name.c_str(), user_id);
        if (is_critical_system_app(package_name)) {
            new_state.config = AppConfig{package_name, user_id, AppPolicy::EXEMPTED};
        } else {
            new_state.config = AppConfig{package_name, user_id, AppPolicy::EXEMPTED};
        }
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

    if (app->app_name == app->package_name) {
        std::string friendly_name = sys_monitor_->get_app_name_from_pid(pid);
        if (!friendly_name.empty()) {
            size_t colon_pos = friendly_name.find(':');
            if (colon_pos != std::string::npos) {
                app->app_name = friendly_name.substr(0, colon_pos);
            } else {
                app->app_name = friendly_name;
            }
        }
    }

    if (std::find(app->pids.begin(), app->pids.end(), pid) == app->pids.end()) {
        app->pids.push_back(pid);
        pid_to_app_map_[pid] = app;
        if (app->current_status == AppRuntimeState::Status::STOPPED) {
           app->current_status = AppRuntimeState::Status::RUNNING;
           logger_->log(LogLevel::INFO, "进程", "检测到新进程启动", app->package_name, user_id);
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
            app->freeze_retry_count = 0;
            app->undetected_since = 0;
            app->freeze_method = AppRuntimeState::FreezeMethod::NONE;
            cancel_timed_unfreeze(*app);
        }
    }
}

bool StateManager::is_critical_system_app(const std::string& package_name) const {
    return critical_system_apps_.count(package_name) > 0;
}