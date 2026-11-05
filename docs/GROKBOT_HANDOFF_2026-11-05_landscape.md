# CMTS Android 横屏交接 — 2026-11-05（转 Codex）

> 本文件由 Grok Bot 只读核对后写出，**未再改功能代码、未装机**。  
> 接续：`docs/GROKBOT_HANDOFF_2026-11-05.md`（USB/地图/回放/归零等既有能力）。  
> 读者：Codex。用户对当前横屏 UI **不满意**，要求汇总后转你继续。

---

## 1. 工程与真机

| 项 | 值 |
|---|---|
| 工程路径 | `D:\CMTS\android` |
| 包名 / applicationId / namespace | `com.cmts.mobile` |
| 应用名 | CMTS Mobile |
| versionName / versionCode | 0.1.0 / 1 |
| minSdk / targetSdk | 26 / 35 |
| 本轮验收真机 | 三星 Note20 **SM-N9860**，adb serial **`R5CN90V1J7V`** |
| 另一台（历史交接） | S23 Ultra SM-S9180，serial `R5CW51WPC6B`（本轮横屏吐槽多在 Note20） |
| adb | `C:\Users\anemi\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| 构建示例 | cwd `D:\CMTS\android`；`$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'`；`./gradlew.bat --offline --console=plain assembleDebug`；需要联网时本机代理 `127.0.0.1:7897` |
| Windows | **勿动** Windows 工程；仅 Android |
| Git | **全部未 commit**；`master...origin/master` 仅有初始 import `03146b3` |

安装注意：装前确认未在录制；`adb -s R5CN90V1J7V install -r ...`。`evidence/`、`local.properties`、APK、build 产物勿提交。高德 Key 只在本机 `local.properties`。

---

## 2. 只读核对：当前行为要点（以磁盘代码为准）

### 2.1 `AndroidManifest.xml`
- LAUNCHER：`SplashActivity`，theme `@style/Theme.CMTS.Startup`。
- `MainActivity`：`exported=false`，theme `Theme.CMTS`，**`configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode"`**（旋转不重建 Activity，状态靠 rememberSaveable / 内存）。
- 另有 `UsbDebugActivity`、`CaptureService`（location FGS）。**未** `screenOrientation` 锁定竖屏。

### 2.2 Splash
- `SplashActivity.kt`：Compose 全屏 `Image(R.drawable.cmts_splash)`，`ContentScale.Crop`，约 **900ms** 后进 `MainActivity` 并 `finish`。
- 同名资源分流：
  - 竖屏：`res/drawable-nodpi/cmts_splash.png`
  - 横屏：`res/drawable-land-nodpi/cmts_splash.png`
- 冷启动窗：`Theme.CMTS.Startup` → `@drawable/cmts_splash_window`（bitmap fill 同一 `cmts_splash`）；`colors.xml` 里 `cmts_splash_bg=#2A1A0B`。
- 另有 `drawable-nodpi/cmts_splash_icon.png`、`cmts_icon.png`（应用图标）。

### 2.3 `MainActivity.kt` 横/竖壳
- **竖屏**：`BottomSheetScaffold` + `PortraitPanelBody`；peek ≈ 240/270dp + navBars；Dashboard **`landscape=false`**；地图 `TrackMap` 在 scaffold content。
- **横屏**：不用 BottomSheet；外层 `Box` + **`safeDrawingPadding()`**（地图与面板都不进状态栏/导航条）；侧栏 `AnchoredDraggableState`，锚点 **Peek / Half / Full**（宽度约 peek≤268dp 或 42%宽、half≈72%宽、full=屏宽）。
- **物理底边**：`panelOnEndForRotation` — 自然竖屏手机上 `ROTATION_90` → 面板在 **End（右）**；`ROTATION_270` → **Start（左）**。原则：贴住竖屏时那条物理底边，不是固定左右栏。
- 侧栏内：贴地图一侧有 **22dp 把手**（主拖拽入口）；Peek 时 `LandscapePeekChrome`（标题/开始记录/侧拉提示）；展开后 `LandscapePanelBody` + `Dashboard(..., landscape=true)`。
- `landscapeExpanded`：`rememberSaveable`；回竖屏时若曾收起则 hide sheet。
- **曾闪退**：锚点未就绪时在 composition 里 `requireOffset()`；现用 `SideEffect { updateAnchors }` + `runCatching { requireOffset() }.getOrDefault(fallback)`。勿回退。

