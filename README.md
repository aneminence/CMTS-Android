# CMTS Android

**Chassis Master Test Suite — 手机端 Standalone MVP**  
当前版本：**0.1.3**（`versionCode` 4）· 包名 `com.cmts.mobile` · 显示名 CMTS

手机端 GNSS + IMU 采集、Compose 地图仪表、会话导出、姿态/坡度、回放曲线，以及独立的 **Performance Test（车辆性能测试）**（含回放离线计分）模块。  
Windows 客户端（`Chassis Master Test Suite`）为本仓库只读参考，**不在此仓库修改**。

Agent / 开发入口：[AGENTS.md](AGENTS.md) · [CONTEXT_INDEX.md](CONTEXT_INDEX.md) · 最新交接见 `docs/GROKBOT_HANDOFF_2026-11-06_RELEASE.md`。

---

## 项目简介 / Overview

CMTS Android 面向底盘与动力学路测场景：在真机上以前台服务采集原始 GNSS/IMU（及气压等），地图上叠加轨迹与浮动仪表面板，支持录制、回放曲线、VBO/KML/CSV 导出；0.1.1 起含可多选阈值段的性能测试；**0.1.2** 补齐实时预览、姿态归零/起步标定、俯仰坡度、轨迹选色、回放离线计分与带速度/海拔的 KML。

**验证边界（请务必阅读）**

- 性能测试引擎单测与构建已通过；**未宣称室外实车验收通过**。质量门限为调参建议，不是已验证规格。
- 阈值穿越使用相邻有效样本**线性插值**，制动距离为速度折线**梯形积分**；插值只用于估计穿越时刻，**不宣称提高了 GNSS 测量精度**。
- 成绩计算使用原始 GNSS 速度与采样时间戳，**禁止**用 UI 刷新数据或历史回放抽样当成绩输入。
- Google 地图瓦片授权与高德 Key 仅限本机配置；发布级授权仍为开放项。

---

## 主要功能 / Features（0.1.2 已落地）

### 地图与界面

- **Google 卫星图** / **高德标准图**切换（高德 Key 在本机 `local.properties` 的 `AMAP_API_KEY`，勿提交）。
- 深色 + 香槟金（`#D4AF37`）视觉；地图全屏铺底 + 浮动面板；**轨迹颜色可自定义**（12 色色板 + RGB，实时/历史共用并持久化）。
- **竖屏**：底部 BottomSheet；半开 / 全开后左右翻页（仪表、成绩、姿态等）；上拉全屏后可左右滑切换分页，底部小圆点指示。
- **横屏**：窄栏 / 展开栏上下翻页；窄栏可看仪表、成绩与俯仰/侧倾；旋转保留当前页；系统栏安全区（观感仍以真机用户验收为准）。
- 竖屏 / 横屏**启动图**（`SplashActivity` + `drawable-nodpi` / `drawable-land-nodpi`）。

### 采集与传感器

- 前台 `CaptureService`：GNSS、加速度、陀螺、旋转矢量；车辆坐标映射（见 ADR-002：`X=device.Y`，`Y=-device.X`，`Z=device.Z`）。App 可见时即可绑定预览刷新仪表；显式开始记录才创建会话。
- Dashboard：**去重力线加速度**显示；姿态 / 陀螺**归零与复位**（仅显示偏置，CSV 仍写原始含重力加速度）；可选**直线向前起步标定**车身 X 正向。
- **车辆姿态页**：可转动左视图（俯仰）与后视图（侧倾），旁侧实时角度；**坡度**按 `100 × tan(俯仰°)`（车头向下为正俯仰约定，上坡为正）。
- 卫星可见 / 参与定位数；**气压**（`TYPE_PRESSURE`，摘要与 CSV；旧会话缺通道显示 `—`）。
- 高度标签为 GNSS 椭球相关高度，**不直接宣称平均海平面海拔**。

### 录制 · 回放 · 导出

