> 2026-11-03 更新：当前采用 AGP 9.5.0 / Gradle 9.6.0 / JBR 25 / 内置 Kotlin 与 Compose compiler 2.2.10。下文旧版环境说明为初始记录；最新配置与验证依据见 docs/toolchain-upgrade.md 和 evidence/upgrade-build-retry.log。官方 Wrapper 已补齐。

# 统一开发与验收流程

Android：Android Studio + Kotlin 2.0.21 + Jetpack Compose + AGP 8.7.3 + Gradle 8.9，JDK 17。Windows：原有 Visual Studio / WPF 独立使用；不修改解决方案或全局 Java/PATH。

Android Studio 打开 D:/CMTS/android。SDK Manager 安装 Platform 35、Build Tools 34.0.0、Platform Tools、Emulator、Command-line Tools，以及 API 35 Google APIs x86_64 镜像。在项目 Gradle 设置选择兼容的 JDK，避免改变 Windows 开发环境。

当前网络无法解析 raw.githubusercontent.com，官方 Wrapper 下载失败。安装 Gradle 8.9 后在 android 目录执行 `gradle wrapper --gradle-version 8.9 --distribution-type bin`，生成官方 wrapper，再使用 `./gradlew.bat`。不要把缺失 wrapper 的工程当作可直接同步成功。

## Emulator

运行 `./scripts/Create-Emulator.ps1` 创建不覆盖旧设备的 CMTS_API35，或在 Android Studio Device Manager 新建 API 35 x86_64 AVD；启用 accelerometer 与 gyroscope。使用 Device Manager 启动，Run/Debug 选择该设备；在回调处设断点验证。

运行 `./scripts/Verify.ps1 -Serial emulator-5554 -DeviceClass emulator` 执行构建、测试、lint、安装和启动并保存日志。实际 serial 以 adb devices -l 为准。脚本启动成功不等于采集通过。

开始采集后执行 Replay-SimulatedRoute.ps1，或 Extended Controls > Location 导入 GPX/KML/播放保存路线。Virtual sensors > Device Pose 测试旋转及倾斜，检查加速度/陀螺仪回调、轴方向和时间戳；没有陀螺仪回调须标为不可用，不能通过加速度推算冒充真实陀螺仪。

## 手机与 RK3588

手机启用 USB 调试并在设备确认电脑授权，运行 adb devices -l，然后 Verify.ps1 -Serial 实际序列号 -DeviceClass phone。RK3588 使用 rk3588 标签，但当前不要求供应商系统权限。不得卸载供应商 APK。

当前无 JNI/NDK 库，不限制 ABI，同一 APK 面向 x86_64 模拟器与 ARM64 Android。后续引入原生库必须同时交付 arm64-v8a 和 x86_64，检查 APK lib 目录；系统版本需 API 26 以上。此为构建设计，非 RK3588 兼容实测结论。

## 每阶段门槛

构建 + JUnit + lint 必须成功，再执行权限拒绝、采集、停止、重新打开、导出解析、锁屏与旋转测试。分别记录 emulator / phone / rk3588，包含型号、系统、APK SHA256、日志、采集样本与失败项。手机不少于 10 分钟真实 GNSS/IMU 为 P1 门槛。模拟器通过只能证明软件路径。未运行、缺硬件或环境阻塞必须标注 BLOCKED/NOT RUN，不得标 PASS。

官方依据：https://developer.android.com/build/releases/agp-8-7-0-release-notes 与 https://developer.android.com/studio/run/emulator-extended-controls 。