### 2.4 `Dashboard.kt`
- 入口：`Dashboard(replay, landscape)` → `DashboardPortrait` / `DashboardLandscape`。
- **Portrait**：原竖屏结构 — 大速度卡 + 航向/精度 + 加速度/陀螺/姿态行 + 归零 + GNSS 卡；整体靠外层 **竖向滚动**（在 sheet 的 `verticalScroll` 里）。
- **Landscape（当前实现，用户不满）**：`BoxWithConstraints` + `Row(horizontalScroll)`，内含 **两列「页」**（每列 `Modifier.width(pageW)`）：页1 紧凑版竖屏同款模块堆叠；页2 GNSS/频率。  
  → 虽是连续 `horizontalScroll`（非 `HorizontalPager`），但仍是 **按屏宽分页的两列**，不是用户要的「小模块转方向后的紧凑网格」。
- 文件中另有未接线的 `SpeedModule` / `AxisModule` / `GnssModule` / `CompactAxisCell`（曾为模块网格试验残留）。
- 传感器显示（横竖共用 model）：加速度 = 车辆坐标 **去重力线加速度**；归零只偏置姿态/陀螺显示；CSV 仍原始含重力。勿改语义除非用户要求。

---

## 3. 已完成（相对本轮横屏 + 此前）

- 传感器：**去重力线加速度** + 姿态/陀螺 **归零/复位**（详见既有 `GROKBOT_HANDOFF_2026-11-05.md`）。
- 横屏壳：`AnchoredDraggable` 侧栏、物理底边左右、Peek/Half/Full、把手拖、`safeDrawingPadding`、旋转不重建。
- 启动：用户提供的竖/横两张图已接入 `drawable-nodpi` / `drawable-land-nodpi`。
- 竖/横 Dashboard **分支修正**：曾误把横屏布局接到竖屏（竖屏也左右滑）→ 已改回竖屏只用 Portrait；横屏只用 Landscape。用户仍对 **横屏内容形态** 不满意。
- 闪退修复（requireOffset）已进当前树。

---

## 4. 用户确认的产品规则（必须遵守）

1. **物理底边**：竖屏底边上拉 → 转 90° 后变成左/右侧拉；跟旋转方向，不固定「永远右侧」。
2. **把手拖**：横屏 Peek→Half→Full 以 **拖把手** 为主，不要改成主要靠点按。
3. **无镂空**：展开贴边；不要一圈地图边框空隙。
4. **Safe drawing**：地图区域不要侵占状态栏和导航条。
5. **滚动轴**：竖屏 Dashboard **上下**；横屏 Dashboard **左右**（竖屏那条滚轴转 90° 的语义）。
6. **紧凑**：像竖屏一样挤；**不是**排成一条松散长带；**不是**故意做好几页 Pager；尽量一屏多看；要 **连续滑动**（可 overflow 横滑），不要一页页吸附翻页。
7. **模块级转向**：是各个小数据模块按横屏可读重排/转字，**不是**整块竖屏 UI 横向拉伸，也不是「整页旋转」。
8. **勿回归**：竖屏再次出现左右滑；横屏再次塞回纯竖向长列表当唯一形态。
9. **体验验收归用户**：用户已说验证交给他自己；可装机，但不必模拟器截图验收（本交接任务明确要求 **不要装机**）。

用户原话关键纠正摘要：
- 「排成一条带」被否：紧凑 ≠ 单行。
- 「竖屏上拉转横后就是左右侧拉」：壳层侧拉正确；Dashboard 内容轴也要左右。
- 「不要做成那种一页页」：当前两列 `width(pageW)` 仍像分页，需改掉。

---

## 5. 已知问题与用户不满点

