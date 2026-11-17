# 项目状态

- 仓库：https://github.com/aneminence/CMTS-Android ，本地路径 `D:\CMTS\android`
- 当前软件版本：`0.1.6` / `versionCode` 7，包名 `com.cmts.mobile`，显示名 CMTS
- 默认分支：`master`，提交 `0abde54`（2026-11-16，`release: 0.1.6 with recording pause and hold-to-stop controls`），与 `origin/master` 一致
- 最近一次更新：2026-11-17 10:29 Asia/Shanghai，CMTS · Android开发（文档结构已按用户授权提交；未改功能代码）

对照默认分支源码、`README.md` 与既有交接后整理。下列“已实现”指代码已在 `master`；验收状态另行标明。

## 已实现的主要功能

- Standalone 采集：前台服务采集手机 GNSS / IMU / 气压，Compose 地图与浮动仪表面板，会话 CSV + `metadata.json`。
- 定位源可在「手机 GNSS」与「外接 UM982」（USB 串口 NMEA）之间切换；支持 CH344Q 与板载 CH340；离开调试页后后台继续收数；外接无数据时**不**悄悄退回手机 GNSS；应用不向模块发送配置命令。NMEA 按同一 UTC 历元合并为一次定位，轨迹按历元更新。
- 录制：显式开始才建会话；可暂停/继续同一会话（暂停不写普通 `samples.csv`，仪表与性能采集仍可运行）；停止须长按 2 秒。计分默认连续，新录制清空成绩表。
- 回放：本地会话轨迹与曲线（含安装归零）；回放离线计分写 `result-vN.json`。导出 VBO / KML / CSV。
- Performance Test：多选阈值段、待命自动起止、质量标识、历史与重算；速度源跟随当前定位源。
- 局域网：Android 作为 WebSocket 服务端，Smart LAN 发现；一键复制 `cmts://` 连接串。协议以 Windows 侧 SSOT 为准。
- 实验性车辆姿态估算（`estimate.*`）与原系统姿态并行，可对照显示与离线重算；**定量 GNSS 加速度补偿未启用**。
- 地图：默认 Google 卫星瓦片（WGS84 绘制）；可切换高德（显示前将坐标转为 GCJ-02，不改 `TrackPoint` 与导出文件）。

## 正在开发的功能

功能代码工作区在写本文档时为干净的 `master`，没有未提交的功能改动。本轮只建立 Agent 文档结构。

## 尚未完成或未验证

- **室外实车验收未宣称通过**（README 与各版发布说明均保留此边界）。
- 0.1.6 长按 2 秒停止的手感、提前松手/旋转取消，以及横竖屏最终视觉：已覆盖安装到 S23 Ultra，代理未做实机操作验收。
- 高德底图上外接 20 Hz 轨迹出现锯齿；用户于 2026-11-16/17 确认同一份轨迹切到谷歌卫星图后平滑。原始定位与导出仍为 WGS84。原因尚未修复。
- 姿态：户外停车校准、动态融合、独立参考精度未验收；Windows 对 `estimate.attitude/status` 的显示未实机验收。
- 远程：暂停没有独立协议状态，远程状态不会单独报告暂停。
- Android CSV 是否可被 Windows 客户端直接导入，尚未完成核对。
- RK3588 系统界面重力感应方向与屏幕物理方向不一致，属系统镜像 / DTS，不在本应用修改范围内；未改固件。
- Google 瓦片商用授权与国内网络稳定性仍开放（`docs/adr/ADR-003.md`）。
- `docs/tasks.md` 中仍开放的项（全量手势回归、VBO 在 Windows / VBOX Test Suite 打开、轨迹抽稀、导出选择器健壮性等）尚未关闭。

## 当前主要问题

1. 高德显示链路导致密集轨迹锯齿，谷歌底图无此现象（优先级低，暂不修复）。
2. 缺少已宣布通过的室外实车验收。
3. `docs/architecture.md` 仍写「USB UM982 仅预留契约」，与 `master` 代码不符；以 `docs/agent/ARCHITECTURE.md` 为准。

## 下一步开发建议

1. 请用户实机确认 0.1.6 长按停止、暂停/继续同一会话。
2. 高德轨迹锯齿优先级低，暂不修复；若日后处理，只查显示链路，不改原始定位数据。
