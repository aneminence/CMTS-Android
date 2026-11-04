# 工具链验证结果 — 2026-11-03

通过：Android Studio Rabbit 2 的新配置 Gradle Sync（17:28:06，见 evidence/ide-sync.log）；AGP 9.5.0 / Gradle 9.6.0 / JBR 25 下 assembleDebug、testDebugUnitTest、lintDebug；3 个 JUnit 测试，0 失败、0 错误。服务定位权限检查已修复，未禁用 lint。

最终日志：evidence/final-verification.log；完整构建/设备日志及 APK 副本：evidence/20261103-172929-emulator/。

APK：app/build/outputs/apk/debug/app-debug.apk。模拟器 emulator-5554 / Medium_Phone_API_37.0 / Android 17：ADB 安装 Success，启动 Status ok。只验证安装和启动，不将其视为 GNSS/IMU 全流程通过。

手机：未测试。RK3588：未测试。APK 中 graphics.path 原生依赖包含 arm64-v8a / armeabi-v7a / x86 / x86_64；此前“无原生库”仅适用于本项目没有自写 JNI，不能用于描述最终 APK。

已知问题：lint 尚有非阻断警告，包括较旧 compile/target SDK 与 UI 库、备份规则、唤醒锁超时、空间检测及应用图标。保留 SDK 35 和现有 UI 库是为了本轮聚焦工具链迁移，不将构建通过视为 P1 采集验收。另有 deprecated mock API 和 debug 原生库未剥离符号提示。

Gradle Daemon toolchain 的 Migrate 提示是可选优化，当前可 Ignore；IDE 和 Verify.ps1 都使用 Android Studio 自带 JBR。没有配置自动下载 JDK，没有修改 Windows 全局 JAVA_HOME、代理或 Visual Studio 工程。
