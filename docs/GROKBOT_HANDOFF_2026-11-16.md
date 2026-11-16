# Android 开发交接 — 2026-11-16

## 当前交付

工程：`D:\CMTS\android`，分支 `master`，远程 `aneminence/CMTS-Android`。版本仍为 0.1.5 / versionCode 6，没有升级版本号。仅修改 Android。

- 竖屏收起面板：开始/暂停占左半区，右半区停止与 GNSS 等分。用嵌套等权 Row 计入间隔，与下方四张摘要卡片列边缘对齐。
- 横屏窄栏：开始/暂停整行，下方停止与 GNSS 左右并排。
- 开始/暂停：录制中绿色，暂停和未开始黄色；文案依次为开始记录、暂停记录、继续记录。
- 停止：红色，必须持续长按 **2 秒**。从顶部顺时针扇形填充整个按钮，沿 16dp 圆角裁切；提前松手取消并清零，短按不停止。早期 3 秒方案已被用户改为 2 秒。
- 控制区与摘要卡片圆角 16dp、间距 8dp。
- 竖屏完整仪表：顶部速度保持；第二行高度/气压；第三行水平精度/垂直精度/可见与定位数。GNSS 详情为纬度/经度/航向，不重复高度与垂直精度。

## 实现入口与语义

- `MainActivity.kt`：RecordingButton、StopRecordingButton、LandscapePeekChrome、PortraitPanelBody。停止采用 detectTapGestures 的按压周期，单调时钟计时，每约 16ms 刷新扇形；取消按压会取消计时协程。圆的半径为按钮对角线的一半，确保填充覆盖矩形四角。
- `CaptureService.kt`：新增 toggle_pause intent；采集 Handler 上翻转 recordingPaused。暂停只跳过普通 samples.csv 的 append，不关闭 writer、不释放 recordHold，继续写同一会话；仪表、远程与性能采集仍可运行。
- Live.recordingPaused 驱动界面；Live.running 在暂停期间仍为 true，代表会话未结束。RecordingStateMachine 仍为 RECORDING，没有新增协议状态，远程状态当前不会单独报告暂停。
- `Dashboard.kt`：完整竖屏仪表布局与 GNSS 详情，移除旧 supplementaryReadings 回调。
- `GnssSourceSwitch.kt`：统一按钮圆角。

## 验证与设备

- 本轮 assembleDebug、现有 testDebugUnitTest、lintDebug 通过。最终 2 秒/全按钮扇形调整再次 assembleDebug、lintDebug 通过，git diff --check 通过。
- 本地日志：`evidence/hold-stop-build.log`、`evidence/hold-stop-final-build.log`、`evidence/hold-stop-2s-build.log`；evidence 不提交。
- 最新 APK：`app/build/outputs/apk/debug/app-debug.apk`，构建产物不提交。
- S23 Ultra：SM-S9180，ADB `R5CW51WPC6B`；最新 APK 通过 adb install -r 安装成功，保留应用数据。
- 用户已用截图反馈前几轮效果；最终 2 秒长按手感、松手取消、旋转中取消与最终横竖屏视觉尚未由代理实机操作验收。不要把安装成功写成这些交互已验收。

## 接手优先检查

1. S23 横竖屏核对卡片边缘、圆角与间距；确认小屏/字体放大情况下文字及停止提示可见。
2. 短按与不足 2 秒不停止；按满 2 秒只停止一次；移出按钮、松手、切页或旋转取消进度。
3. 实际录制→暂停→继续→停止，检查同一 CSV 暂停期间无新增样本、继续后追加，性能采集不受普通录制暂停影响。
4. 暂停目前未增加独立元数据事件，时间戳保留真实间隔。后续如需远程暂停状态、回放/导出跨暂停间隔的明确分段，应另行定义并验证，勿假定已经支持。

本轮 README 和交接入口随实现一起提交。既有更广泛的 GNSS、USB、性能与姿态验证边界仍见其他交接文档。
