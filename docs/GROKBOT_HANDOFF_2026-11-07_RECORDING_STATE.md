# GROKBOT HANDOFF — 录制状态机对照（2026-11-07）

> Android 仓 `D:\CMTS\android`。Windows 仓只读。本稿管 **RecordingState / 服务侧录制边界**。跨端 **协议 v0.1 SSOT** 见 Win：https://github.com/aneminence/Chassis-Master-Test-Suite/blob/docs/remote-device-p0/docs/remote-device-p0-win.md （分支 `docs/remote-device-p0`）。

## 1. 目标录制状态（产品）

| 状态 | 含义 |
|------|------|
| IDLE | 未采集、无 hold |
| INITIALIZING | 正在拉起前台服务 / 权限 / 监听 |
| READY | 服务与传感器就绪，可开始录制或性能测试 |
| RECORDING | 正在写会话文件（CLIENT_RECORD） |
| STOPPING | 收到停止，正在收尾写盘 |
| SAVING | 关闭 CSV / 写 metadata |
| ERROR | 权限、空间、传感器等失败 |

性能测试（CLIENT_PERF）可与普通录制并行；产品状态机仍应以「是否在写会话」为主轴，性能待命是叠加 hold。

## 2. 现状映射（代码）

| 目标态 | 现状近似 |
|--------|----------|
| IDLE | `!Live.serviceAlive` 或服务在但仅 preview、无 record/perf hold |
| INITIALIZING | `onStartCommand` → `acquireClient` 过程中（无显式枚举） |
| READY | `Live.serviceAlive` 且 listeners 已起；`perfHold` 可已占用但引擎未 Arm；或 preview 已绑定 |
| RECORDING | `recordHold == true` 且 `Live.running == true`（写 CSV） |
| STOPPING/SAVING | `stopRecordingSession()` 内（无独立枚举） |
| ERROR | `fail(e)` → `Live.status` 文案；无 ERROR 枚举 |

叠加：

- `CaptureService.CLIENT_RECORD` / `CLIENT_PERF`：引用计数式 hold；最后一方 release 才 `stopSelf`。
- `Live.running`：普通录制会话是否在写盘。
- `Live.serviceAlive`：服务 `onCreate`/`onDestroy`。
- `PerformanceController.holdCapture` / `armed`：性能段已 Arm，直到全部终态或取消。
- `MainActivity.toggleRecording()`：只 acquire/release **CLIENT_RECORD**；性能 hold 中停止录制不会拆 CLIENT_PERF。

## 3. Intent / 入口对照

### 现有

| 入口 | 行为 |
|------|------|
| `CaptureService.acquireIntent(ctx, CLIENT_RECORD)` | 前台服务 + 开写会话 |
| `CaptureService.releaseIntent(ctx, CLIENT_RECORD)` | 停写会话；若无 perf hold 则停服务 |
| `CaptureService.acquireIntent(ctx, CLIENT_PERF)` | 前台服务 + GNSS→`PerformanceController.onRawGnss`；**不写**会话 CSV |
| `CaptureService.releaseIntent(ctx, CLIENT_PERF)` | 去掉 perf hold；若无 record hold 则停服务 |
| `MainActivity.toggleRecording()` | 上述 RECORD 起停 |
| `PerformancePane` Arm | `onEnsureCapture()` 后 `PerformanceController.arm(...)` |
| `PerformanceController.cancel()` / 段全部终态 | `holdCapture=false`；服务侧观察后 release CLIENT_PERF |

### 建议服务侧伪 API（尚未实现；供 Win 草案对齐）

```
record.start()   -> acquire CLIENT_RECORD   // 幂等
record.stop()    -> release CLIENT_RECORD   // 幂等；不影响 CLIENT_PERF
record.status()  -> { state, recordHold, perfHold, running, activeSession? }

perf.arm(configs) / perf.cancel()  // 仍走 PerformanceController；Core 持有状态机后可转发
```

首版 Win 范围：**手动 IP + 实时订阅 + 录制启停**。JSON 字段以 Win 协议 v0.1 SSOT 为准，本页不重复。

## 4. 本提交小改（性能 hold 生命周期）

**根因：** `watchdogTick` 与「hold 结束后 release CLIENT_PERF」原先挂在 `PerformancePane` 的 `LaunchedEffect` 上；`VerticalPager` 卸页会取消协程 → 超时不跑、CLIENT_PERF 可能挂死。

**做法：**

1. `PerformanceController`：Arm 后在主线程 Handler 每 250 ms 调 `watchdogTick`，`releaseLocked` 时移除回调（不依赖 Compose）。
2. `CaptureService`：持有 CLIENT_PERF 时在采集线程观察 `holdCapture`；曾见 hold 再变 false 则自行 `releaseClient(CLIENT_PERF)`。
3. `PerformanceScreen`：删除上述 `LaunchedEffect`；UI/香槟金/横竖交互不变。

## 5. 明确未做（本波）

- 开机自启 / 无屏热点 / 实体 LOG / GPIO
- USB / UM982 收进 Core 统一状态机
- WebSocket 服务端与 JSON 定稿
- 把目标七态落成真实枚举并驱动通知文案（仅文档对照）
- 实车验收宣称

## 6. 与 Win 衔接

- **协议 SSOT（v0.1）**：[remote-device-p0-win.md](https://github.com/aneminence/Chassis-Master-Test-Suite/blob/docs/remote-device-p0/docs/remote-device-p0-win.md)（`8787/cmts/v1`、无 TLS、配对 token、双时间戳、valid/null、seq 进程级、七态 record、RTK→status）。本页不另写 JSON，避免双源。
- Android：采集执行层；Win：旁路 `CMTSRemoteDataSource`，不改 GSpot 协议。
- 录制启停应对同一七态状态机；禁止多控制端重复开写损坏文件（实现待状态机落地后强化）。

## 7. 验证提示

```powershell
$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat --offline --console=plain assembleDebug testDebugUnitTest
```

装机后：Arm 多段 → 滑走性能页 → 确认仍会出分/超时/取消后前台通知与 CLIENT_PERF 会释放。室外实车仍待路上验收。
