# Rabbit 2 工具链评估（2026-11-03）

修改前配置保存在 evidence/pre-toolchain-upgrade：根 build.gradle.kts、app-build.gradle.kts、settings.gradle.kts、gradle.properties、gradle-wrapper.properties。回退时分别复制回原路径；不回退用户的 SDK 或 Windows 工程。

初始：AGP 8.7.3、Kotlin/Compose compiler 2.0.21、Wrapper 8.9；Material3 1.3.1、Activity Compose 1.9.3、Lifecycle 2.8.7；compile/target 35、min 26。IDE 是 Rabbit 2 2026.2.2，自带 JBR 25.0.4；已有 SDK API 37.0、Build Tools 36.0.0 和在线 AVD Medium_Phone_API_37.0。

选择：AGP 9.5.0 正式版、Gradle 9.6.0、JBR 25。Rabbit 2 支持 AGP 到 9.5；AGP 9.5 的官方最低/默认 Gradle 为 9.6，Build Tools 36。Gradle 9.1 起支持 Java 25。Gradle 最新稳定为 9.8.2，但本轮优先官方默认组合，不使用预发布版本。

AGP 9.5 官方 POM 依赖 Kotlin Gradle Plugin 2.2.10，因此 Compose compiler 使用同版本 2.2.10。移除独立 org.jetbrains.kotlin.android，使用 AGP built-in Kotlin；移除 android.kotlinOptions，JVM target 继承 Java targetCompatibility 17。运行 Gradle 的 JDK 25 不等于 APK 使用 Java 25 字节码。Compose UI 库暂保持原稳定版本，避免混合 UI 升级和构建迁移。

网络：受限执行环境读不到部分 SDK 路径且无法访问本机代理；提升权限后使用已有代理 127.0.0.1:7897 可访问官方 HTTPS。未修改系统代理，未关闭证书验证，未设置明文凭据。首次构建遇到 sdk-common POM TLS 握手中断；同 URL curl 代理探测成功，说明是网络路径间歇故障，不是版本不存在。日志分别保存，不用降级版本代替网络诊断。

命令：PowerShell `./scripts/Verify.ps1 -ProxyHost 127.0.0.1`；代理仅在脚本进程生效并恢复原环境。IDE 如需代理，在 IDE HTTP Proxy 配置同一本机地址；不应将代理密码提交源码。

官方资料：
- https://developer.android.google.cn/studio/releases
- https://developer.android.google.cn/build/releases/agp-9-5-0-release-notes?hl=en
- https://docs.gradle.org/current/userguide/compatibility.html
- https://developer.android.com/build/migrate-to-built-in-kotlin
- https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.5.0/gradle-9.5.0.pom

构建结果以 evidence 中本轮实际日志为准，配置选择不等同于验证通过。
