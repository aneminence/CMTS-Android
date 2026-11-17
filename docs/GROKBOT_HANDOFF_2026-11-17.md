# Android 开发交接 — 2026-11-17

## 当前交付

工程：`D:\CMTS\android`，分支 `master`，远程 `aneminence/CMTS-Android`。发布版本更新为 0.1.7 / versionCode 8，延续 debug 签名 APK 发布方式。仅修改 Android。

- 记录列表可打开外部 VBO/KML（仅预览，不成为本地会话）；显示时长与行驶里程，支持重命名，导出文件名与显示名一致。列表立即出现，时长与里程在后台补算。
- 行驶里程按定位速度对时间积分（低于 0.5 km/h 不计，间隔超过 5 s 跳过），在停止录制或旧文件首次打开时写入 `metadata.json`，之后直接读取。
- 停止录制时若行驶里程为 0，不保存会话，提示「轨迹过短，未记录」。
- 实时仪表顶部以一行参考文字显示录制时间、行驶时间（不含停车）和行驶里程。
- 同一次录制中成绩累计，中途停车不清空；开始新录制时清空。加速成绩同时显示距离；加速时间与制动距离用香槟金强调。
- 姿态页增加去重力 GG 图（拖影 5 s，量程 ±1 g，红色轨迹随时间变淡）。竖屏展开 GG 在上、俯仰与侧倾并列在下；横屏展开 GG 占左半、俯仰在上侧倾在下占右半；竖屏半开为俯仰、GG、侧倾。俯仰与侧倾圆盘等大。
- Google 卫星图最大缩放 21；锁定跟随时不再把缩放打回 16。应用内显示软件版本。

## 实现入口与语义

- `TripMetrics.kt` / `SessionCatalog.kt` / `CaptureService.kt`：速度×时间积分与元数据缓存；停录时按 `distanceMeters <= 0` 丢弃会话目录。
- `RecordsScreen.kt`、`Kml.kt`、`MainActivity.kt`：记录列表、打开外部文件、重命名与导出文件名。
- `Dashboard.kt`、`GgPlot.kt`：行程参考行、GG 图与姿态页布局。
- `performance/RunCycle.kt`、`PerformanceController.kt`、`PerformanceScreen.kt`：同次录制成绩累计，加速距离与香槟金强调。
- `MapScreen.kt`、`AmapScreen.kt`：最大缩放 21，跟随只更新中心。

## 验证与设备

- 本轮 `assembleDebug`、`testDebugUnitTest`、`lintDebug` 通过。构建产物不提交。
- 最新 APK：`app/build/outputs/apk/debug/app-debug.apk`；GitHub Release 资源为 `CMTS-Android-0.1.7-debug.apk`。
- S23 Ultra：SM-S9180，ADB `R5CW51WPC6B`；以 `adb install -r --no-incremental` 覆盖安装并保留应用数据。
- 不要把安装成功写成功能或室外实车已验收。

## 接手优先检查

- 当前有效状态以 `PROJECT_STATE.md` 与 `docs/agent/` 为准。
- 高德 20 Hz 轨迹锯齿仍为低优先级已知问题；不要过滤原始 GNSS。
- 用户另有界面改动说明时再改布局。
