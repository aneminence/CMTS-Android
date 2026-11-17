# 跨 Agent 交接

更新：2026-11-17 10:29 Asia/Shanghai，CMTS · Android开发

## 最近一次任务目标

按用户在群里给出的多 Agent 文档规范，以及项目经理「先落盘、未经授权不要提交」的要求，在 CMTS Android 仓库落地文档结构：根目录 `AGENTS.md`、`PROJECT_STATE.md`，以及 `docs/agent/` 下 `CHANGELOG.md`、`ARCHITECTURE.md`、`HANDOFF.md`、`KNOWN_ISSUES.md`。合并已有有效内容，状态与 `master` 代码一致，并区分已完成 / 未验证。

## 实际完成内容（已完成）

- 对照 P16 上 `D:\CMTS\android` 的 `master` @ `0abde54`（与 `origin/master` 一致，落盘前工作区干净）以及 README、既有交接、ADR 写好上述文件。
- `AGENTS.md` 保留原硬性边界、构建方式和产品事实，并入任务前后强制流程与文档维护规则。
- `CONTEXT_INDEX.md` 改为以 `PROJECT_STATE.md` 与 `docs/agent/` 为当前入口，历史 `GROKBOT_HANDOFF_*` 仍保留并被索引。
- 已在文档中记录：`docs/architecture.md` 关于 UM982 未接入的表述已过时。

## 尚未完成的部分

- 未修改功能代码。高德轨迹锯齿按用户 2026-11-17 意见保留在已知问题中，优先级低，暂不修复。
- 未重新执行 Gradle 构建或实机验收。

## 关键代码位置

- 工程根：`D:\CMTS\android`
- 采集与外接 GNSS：`app/src/main/java/com/cmts/mobile/CaptureService.kt`、`NmeaParser.kt`
- 地图：`MapScreen.kt`（谷歌 / WGS84）、`AmapScreen.kt`（高德显示转换）
- 录制暂停与长按停止：`CaptureService.kt`、`MainActivity.kt`
- 远程：`app/src/main/java/com/cmts/mobile/remote/`
- 历史交接仍在 `docs/GROKBOT_HANDOFF_*.md`，其中最新功能交接为 `docs/GROKBOT_HANDOFF_2026-11-16.md`

## 遗留问题

见 `docs/agent/KNOWN_ISSUES.md`。与最近用户反馈直接相关的是：外接 20 Hz 轨迹在高德底图上有锯齿，同一轨迹在谷歌卫星图上平滑；不要把该现象写成 UM982 或 20 Hz 数据本身的问题，也不要用滤波改原始定位来掩盖。

0.1.6 的长按停止与暂停录制已合入并已装到 S23 Ultra，但代理未做实机操作验收。

## 后续 Agent 接手注意事项

- 历史交接文件不要删、不要整文件覆盖。当前状态以 `PROJECT_STATE.md` 为准。
- 工作区现在应只有本轮文档改动；动手前再跑一次 `git status`。
- 不要把文档落盘写成功能已验证或已经发布。
- 协议 SSOT 在 Windows；改远程字段先看兼容性。
- RK3588 系统重力方向问题不要在本应用里用 `wm size` / `wm density` 或刷机处理。

## 推荐的下一步行动

1. 请用户实机确认 0.1.6 停止/暂停行为。
2. 高德锯齿优先级低，暂不处理。
