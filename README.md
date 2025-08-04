#  Cerberus - 智能后台管理与系统监控工具

![Project Cerberus Banner](https://img.shields.io/badge/Project-Cerberus-blueviolet?style=for-the-badge&logo=android)
![Language](https://img.shields.io/badge/Language-Kotlin%20%26%20C%2B%2B-orange?style=for-the-badge)
![License](https://img.shields.io/badge/License-MIT-green?style=for-the-badge)
![Status](https://img.shields.io/badge/Status-Active%20Development-brightgreen?style=for-the-badge)

**中文** | [English](#) 

---

## 📖 项目简介

**Cerberus** 是一款专为 Android root玩家设计的系统级后台管理（冻结工具）与性能监控工具。

项目采用现代化的技术栈（Jetpack Compose, Kotlin Coroutines, C++17）和创新的混合架构（`Xposed`+`Daemon`），旨在实现极致的性能、功耗与通知体验的平衡。

## ✨ 核心功能

*   **智能应用管理**：
    *   **多策略冻结**：为每个应用设置“豁免”、“智能”、“严格”等不同管理策略。
    *   **优雅冻结技术**：优先使用现代 `Cgroup v2 Freezer` 和 `Binder` 冻结技术，确保应用在不被系统杀死的前提下被彻底“休眠”，并在必要时以 `SIGSTOP` 作为可靠后备。
    *   **ANR 保护**：通过Xposed Hook主动拦截针对已冻结应用的ANR（应用无响应）检测，从根本上防止它们被系统错误地杀死。

*   **事件驱动的智能唤醒**：
    *   **推送唤醒 (FCM/GCM)**：精准拦截FCM/GCM广播，在应用被冻结时，临时将其唤醒一小段时间以接收和处理推送，之后自动重新冻结。
    *   **通知唤醒**：当高优先级通知到达时，智能唤醒对应应用。
    *   **定时唤醒**：可配置的定时解冻机制。

*   **全方位系统监控面板 (Dashboard)**：
    *   **实时数据流**：通过TCP与后台守护进程通信，实时展示系统总览（CPU、内存、Swap使用率）和各应用运行状态（CPU、内存占用、状态）。
    *   **多核CPU监控**：在统计图表中，清晰展示每个CPU核心的实时使用率。
    *   **电池与环境感知**：监控电池温度、功耗，并感知充电、亮屏、音频播放、定位等状态，为未来的智能策略提供数据基础。

*   **深度日志与统计**：
    *   **事件时间线**：记录所有核心操作（应用冻结/解冻、策略变更、唤醒事件等），帮助用户追溯系统行为。
    *   **Doze 报告**：在设备退出深度休眠后，自动生成一份报告，展示休眠期间哪些应用消耗了CPU资源。
    *   **资源历史图表**：以滚动图表形式可视化展示CPU、内存、电池温度的历史数据。

## 🏛️ 架构解析

Cerberus 采用创新的三体架构，各部分职责分明、高效协作：

1.  **前端UI (Android App - `app`模块)**
    *   **技术栈**: 100% Kotlin, Jetpack Compose, Material 3, ViewModel, Coroutines Flow, Coil。
    *   **职责**: 提供用户交互界面，展示来自`Daemon`的数据，并将用户配置发送给`Daemon`。UI层经过精心优化，通过自定义Coil Fetcher实现应用图标的按需加载和内存优化。

2.  **守护进程 (Native Daemon - `daemon`模块)**
    *   **技术栈**: C++17, aarch64/armv7a, TCP Server, SQLiteCpp, Nlohmann JSON。
    *   **职责**: 项目的“大脑”。以Root权限在后台运行，负责：
        *   执行真正的冻结/解冻操作 (`ActionExecutor`)。
        *   管理应用策略和配置，并持久化到SQLite数据库 (`DatabaseManager`)。
        *   监控系统核心指标 (`SystemMonitor`)。
        *   维护所有应用的核心状态机 (`StateManager`)。
        *   作为数据中心，通过TCP服务与UI和Probe通信。

3.  **系统探针 (Xposed Module - `lsp`模块)**
    *   **技术栈**: Xposed (LSPosed/EdXposed), Kotlin。
    *   **职责**: 注入到`system_server`进程中，负责：
        *   Hook系统关键服务（AMS, NMS, PowerManager等）。
        *   拦截并处理FCM/GCM广播、通知、ANR流程、Wakelock等。
        *   将捕获到的系统事件实时发送给`Daemon`进行决策。
        *   从`Daemon`接收冻结列表，为ANR保护等功能提供依据。

![Architecture Diagram](链接)

### 通信机制

*   **UI <-> Daemon**: 通过 `127.0.0.1:28900` 的TCP套接字进行双向JSON RPC通信。UI发送配置命令和查询请求，Daemon实时推送Dashboard、日志和统计数据流。
*   **Probe -> Daemon**: 通过短连接的TCP套接字，将捕获到的系统事件（如“应用唤醒请求”）单向发送给Daemon。同时，在Probe启动时会与Daemon建立连接以获取初始配置。

## 🚀 安装与构建

### 环境要求

*   Android 8.0+
*   已解锁并获取Root权限的设备
*   已安装 Magisk
*   已安装 LSPosed (或其他支持Xposed API 82的框架)

### 构建步骤

1.  **克隆仓库**:
    ```bash
    git clone 链接
    cd Cerberus
    ```

2.  **构建前端 (APK)**:
    *   使用 Android Studio 打开项目。
    *   项目将自动通过Gradle Sync同步依赖。
    *   点击 `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`。
    *   生成的APK位于 `app/build/outputs/apk/release/app-release.apk`。

3.  **构建后端 (Daemon)**:
    *   确保已安装Android NDK。
    *   在项目根目录执行以下命令（请根据您的NDK路径和目标架构进行调整）：
    ```bash
    cd daemon
    mkdir build && cd build
    
    # 为 arm64-v8a 构建
    cmake .. \
      -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a \
      -DANDROID_PLATFORM=android-30 \
      -DCMAKE_BUILD_TYPE=Release
      
    make cerberusd
    ```
    *   生成的可执行文件为 `daemon/build/cerberusd`。

### 部署与使用

1.  **安装APK**：将构建好的 `app-release.apk` 安装到您的设备上。
2.  **激活Xposed模块**：在LSPosed管理器中，激活 **CRFzit** 模块，并确保作用域勾选了 **系统框架 (`android`)**。重启设备。
3.  **部署Daemon**：
    *   将 `cerberusd` 可执行文件推送到设备的某个可执行路径，例如 `/data/adb/cerberus/`。
    *   赋予其可执行权限：`chmod +x /data/adb/cerberus/cerberusd`。
4.  **运行Daemon**：
    *   为了让Daemon在后台持续运行，建议通过Magisk启动脚本或rc脚本来启动它。一个简单的启动脚本 `post-fs-data.sh` 可能如下：
    ```sh
    #!/system/bin/sh
    MODDIR=${0%/*}
    
    # 确保目录存在
    mkdir -p /data/adb/cerberus
    cp $MODDIR/cerberusd /data/adb/cerberus/cerberusd
    chmod 755 /data/adb/cerberus/cerberusd
    
    # 启动守护进程
    /data/adb/cerberus/cerberusd &
    ```
5.  **开始使用**：打开CRFzit应用，如果一切正常，您应该能看到“主页”上实时跳动的数据。

## 致谢

*   **LSPosed** 团队，为现代Android提供了强大的Hook框架。
*   **topjohnwu**，Magisk的创造者。
*   **weisu**，KernelSU的创造者。
*   所有为Android开源社区做出贡献的开发者。

---
**免责声明**: 本项目涉及系统底层修改，请确保您了解相关风险。作者不对因使用本项目造成的任何数据丢失或设备损坏负责。