| 问题 | 说明 |
|---|---|
| 横屏 Dashboard 形态不对 | 仍是「竖屏结构压进侧栏 + 第二屏宽列」，松散/像分页；未达到「小模块转向 + 紧凑网格」 |
| 多次改歪 | 曾侧栏点按无拖、全屏松散、单行长条、Pager、竖屏误接横滑、横屏误接竖滑；用户信任度低 |
| 视觉 | 镂空/系统条曾挡 peek；`safeDrawing` 后需真机再确认全屏贴边观感 |
| 死代码 | `SpeedModule` 等未使用，易误导下一家 |
| 产品差距 | 用户期望「和竖屏一个逻辑只换方向」；实现仍是双套壳 + 双套 Dashboard 拼凑 |
| 交接原因 | 用户明确「不满意…转给 Codex 继续」 |

未在本轮解决、可顺带留意（非本次吐槽焦点）：回放 `takeWhile`/高德全清重建性能；USB 仍无前台服务；旋转后 SAF 源恢复等——见旧交接。

---

## 6. 改动文件列表（git 视角，截至写交接时）

**已修改 (M)**  
`CONTEXT_INDEX.md`  
`app/build.gradle.kts`  
`app/src/main/AndroidManifest.xml`  
`app/src/main/java/com/cmts/mobile/Attitude.kt`  
`CaptureService.kt` / `Dashboard.kt` / `MainActivity.kt` / `MapScreen.kt` / `RecordsScreen.kt` / `Vbo.kt`  
`app/src/test/java/com/cmts/mobile/AttitudeTest.kt`  
`docs/adr/ADR-003.md` / `docs/data-format.md`

**未跟踪 (??) 与横屏/启动强相关**  
`SplashActivity.kt`  
`res/drawable-land-nodpi/`（横屏启动图）  
`res/drawable-nodpi/cmts_splash.png`、`cmts_splash_icon.png`（及既有 icon）  
`res/drawable/cmts_splash_window.xml`  
`res/values/colors.xml`、`themes.xml`

**未跟踪（更早能力，勿丢）**  
`AmapScreen.kt` / `Kml.kt` / `Replay.kt` / `UsbDebugActivity.kt`  
`app/libs/`（含 CH34X jar）  
测试 `KmlTest` / `ReplayTest`  
`docs/GROKBOT_HANDOFF_2026-11-05.md`、`amap-map.md`、`usb-debug.md`  
`scripts/check_elf_alignment.py`

统计约：`13 files changed, ~1019 insertions(+), ~131 deletions(-)`（仅已跟踪 diff；?? 另计）。

---

## 7. 建议 Codex 下一步（按优先级）

1. **先对齐交互原型再写码**：在 Note20 上对照竖屏半屏/全屏，画出横屏 Peek/Half/Full 线框（物理底边、把手、模块网格），用用户原话规则自检，避免再猜。
2. **重做 `DashboardLandscape`**：去掉「双屏宽 Column 分页」；用小模块紧凑网格（可复用/改造现有 `SpeedModule`/`AxisModule`/`GnssModule` 或等价），溢出 **连续** `horizontalScroll`；竖屏路径零改动。
3. **壳层只微调**：保留 `AnchoredDraggable` + 物理底边 + 把手拖 + `safeDrawing`；核对 Full 时无镂空、peek 不挡手势条。
4. **删或接线死代码**，加极小 Compose/逻辑测试防「竖屏 landscape=true」回归。
5. **只 `assembleDebug` + 用户指定机 `install -r`**；不做模拟器截图验收；**勿 commit** 除非用户要；**勿动 Windows**。
6. 启动图已接好，非阻塞；勿为启动页再大改除非用户提。

### 构建/安装（给 Codex）

```powershell
cd D:\CMTS\android
$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat --offline --console=plain assembleDebug
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s R5CN90V1J7V install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## 8. 硬约束（全局，勿破）

- 地图全屏铺底 + 浮动面板；TrackMap 仍在可点图层，不挡手势。  
- Google/高德切换与深色+香槟金视觉不擅自改。  
- 「车载安装方向」姿态轴 ≠ UI 横屏；勿混改。  
- CSV/导出 WGS84；Dashboard 显示去重力与归零语义保持。

---

*写于 2026-11-05（Asia/Shanghai）。仅文档；功能树未再改。*
