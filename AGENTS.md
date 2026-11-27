# CMTS Android — Agent 规则

范围：仅本仓库，本地目录为 `CMTS-Android`。**不要**修改同级 `CMTS-Win` 中的 Windows 客户端。**不要**刷写 RK3588 或改动供应商系统镜像。

## 开始任务前必读

1. 本文件 `AGENTS.md`
2. `PROJECT_STATE.md` — 当前有效状态（只记录已对照代码核实的内容）
3. `docs/agent/HANDOFF.md` — 最近交接
4. 按任务需要阅读 `docs/agent/ARCHITECTURE.md`、`docs/agent/KNOWN_ISSUES.md`、`docs/agent/CHANGELOG.md`
5. `CONTEXT_INDEX.md` — 文档与代码入口（含历史交接索引）
6. `docs/adr/` — 已锁定决策，不得悄悄推翻
7. 检查当前 Git 分支、工作区修改和最近提交，并以代码为准；文档与代码不一致时先核实代码和已有验证记录，再报告差异

既有 `docs/GROKBOT_HANDOFF_*.md` 等历史交接必须保留，作为背景与证据来源，**不得覆盖或删除其中仍有效的内容**。新的当前状态以 `PROJECT_STATE.md` 与 `docs/agent/` 为准。

## 硬性边界

- 不改 Windows 代码；Windows 只作只读参考。跨端通信协议的 SSOT 在 Windows 仓库。
- 不刷写 RK3588、不删除供应商 APK、不改设备树/系统镜像。
- 未经用户确认，不得更换底图提供方（Google 瓦片是既有选择；**不宣称**已获商用授权）。
- 不得把模拟器或仿真数据当作手机或 RK 验收。
- 不得为无 GNSS 的 IMU-only VBO 编造定位坐标。
- 优先在当前 APK 与源码上继续，而不是重做已落地工作。
- 产品、网络、授权类选型先问用户。
- 未经用户明确授权，不得提交、合并或推送。
- 禁止 `git reset --hard`、`git clean`、强制推送等可能破坏他人工作区的操作，除非用户明确授权且已确认不会丢掉其他 Agent 的未提交修改。

## 构建（PowerShell，仓库根目录）

```powershell
$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat --offline --console=plain assembleDebug testDebugUnitTest lintDebug
```

需要代理时：`127.0.0.1:7897`（见 `scripts/Verify.ps1 -ProxyHost ...`）。

## Git / 机密

永远不要提交：`local.properties`、`.toolchain/`、`evidence/`、`*.apk`/`*.aab`、`.idea/`、`.kotlin/`、构建产物。

## 产品事实（不要在文档里“纠正”）

- UI：Jetpack Compose；地图在浮动底栏下方；深色 + 香槟金 `#D4AF37`。
- 车辆坐标（屏幕朝上，手机顶部朝车头）：`X=device.Y`，`Y=-device.X`，`Z=device.Z`。
- IMU 请求周期约 10000 µs（约 100 Hz 意图）；手机实测平均约 106.7 Hz — 不是硬 100 Hz，也不是 GNSS 速率。
- VBO 是 GNSS 定时行，车辆 IMU 取不晚于该行且不超过 200 ms 的样本；完整保真正文在 CSV。

## 工作记录维护

本地文件是项目状态的主要事实来源，不得只靠聊天记忆。所有记录用 Markdown、中文撰写，技术标识符保留原文。必须基于真实代码和已有验证结果，禁止虚构完成或验收。

完成一个独立功能、缺陷修复或重要重构后，主动更新：

1. 核对本次代码变更，执行可用的编译、测试或静态检查。
2. 如实记录验证结果；未做的检查要写原因。不得把“代码已修改”写成“功能已验证”。
3. 更新 `docs/agent/CHANGELOG.md`、`PROJECT_STATE.md`、`docs/agent/HANDOFF.md`。
4. 架构或接口变化时更新 `docs/agent/ARCHITECTURE.md`；问题状态变化时更新 `docs/agent/KNOWN_ISSUES.md`。
5. 共享文档冲突时保留双方有效记录再合并，不得简单覆盖。
6. 修改与 Windows 的通信接口时，必须写明协议版本、数据结构、兼容性影响，以及另一端是否需要同步适配。
7. 在最终回复中说明文档是否成功更新；无法写入时明确报告，不得假装已同步。

`docs/agent/CHANGELOG.md` 按时间倒序追加，不得删除已有有效记录。`PROJECT_STATE.md` 只保留当前有效状态，不堆历史流水账。

## 多 Agent 并行

- 动手前检查其他 Agent 已记录的工作范围与工作区是否有未提交修改。
- 不得擅自覆盖其他 Agent 尚未提交或尚未完成的修改。
- 同一模块有并行风险时先报告冲突；尽量使用独立 Git 分支或 Git Worktree。
- 禁止把未验证、未提交或只存在于其他工作区的变更写成已经集成完成。

## 任务结束回复格式

任务： 本次开发目标

完成情况： 已完成 / 部分完成 / 未完成

主要修改： 功能、模块及关键文件

验证情况： 已通过的检查、未执行的检查及原因

遗留问题： 未解决问题及风险

文档同步： 已更新的文档及失败项

Git 状态： 当前分支、提交情况、未提交修改

后续建议： 下一步优先任务
