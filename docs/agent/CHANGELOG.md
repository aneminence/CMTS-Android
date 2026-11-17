# 功能变更记录

按时间倒序。历史条目摘自已合入 `master` 的发布说明与交接，验证结果沿用当时记录，本轮未重新跑构建或实机验收。

## 2026-11-17 10:20 Asia/Shanghai — CMTS · Android开发

- 功能名称：落地多 Agent 文档结构
- 修改内容：新增 `PROJECT_STATE.md` 与 `docs/agent/` 下 `CHANGELOG.md`、`ARCHITECTURE.md`、`HANDOFF.md`、`KNOWN_ISSUES.md`；在保留原有硬性边界的前提下更新 `AGENTS.md` 与 `CONTEXT_INDEX.md`，把现有 `GROKBOT_HANDOFF_*`、ADR 纳入索引而不是覆盖。
- 涉及文件：`AGENTS.md`、`CONTEXT_INDEX.md`、`PROJECT_STATE.md`、`docs/agent/*`
- 验证结果：对照 `master` @ `0abde54` 的源码与 README / 既有交接撰写；本轮无功能代码变更，未执行 Gradle 构建或实机测试。
- 兼容性影响：无协议或数据格式变更。用户于 2026-11-17 授权提交；高德 20 Hz 轨迹锯齿按用户意见保留在已知问题中，优先级低。

## 2026-11-16 — 0.1.6 / versionCode 7

- 执行来源：`0abde54`、`fa234c3`，详见 `README.md` 与 `docs/GROKBOT_HANDOFF_2026-11-16.md`
- 功能名称：普通录制暂停/继续，长按 2 秒停止，横竖屏控制区与仪表布局调整
- 修改内容：暂停只跳过普通 `samples.csv` 追加，不关闭会话；停止按钮须持续按住 2 秒，提前松手取消；竖屏开始/暂停占左半区，停止与 GNSS 平分右半区；横屏窄栏开始/暂停整行，下方停止与 GNSS 并排。
- 涉及文件：`MainActivity.kt`、`CaptureService.kt`、`Dashboard.kt`、`GnssSourceSwitch.kt`
- 验证结果：当时 assembleDebug、现有单元测试、lintDebug 通过；已覆盖安装到 S23 Ultra（SM-S9180）并保留数据。长按手感与横竖屏最终视觉仍待用户实机确认。
- 兼容性影响：`RecordingStateMachine` 未新增暂停态，远程不会单独报告暂停。

## 2026-11-13 — 0.1.5 / versionCode 6

- 执行来源：`1f0c014`，详见 `README.md`、`docs/md3-ui-delivery-2026-11-12.md`
- 功能名称：Material 3 界面统一与可靠性
- 修改内容：官方稳定版 Material 3 1.4.0；录制有界写入队列；USB 连接隔离旧读取任务；远程控制帧与采样帧分队列；回放按测量时间排序，不改写原始 CSV。
- 验证结果：以该版发布说明为准。不代表室外实车验收通过。
- 兼容性影响：原始采样、坐标约定与成绩输入口径保持。

## 2026-11-11 — 0.1.4 / versionCode 5

- 执行来源：`a182a95` 及此前 `1108392`、`18cf698`、`47e15ce`、`781b324`、`3960684`、`5b5e992` 等，详见 `README.md`、`docs/vehicle-attitude-delivery.md`
- 功能名称：外接 UM982、历元合并、连续计分、回放归零、实验姿态估算、GNSS 节奏刷新
- 修改内容：主界面切换手机 GNSS / 外接 UM982；NMEA 同一历元一次定位；横屏半开改回一列四个；计分默认连续，新录制清空成绩表；回放通道与安装归零；实验性 `estimate.*` 姿态管线；CH344Q 全口监听与 CH340 直连。
- 验证结果：当时记录 143 项单元测试及构建、Lint 通过；Note20 Ultra 有安装与界面验证。合成测试与手机界面验证不能替代室外实车验收。GNSS 定量补偿未启用。
- 兼容性影响：远程新增 `estimate.attitude/status`，不替换既有 `vehicle.attitude`；Windows 显示未实机验收。应用不发送 UM982 配置。

## 2026-11-10 — 0.1.3 / versionCode 4

- 执行来源：`a8de9f2`、`3a92dfb`，详见 `README.md`、`docs/GROKBOT_HANDOFF_2026-11-10_SMART_LAN.md`
- 功能名称：Smart LAN 发现与直连
- 修改内容：Android 作为 WebSocket 服务端，局域网发布发现信息；支持无 token 的 `device.hello`（当前单人使用模式）；一键复制 `cmts://` 连接串。
- 验证结果：以当时交接与 README 为准。订阅状态不代表已验证传输质量。
- 兼容性影响：协议版本仍校验；公网和多设备并行采集尚未实现。

## 2026-11-06 — 0.1.2 / versionCode 3

- 执行来源：`001e497`，详见 `README.md`、`docs/GROKBOT_HANDOFF_2026-11-06_RELEASE.md`
- 功能名称：实时预览、姿态归零/坡度、回放曲线与离线计分、KML 速度/海拔
- 验证结果：构建 / 单测 / Lint 见该版 Release；室外实车验收未宣称通过。

## 2026-11-05 — 0.1.1 / versionCode 2

- 执行来源：`373ffa7`，详见 `README.md`
- 功能名称：Performance Test 模块，横竖屏面板分页，高德/Google 底图切换
- 验证结果：性能测试引擎单测与构建已通过；未宣称室外实车验收通过。质量门限为调参建议。

## 2026-11-04 — 0.1.0 初始导入

- 执行来源：`03146b3`，详见 `docs/GROKBOT_HANDOFF_2026-11-04.md`
- 功能名称：Standalone MVP（采集、地图仪表、车辆坐标、VBO/原始导出、基础单测）
