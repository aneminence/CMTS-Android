# 跨 Agent 交接

更新：2026-11-17 Asia/Shanghai，CMTS · Android开发

## 最近一次任务目标

将 `feature/records-dashboard-gg` 的记录/行程/仪表/成绩/GG/地图改动合并回 `master`，版本升到 0.1.7 / versionCode 8，更新 README，同步 GitHub 并发布 debug APK。

## 实际完成内容

- 上述功能已合入 `master` 并发布为 0.1.7（标签 `v0.1.7`）。
- 里程与行驶时间：定位速度对时间积分，运动阈值 0.5 km/h；相邻点间隔大于 5 s 不积分。录制时间为墙钟，含停车。停止时写入该条 `metadata.json`；旧文件首次打开时算一次并落盘。行驶里程为 0 时不保存会话，提示「轨迹过短，未记录」。
- 实时行程参数改为仪表顶部一行参考文字。姿态页 GG 图拖影 5 s、±1 g，红色随时间变淡；俯仰/侧倾圆盘等大，并按用户确认的横竖屏布局排列。
- P16 上 `assembleDebug`、`testDebugUnitTest`、`lintDebug` 通过。已覆盖安装到 S23 Ultra（SM-S9180，`R5CW51WPC6B`），保留应用数据。

## 尚未完成的部分

- 未做实机操作或室外实车验收，不要把安装成功写成已验收。
- 用户另有界面改动需求时尚未开始。
- 高德轨迹锯齿仍按已知问题保留，优先级低。

## 关键代码位置

- 工程根：本地目录 `CMTS-Android`，分支 `master`
- 行程算法与缓存：`TripMetrics.kt`、`SessionCatalog.kt`、`CaptureService.kt`
- 记录列表与打开文件：`RecordsScreen.kt`、`Kml.kt`、`MainActivity.kt`
- 仪表与 GG 图：`Dashboard.kt`、`GgPlot.kt`
- 成绩累计与强调：`performance/RunCycle.kt`、`PerformanceController.kt`、`PerformanceScreen.kt`
- 地图：`MapScreen.kt`（谷歌最大缩放 21）、`AmapScreen.kt`（跟随只更新中心）

## 遗留问题

见 `docs/agent/KNOWN_ISSUES.md`。本轮未关闭既有开放项。功能侧待用户实车确认。

## 后续 Agent 接手注意事项

- 不要把单测通过或覆盖安装写成实车已验收。
- 未经用户明确授权，不得再升版本、打标签或推送。
- 协议 SSOT 在 Windows；本轮未改远程字段。
- RK3588 系统重力方向问题不要用 `wm size` / `wm density` 或刷机处理。

## 推荐的下一步行动

1. 请用户实机确认 0.1.7 功能。
2. 用户另有界面说明时，再改布局。