- 会话录制与原始 CSV / 元数据；历史列表紧凑行；左滑删除（保护当前写入目录）。
- 本地会话回放（播放 / 暂停、进度、0.5×–8×）；**回放曲线**（速度 / 俯仰 / 侧倾 / 三轴加速度、时间窗与共享游标）；与 Live 隔离。外部「打开 VBO」仍为文件页预览，**不等于**主界面通用回放。
- **回放离线计分**：设定速度边界后逐段出结果；读原始 `samples.csv` phone.gps，复用性能引擎，写 `result-vN.json`（不覆盖原始）。
- 导出：**VBO**、**KML**（`gx:Track` + 原始 GNSS 海拔 / 速度 m/s / 水平精度；旧记录可从手机原始会话重新导出）、**CSV**（原样 `samples.csv`）。ZIP 入口已移除，原始文件仍保留。

### 局域网连接（0.1.3）

- Android 作为 WebSocket 服务端，在局域网发布 CMTS 发现信息；Windows 可自动发现并双击连接。
- 支持无 token 的 `device.hello`，协议版本仍会校验；同一局域网可访问的客户端均可连接，这是当前单人使用模式。
- 设备型号单独提供；例如 SM-N9860 显示为 Samsung Galaxy Note20 Ultra 5G。连接二维码只含设备与网络元数据。
- Windows 目前只支持同时连接一台手机；公网和多设备并行采集尚未实现。

### USB 调试

- 独立 `UsbDebugActivity`：WCH UART（`CH34XUartDriver.jar`）枚举 / 授权 / 波特率 / HEX·ASCII / 保存。
- **未**发送设备配置协议；UM982 解析、前台常驻 USB、真机 OTG 插拔**尚未验收**。

### Performance Test（性能测试）

- 四种段类型可**多选**：起步加速 `0→X`、区间加速 `X→Y`、区间制动 `X→Y`、停车制动 `X→0`；阈值用户可改。
- 一次跑程（如 0–100–0）可同时输出多项成绩；表列含测试时间、类型、**时间**与**距离**（制动类）。
- **待命**后自动判起止；质量标识（PASS/WARN/FAIL）与原因；历史尝试与**重算**（新 `result-vN`，不覆盖原始成绩）。
- 纯 Kotlin 引擎 `perf-engine-1.0.0`，实时与离线重算共用；`SpeedSource` 预留 UM982 / CMTS 主机适配（当前速度源为手机 GNSS）。
- 静止 / 异常过滤 / 缺样 / 超时 / 换源等规则见引擎与单测；零速与门限参数随尝试保存。

---

## 使用教程 / How to use

### 安装

