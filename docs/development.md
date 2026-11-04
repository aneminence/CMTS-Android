> 2026-11-03 更新：当前采用 AGP 9.5.0 / Gradle 9.6.0 / JBR 25 / 内置 Kotlin 与 Compose compiler 2.2.10。下文旧版环境说明为初始记录；最新配置与验证依据见 docs/toolchain-upgrade.md 和 evidence/upgrade-build-retry.log。官方 Wrapper 已补齐。

# 环境审查（2026-11-03）

工作区 D:/CMTS；Windows 工程位于 Chassis Master Test Suite，net10.0-windows / WPF；Android 新建于旁级 android，未修改 Windows 源码。

Get-Command java,gradle,adb,kotlinc 未返回可用命令；默认 Program Files/Java、Program Files/Android、LOCALAPPDATA/Android/Sdk、用户 .gradle 检查未发现安装。不能据此排除非标准目录安装。

依赖：Kotlin、Android Gradle Plugin、AndroidX Compose/Activity/Lifecycle，通常采用 Apache-2.0；JUnit 4 为 EPL-1.0。依赖许可证最终以下载包为准。未下载 SDK 或接受 SDK 协议。

构建步骤见 README。需要先配置工具链，再运行测试和构建，使用 adb devices -l 确认设备后安装验证。

