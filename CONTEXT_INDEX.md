# CONTEXT_INDEX — CMTS Android

单一索引。当前有效状态以 `PROJECT_STATE.md` 和 `docs/agent/` 为准；历史交接保留，不要另起一套互相覆盖的百科。

## 当前入口（2026-11-17 起）

| 文档 | 职责 |
|------|------|
| [AGENTS.md](AGENTS.md) | Agent 规则、硬性边界、文档维护与交付格式 |
| [PROJECT_STATE.md](PROJECT_STATE.md) | 当前版本、已实现 / 未验证、主要问题和下一步 |
| [docs/agent/HANDOFF.md](docs/agent/HANDOFF.md) | 最近交接：已完成 / 未完成 / 接手注意 |
| [docs/agent/CHANGELOG.md](docs/agent/CHANGELOG.md) | 功能与重要修改（时间倒序） |
| [docs/agent/ARCHITECTURE.md](docs/agent/ARCHITECTURE.md) | 对照当前代码的架构与接口约定 |
| [docs/agent/KNOWN_ISSUES.md](docs/agent/KNOWN_ISSUES.md) | 已知问题与技术债 |

最新已合入功能的历史交接：[2026-11-17 开发进度](docs/GROKBOT_HANDOFF_2026-11-17.md)（0.1.7 记录/行程/GG 图与成绩累计）。

## 历史交接（保留，勿覆盖）

| 文档 | 角色 |
|------|------|
| [docs/GROKBOT_HANDOFF_2026-11-17.md](docs/GROKBOT_HANDOFF_2026-11-17.md) | 0.1.7 记录、行程、GG 图 |
| [docs/GROKBOT_HANDOFF_2026-11-16.md](docs/GROKBOT_HANDOFF_2026-11-16.md) | 0.1.6 录制控制 |
| [docs/GROKBOT_HANDOFF_2026-11-10_SMART_LAN.md](docs/GROKBOT_HANDOFF_2026-11-10_SMART_LAN.md) | Smart LAN |
| [docs/GROKBOT_HANDOFF_2026-11-09_REMOTE_LAN_P0.md](docs/GROKBOT_HANDOFF_2026-11-09_REMOTE_LAN_P0.md) | 远程局域网 P0 |
| [docs/GROKBOT_HANDOFF_2026-11-07_RECORDING_STATE.md](docs/GROKBOT_HANDOFF_2026-11-07_RECORDING_STATE.md) | 录制状态机 |
| [docs/GROKBOT_HANDOFF_2026-11-06_RELEASE.md](docs/GROKBOT_HANDOFF_2026-11-06_RELEASE.md) | 0.1.2 发布 |
| [docs/GROKBOT_HANDOFF_2026-11-06_BACKLOG.md](docs/GROKBOT_HANDOFF_2026-11-06_BACKLOG.md) | 当时待办 |
| [docs/GROKBOT_HANDOFF_2026-11-05.md](docs/GROKBOT_HANDOFF_2026-11-05.md) 及同日 PERFORMANCE / landscape / PERF_RESULT 各篇 | USB、锁屏、高德/16KB、性能测试计划等 |
| [docs/GROKBOT_HANDOFF_2026-11-04.md](docs/GROKBOT_HANDOFF_2026-11-04.md) | 初始 MVP 交接 |

[docs/tasks.md](docs/tasks.md) 仍列出 2026-11-04 起的开放项，关闭前先对照当前代码，不要盲目视为最新待办清单。

## 决策（ADR）

| ADR | 主题 |
|-----|------|
| [docs/adr/ADR-001.md](docs/adr/ADR-001.md) | Jetpack Compose UI |
| [docs/adr/ADR-002.md](docs/adr/ADR-002.md) | 车辆坐标系 |
| [docs/adr/ADR-003.md](docs/adr/ADR-003.md) | Google 瓦片 URL（不宣称商用授权） |

## 仍有用的专题文档

| 文档 | 说明 |
|------|------|
| [docs/data-format.md](docs/data-format.md) | CSV / VBO / metadata 通道 |
| [docs/architecture.md](docs/architecture.md) | 旧架构短文，部分过时，以 `docs/agent/ARCHITECTURE.md` 为准 |
| [docs/development.md](docs/development.md) | 开发笔记 |
| [docs/toolchain.md](docs/toolchain.md) 及 upgrade/validation | 工具链历史 |
| [docs/windows-compatibility.md](docs/windows-compatibility.md) | Windows 对读注意事项（导入仍未完全核对） |
| [docs/rk3588-plan.md](docs/rk3588-plan.md) | 未来 RK 计划；未验收 |
| [docs/vehicle-attitude-delivery.md](docs/vehicle-attitude-delivery.md) | 实验姿态交付与验证边界 |
| [docs/REMOTE_WS_V0.1_NOTES.md](docs/REMOTE_WS_V0.1_NOTES.md) | 远程协议笔记 |
| [docs/amap-map.md](docs/amap-map.md) | 高德切换与显示转换 |
| [docs/usb-debug.md](docs/usb-debug.md) | USB 调试记录 |

## 过时叙述（保留文件，不再当作当前状态）

`docs/map-dashboard-progress.md`、`docs/dashboard-mapping.md`、`docs/test-report.md` 已被后续交接取代。

## 代码地图（app）

| 区域 | 文件 |
|------|------|
| 入口 / 面板 | `MainActivity.kt` |
| 地图 | `MapScreen.kt`、`AmapScreen.kt`、`MapMath.kt`、`TrackRendering.kt` |
| 采集 | `CaptureService.kt` |
| 定位 / NMEA / USB | `GnssSource.kt`、`NmeaParser.kt`、`UsbSerialHub.kt`、`UsbDebugActivity.kt` |
| 姿态 / 车辆 | `Attitude.kt`、`VehicleAttitudeEstimator.kt`、`AttitudePipeline.kt` |
| 仪表 / 性能 / GG | `Dashboard.kt`、`GgPlot.kt`、`Model.kt` |
| 行程计量 | `TripMetrics.kt`、`SessionCatalog.kt` |
| 记录 / VBO / 回放 | `RecordsScreen.kt`、`Vbo.kt`、`Kml.kt`、`Replay.kt` |
| 远程 | `remote/*` |
| 测试 | `app/src/test/java/com/cmts/mobile/*Test.kt` |

## 仅本地（gitignored）

`evidence/`、`local.properties`、`.toolchain/`、`**/build/`、`*.apk`

## 仓库外

Windows：同级目录 `../CMTS-Win`（只读参考）。