1. 获取 debug APK（自行 `assembleDebug`，或见 [Releases](https://github.com/aneminence/CMTS-Android/releases) 说明）。
2. 手机开启「安装未知应用」；若系统用地图/网盘打开 `.apk`，请改用**软件包安装程序**。
3. 覆盖安装（`adb install -r`）可保留应用数据；**安装前确认未在录制**。

### 权限与省电

- 需要**精确定位**；性能测试待命也会拉起采集持有。
- 三星等机型省电可能导致锁屏后唤醒锁受限、CSV 停写；长期路测请按交接说明检查省电 / 电池优化（已验证场景不等于全部机型验收）。

### 竖屏面板

1. 半开 / 全开后左右翻页查看仪表、性能测试、姿态等。
2. 底部分页小点指示当前页。
3. 内容区优先滚动；滚到边界后再拖面板收起（手感以真机为准）。

### 横屏面板

1. 旋转后面板在物理底边一侧；拖**把手** Peek → 半开 → 全屏。
2. 全屏后**上下滑**切换 Dashboard / 性能测试。
3. Dashboard 横屏为紧凑模块布局并可横向浏览内容（与竖屏上下滚对应「轴转 90°」产品意图；布局仍可能继续打磨）。

### 普通录制与回放

1. 授权定位后点开始记录；结束后在「记录与文件」查看会话。
2. 点已保存会话进入主界面轨迹 + Dashboard 回放；导出用行内 VBO / KML / CSV。

### 性能测试

1. 全屏面板滑到 **性能测试** 页。
2. 勾选需要的测试段，填写起/终速度（km/h）；两个加速一行、两个制动一行。
3. 点 **待命**（可与普通录制独立持有 GNSS；停止录制不会无提示杀掉性能 hold）。
4. 按段自动起止；下方成绩表新增行（时间、类型、成绩含距离/时间）。
5. 点历史行可看详情并**重算**（新版本结果，不覆盖原始）。

界面文案亦提示：**原始 GNSS 速度；插值不提高精度。**

---

## 构建说明 / Build

环境：Android Studio（建议捆绑 JBR）、AGP / Gradle 以工程为准；`compileSdk`/`targetSdk` 35，`minSdk` 26；Kotlin / Compose 见 `gradle` 配置。

PowerShell，仓库根目录：

```powershell
$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat --offline --console=plain assembleDebug testDebugUnitTest lintDebug
```

需要拉依赖时可用本机代理，例如 `127.0.0.1:7897`（见 `scripts/Verify.ps1 -ProxyHost ...`）。官方 Gradle Wrapper 已包含。

调试安装示例：

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
```

可选：`scripts/check_elf_alignment.py` 检查 64 位 SO LOAD 对齐（高德 16KB 相关；**不等于**已在真 16KB 页手机上验收）。

### 不纳入 Git

`evidence/`、`local.properties`、`.toolchain/`、构建产物、`*.apk` / `*.aab` 等已 gitignore，请本地重建。

---

## 功能更新记录 / Changelog

### 0.1.2 — 2026-11-06

- 实时预览绑定采集服务；显式开始记录才建会话；记录左滑删除。
- 姿态归零、可选起步标定；左视俯仰 / 后视侧倾可视化；俯仰换算坡度（`100 × tan`）。
- 横竖屏翻页与安全区打磨；轨迹颜色自定义并持久化。
- 回放曲线（多通道、游标、时间窗）；回放多边界离线计分（原始 GNSS，`result-vN`）。
- KML `gx:Track` 导出速度与海拔；**提醒：旧记录请从手机原始会话重新导出**。
- 版本号：`versionName 0.1.2` / `versionCode 3`。构建 / 单测 / Lint 见本版 Release；**室外实车验收未宣称通过**。

### 0.1.1 — 2026-11-05

- **Performance Test**：多选段、自定义阈值、待命、自动起止、成绩表（时间/类型/时间与距离）、质量标识、尝试目录持久化、历史与重算（`result-vN`）；引擎 `perf-engine-1.0.0` + 单元测试。
- **明确声明**：插值 / 积分用于成绩估计，**不宣称提高 GNSS 精度**；**未做实车验收**；门限为调参建议。
- Dashboard 与性能测试同级分页：竖屏全屏后左右滑、横屏上下滑；半开仅摘要；底部分页小点。
- 横屏面板跟物理底边侧拉；竖/横启动图；地图安全区相关调整。
- 传感器：去重力线加速度显示、姿态/陀螺归零、气压进摘要与 CSV。
- 地图：Google / 高德切换、有界 Google 瓦片内存缓存、高德依赖升级与 16KB 对齐检查脚本。
- 回放与导出：本地会话回放倍速；KML/CSV/VBO；USB 调试页（WCH）。
- 版本号：`versionName 0.1.1` / `versionCode 2`。

### 0.1.0 — 初始导入

- Standalone MVP：前台 GNSS+IMU 采集、Compose 地图 + 浮动底栏、车辆坐标、VBO/原始导出、基础单测与工具链文档。
- 详见首次提交与 [docs/GROKBOT_HANDOFF_2026-11-04.md](docs/GROKBOT_HANDOFF_2026-11-04.md)。

---

## 文档索引

| 文档 | 说明 |
|------|------|
| [AGENTS.md](AGENTS.md) | Agent 硬边界与构建 |
| [CONTEXT_INDEX.md](CONTEXT_INDEX.md) | 文档与代码地图 |
| [docs/GROKBOT_HANDOFF_2026-11-05_PERFORMANCE_PLAN.md](docs/GROKBOT_HANDOFF_2026-11-05_PERFORMANCE_PLAN.md) | 性能设计与进度（优先） |
| [docs/GROKBOT_HANDOFF_2026-11-05_landscape.md](docs/GROKBOT_HANDOFF_2026-11-05_landscape.md) | 横屏交接与产品规则 |
| [docs/GROKBOT_HANDOFF_2026-11-05.md](docs/GROKBOT_HANDOFF_2026-11-05.md) | USB / 地图 / 回放等 |
| [docs/adr/](docs/adr/) | ADR（Compose、车辆轴、Google 瓦片） |

---

## License / 说明

内部工程仓库；第三方 SDK（Google / 高德 / WCH 等）遵循各自条款。Key 与授权勿写入公开文档或提交。
