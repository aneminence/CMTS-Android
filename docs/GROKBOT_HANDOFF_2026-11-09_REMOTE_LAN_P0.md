# GROKBOT HANDOFF — 遥控局域网 P0 汇总（2026-11-09）

> **交给 Codex / 下一任开发。** 由 CMTS · 项目汇总 Win + Android + 项目侧口径。
> 本机路径建议：`D:\CMTS\docs\GROKBOT_HANDOFF_2026-11-09_REMOTE_LAN_P0.md`（本文）。
> Android 侧历史交接样例：`D:\CMTS\android\docs\GROKBOT_HANDOFF_*.md`（Codex/Grokbot 惯例）。

## 0. 一句话

跨端遥控采集局域网 P0 已合入两端默认分支并推 GitHub：手机当 WS 服务端，Win 订阅/录制/曲线/仪表；只展示车端通道；归零与 Dashboard/通道记忆已修。公网搁置。局域网下一阶段等产品开口再开分支。

## 1. 仓库 tip（已推 GitHub）

| 端 | 仓库 | 默认分支 | tip | 本机路径 |
|----|------|----------|-----|----------|
| Win | https://github.com/aneminence/Chassis-Master-Test-Suite | `main` | **`93ba0d2`**（单测约 165 绿） | `D:\CMTS\Chassis Master Test Suite` |
| Android | https://github.com/aneminence/CMTS-Android | **`master`**（无 `main`） | **`6b2893f`** | `D:\CMTS\android` |

说明：
- Win 遥控合入点曾为 `210f4fe`；随后又合了两条历史 fix → 现 tip `93ba0d2`。
- Android `feature/remote-ws-v0.1` 已 ff 进 `master`，与 `6b2893f` 同 tip（分支保留未删）。
- 先 `git pull` 上述 tip，再从默认分支开新功能分支。

## 2. 协议与架构（v0.1，冻 LAN）

- 传输：`ws://手机IP:8787/cmts/v1`（无 TLS）；配对 token。
- 角色：Android = 服务端；Win = `CMTSRemoteDataSource` 客户端。
- 流程：hello → subscribe（maxHz≈100）→ `stream.sample`；录制启停；`device.status`。
- 连接串：Android 一键复制 `cmts://IP:8787/cmts/v1?token=…`；Win 粘贴 + 上次成功预填。
- SSOT（Win）：`docs/remote-device-p0-win.md`；缺口备忘：`docs/remote-device-channels-gap.md`。
- Android 笔记：`docs/REMOTE_WS_V0.1_NOTES.md`。
- **不做**：改 GSpot；公网/中继/穿透（另立里程碑，倾向中继）。

## 3. 已落地（按端）

### 3.1 Android（`master` @ `6b2893f`）

- WS 服务端 + 复制连接串。
- IMU 节奏推流（默认 ≤100Hz）；根因曾是 Live.samples/~GNSS 节流导致 Win 曲线像 1Hz。
- `stream.sample` 嵌套 **只 `vehicle.*`**（无 `phone.*`）；core 车字段保留。
- 归零 control **`stream.reset`**：`reason` = `mount_zero` | `mount_zero_clear`；payload 含 `mountZeroEnabled`、`zeroedAtElapsedNanos`、`nextSeq` 等。
- `mountZero` **开**：`vehicle.accel/gyro/attitude` 与 core `ax/ay/az/yawRateDps` 按与手机 Dashboard **同一套**变换贴零后再推；**关**：raw。
- 缺测字段用 null，不填假 0。
- Note20（SM-N9860）曾装 `6b2893f`；IP 以当时「复制连接串」为准（会变）。

### 3.2 Win（`main` @ `93ba0d2`，含遥控段 `210f4fe` + 后续 fix）

- `CMTSRemoteDataSource`：连、订、收 sample、录制启停。
- Available **只车通道**（core + `vehicle.*`）；过滤 `phone.*` / sats 展示。
- 认 `stream.reset`：清 live 曲线缓冲 + **`ClearPlotCursors()`**（修 Dashboard 冻在光标点）。
- **不做本地旋转矩阵**；轴显示跟 Android 贴零后的 `vehicle.*`。
- 曲线通道选择记忆：重连时 `SetLiveCore` 暂无 `vehicle.*` 时不再 Sanitize 冲回速度。
- 另合历史 fix：`fix/curves-elapsed-axis-crash`、`fix/trackmap-long-route-prefix`。

### 3.3 项目侧已拍板口径

- 公网「有网就能连」搁置；先 LAN。
- 通道只体现车、不体现手机。
- 归零：显式 `stream.reset` 清 Win 曲线；贴零进 sample 由 Android 做。
- 用户 GO 后已合默认分支并推 GitHub；半截分支已清（见下）。

## 4. 半截 / 归档分支

### Win

| 分支 | 处理 |
|------|------|
| `fix/curves-elapsed-axis-crash` | 已合 main |
| `fix/trackmap-long-route-prefix` | 已合 main |
| `docs/remote-device-p0` | 旧文档 tip；内容已被 Remote 文档覆盖 → **归档不合** |
| `feature/translucent-glass-theme` | 本地主题实验，无相对 main 新提交 → **不合** |

### Android

- 无半截分支。`feature/remote-ws-v0.1` 与 master 同 tip。

## 5. 明确未做 / 下一阶段候选

- 公网 / 中继 / VPN / 端口映射产品化。
- NTRIP、无屏热点、实体 LOG、开机自启等 V1.0 其余硬件向能力。
- 局域网体验增强（发现、配对、稳定性、协议增量等）——**等产品开口再开分支**。
- 实车验收主责不在本交接宣称范围。

## 6. Codex 接手检查清单

1. Win：`cd "D:\CMTS\Chassis Master Test Suite"` → `git fetch` → `git checkout main` → `git pull` → 确认 `93ba0d2`；读根 `AGENTS.md` + `docs/remote-device-p0-win.md`。
2. Android：`cd D:\CMTS\android` → `git fetch` → `git checkout master` → `git pull` → 确认 `6b2893f`；读 `AGENTS.md` + `docs/REMOTE_WS_V0.1_NOTES.md`；需要时再读历史 `docs/GROKBOT_HANDOFF_*.md`。
3. 同 Wi‑Fi 对打：手机复制连接串 → Win 粘贴 → 验 Hz≈100、无 phone 键、归零清曲线且轴跟手机、Dashboard 持续刷、通道记忆。
4. 新功能从对应默认分支开 `feature/...`；协议变更先对齐 Win SSOT；公网勿塞进 v0.1。

## 7. 关键路径速查

| 用途 | 路径 |
|------|------|
| 本文（跨端汇总） | `D:\CMTS\docs\GROKBOT_HANDOFF_2026-11-09_REMOTE_LAN_P0.md` |
| Android 历史交接（Codex 惯例） | `D:\CMTS\android\docs\GROKBOT_HANDOFF_*.md` |
| Win 协议 SSOT | `D:\CMTS\Chassis Master Test Suite\docs\remote-device-p0-win.md` |
| Android 遥控笔记 | `D:\CMTS\android\docs\REMOTE_WS_V0.1_NOTES.md` |

---
*汇总来源：2026-11-09 CMTS客户端开发群（CMTS · 项目 / WIN开发 / Android开发）同步口径。*
