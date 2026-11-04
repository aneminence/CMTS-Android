# CMTS Android 开发交接 — 2026-11-04

## 本轮已跟进（CMTS · Android，2026-11-04 晚）

- 已修正 `Vbo.kt` 导出 `[comments]`：改为车辆坐标与右手 yaw 说明（不再写 Device axes / Magnetic azimuth）。
- 已同步 `docs/data-format.md` VBO/CSV 通道说明为 `Vehicle_*` / `vehicle.*`。
- `:app:testDebugUnitTest`（含 Vbo）离线通过。

## 任务与用户决定

Android 工程 `D:\CMTS\android`，Kotlin + Jetpack Compose + Gradle，用 Android Studio 开发。
Windows 工程 `D:\CMTS\Chassis Master Test Suite` 保持不变，本轮只读参考。
优先手机 Standalone MVP，RK3588/USB UM982/RS485/Remote 仍为后续，不依赖供应商系统权限。

用户最新确认：手机已更新，地图拖动和缩放恢复正常，可开始交接。
用户偏好：不要停留在方案；遇到需要产品/网络/服务选择的问题停下来问，不擅自换方案。

### 界面要求

- 两步路式：地图全屏铺底，底部浮动面板覆盖地图；不得有黑色间隙。
- 开始/停止记录；上拉查看 Dashboard；下拉或“全屏地图”隐藏面板，再由底部小按钮打开。
- Dashboard 仅实时测量；UUID、历史记录、导出等放到独立“记录与文件”。
- 深色 + 香槟金 #D4AF37，已用用户提供的 CMTS PNG 图标。
- 用户指定 Google 底图，参考 Windows 原有直连方式。已接 Google Satellite `https://mt0.google.com/vt/lyrs=s&x={x}&y={y}&z={z}`。
- 去掉大的 Google Satellite 标签，保留小字 © Google。手机实际加载成功；模拟器仍可能连接超时。
- 该直连方式只验证了可访问性，未完成正式发布授权/稳定性评估；不要把它描述成已获免费商用授权。

## 当前版本与构建

- appId `com.cmts.mobile`，versionName 0.1.0，versionCode 1；未擅自升级版本号。
- AGP 9.5.0、Gradle 9.6.0、JBR/JDK 25，AGP 内置 Kotlin 2.2.10、Compose compiler 2.2.10。
- compileSdk/targetSdk 35，minSdk 26，Java bytecode target17。
- Studio/JBR：`C:\Program Files\Android\Android Studio\jbr`
- SDK：`C:\Users\anemi\AppData\Local\Android\Sdk`
- ADB：上述 SDK 下 `platform-tools\adb.exe`
- 手机：Samsung SM-S9180，ADB serial `R5CW51WPC6B`。
- 模拟器：`emulator-5554`，API37。
- APK：`D:\CMTS\android\app\build\outputs\apk\debug\app-debug.apk`

构建命令（PowerShell，android 目录）：

```powershell
$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat --offline --console=plain assembleDebug testDebugUnitTest lintDebug
```

现有依赖已缓存，最新构建离线成功。工具沙箱可能无法访问用户 Gradle/SDK，需要工具层允许本机访问；这不等于构建环境损坏。
网络代理曾使用 127.0.0.1:7897；本轮没有改系统/手机代理或 DNS。

## 已实现代码

### 地图与浮动面板

- `MainActivity.kt`：底部面板、隐藏/恢复、权限、启动记录、异步导入导出与文件页面切换。
- `MapScreen.kt`：原生 Compose Canvas 瓦片地图，拖动/捏合、缩放按钮、回定位、轨迹与精度圈。
- Google 瓦片仅保留当前可见区域内存，不做批量下载或离线 Google 缓存；失败有重试。
- `MapMath.kt`：Mercator、经度环绕、单调时钟计时。
- 地图手势曾被透明 Scaffold 覆盖层阻挡；最终修复：TrackMap 放入 BottomSheetScaffold 的 content，**不应用 Scaffold 返回的底部 padding**，而非放到另一个被 Scaffold 覆盖的底层兄弟节点。
- 用户已验证最终安装版本能拖动缩放。全屏隐藏面板仍建议接手后做完整回归。
- 地图当前开始记录后才采集定位；尚无独立的记录前定位预览。

### 手机传感器、车辆坐标、姿态

- `CaptureService.kt`：前台服务、后台 HandlerThread、CSV、唤醒锁、GPS/加速度/陀螺仪/旋转矢量采集。
- 请求 IMU 采样间隔改为 **10000 微秒（目标100Hz）**，不是固定强制100Hz。
- GNSS 频率独立，不能声称手机GPS有100Hz。
- UI快照仍限制约10Hz；原始CSV按实际事件写入。
- `Rate` 当前为相邻同源时间戳的瞬时频率，不是窗口平均。原先53.39Hz来自约50Hz请求的实际回调，不是固定常量。
- 手机有 TYPE_ROTATION_VECTOR。记录原始旋转矢量及派生姿态。

**用户指定安装方向，已实现：**屏幕朝上、手机顶部朝车头；车辆X前/Y左/Z上。

```
vehicle.X = device.Y
vehicle.Y = -device.X
vehicle.Z = device.Z
```

- 原始 `phone.accel` / `phone.gyro` / `phone.rotation_vector` / `phone.attitude` 保留。
- 派生 `vehicle.accel` / `vehicle.gyro` / `vehicle.attitude` 单独记录，Dashboard 显示 vehicle 来源。
- `Attitude.kt`：向量轴转换及旋转矩阵转换，不能只交换三个欧拉角。
- vehicle.attitude 顺序：roll X / pitch Y / yaw Z，单位度；右手正向。
- 正横滚＝左侧抬起；正俯仰＝车头向下；正偏航＝从磁北向左旋转，范围约±180°。
- 此偏航与GNSS heading不同；磁北参考、有磁场干扰，未归零、未做安装校准。
- 加速度仍含重力；平放Z约9.8~10并未实施偏置校准。用户暂缓该问题。
- metadata.json 有安装方向、姿态定义、10000us请求等字段。

### VBO 和记录页面

- `RecordsScreen.kt`：单独记录列表，按日期显示；导出VBO/原始ZIP；打开VBO。
- `Vbo.kt`：纯Kotlin编解码。参考 Windows VboRecorder/VboReader 和 `D:\CMTS\test data`。
- 导出标准基础字段：UTC HHmmss.SSS、Elapsed_time秒、velocity km/h、heading度、lat北正角分、long西正角分（东经负）、height米。
- 每个GNSS定位样本一行，车辆IMU使用不晚于定位且年龄<=200ms的最新样本；缺失写NaN，不填零。
- 导出扩展字段 Vehicle_Acc_X/Y/Z（m/s²含重力）、Vehicle_Gyro_X/Y/Z（deg/s）、Vehicle_Roll/Pitch/Yaw（度），另含精度/时间戳/模拟标志。
- 没GNSS的记录不生成空VBO，提示可导出原始ZIP。必须关闭记录才可导出。
- VBO不是完整高频原始数据容器；完整异步样本、质量、原始旋转矢量和元数据仍在CSV+ZIP。
- 老会话没有vehicle.*来源，当前导出车辆通道为NaN，不偷偷假定历史安装姿态。
- 导入按[column names]读取，最多200000行；离线轨迹、样本滑块、原始通道列表，不改变实时数据。
- 标准坐标解析支持已验证样例；**不支持GSpot错位/deg*1e7非标准布局自动识别**。
- 尚未给系统注册外部 `.vbo` 文件“打开方式”；当前由App内“打开VBO文件”选择文件。

## 已验证与证据

最新构建 `evidence/map-gesture-fix-build.log`：assembleDebug、testDebugUnitTest、lintDebug成功。
**16个单元测试通过**：Model3、Dashboard2、MapMath3、Attitude4、Vbo4。Lint 0 errors / 10 warnings。
报告：`app/build/reports/tests/testDebugUnitTest/index.html`、`app/build/reports/lint-results-debug.html`。

### 手机真实硬件

- 最新APK覆盖安装成功，既有记录未清除；用户确认最终地图拖动/缩放可用。
- Google卫星底图手机加载成功，截图 `evidence/google-phone-map.png`（早期布局）、后续 `vehicle-phone.png` 也有影像。
- 从手机真实会话CSV统计约499秒记录，证据 `evidence/phone-100hz-stats.json`：
  - 加速度53315条 / 499.33s，平均约106.77Hz。
  - 陀螺仪53296条 / 499.32s，平均约106.74Hz。
  - 旋转矢量/姿态53292条 / 499.28s，平均约106.74Hz。
- 说明100Hz请求在该手机已达到约106.7Hz实际回调；不是严格100Hz定频。
- 该统计没有GNSS行，不能声称GNSS定位/路测轨迹验收成功。
- 验证了姿态回调和写入存在，**没有验证姿态角绝对精度或车载标定**。

### 模拟器

- 安装/启动、上拉Dashboard验证过；模拟器地图在线访问仍失败。
- 通过真实系统文件选择器导入用户 `CMTS_测试文件_供VBTS验证.vbo`，显示 **14566行、11通道**。
- 证据 `evidence/vbo-import.xml`，原样例复制在模拟器 Download/cmts-reference.vbo。
- 三个样例前100行单元测试成功：ons shot 7.vbo、CMTS_测试文件_供VBTS验证.vbo、GSpot-000-1162347959.vbo。
- VBO导出编码及回读单元测试成功；**未跑完整手机SAF保存→VBOX Test Suite打开验收**。

### RK3588

未验证，未修改供应商APK/底层系统。不要把模拟器或手机结果当作RK实机验收。

## 接手优先事项 / 已知不足

1. 完整回归全屏地图、面板隐藏/恢复/滚动、横竖屏、文件页面返回；确保没有触摸拦截或列表滚动位置导致开始停止按钮不可见。
2. 室外真实GNSS定位、记录移动轨迹，完成VBO导出并用Windows CMTS和VBOX Test Suite打开；重点核对角分/东经符号、UTC跨午夜、NaN兼容与列映射。
3. ~~Vbo.kt [comments] / data-format.md Device_*~~ **已完成**（见上文「本轮已跟进」）。
4. 最好给VBO增加每通道时间/质量/有效标志；当前200ms取样规则并非精密同步，不能冒充100Hz同步GNSS。
5. 导出无GNSS时失败符合当前设计；若用户希望纯IMU也生成VBO，需要明确缺失定位/时间锚定方案，不要虚构GPS坐标。
6. 大VBO最多200000行，但地图当前绘制全部轨迹点，需抽稀/分段/后台解析进度及内存性能验证。
7. 当前瓦片顺序加载，移动时取消/重建可见请求，未做并行限流队列；网络慢时可继续优化。
8. Rate瞬时Hz容易跳动；可增加滑动窗口平均/丢样统计，仍保留原始时间戳。测试高频写入和后台长时间稳定性。
9. 文件导出选择器期间Activity重建恢复、URI打开关联、取消处理、用户可读错误提示仍需完善。
10. 记录服务的status/closed.marker语义、关闭完成后列表自动刷新、质量准确性需继续检查；旧有异常处理并非生产级完成。
11. 固定坐标转换不是安装标定：后续可加水平/零偏标定，必须保留原始传感器和标定参数。
12. Google正式发布授权和国内网络稳定性未解决。不要再换回OSM而不告知用户。

## 重要边界

- 不改Windows客户端、不刷RK3588、不删除供应商APK、不把模拟数据算真机通过。
- 不重跑所有旧工作；从这个APK和源码继续。
- 用户此前提到 Android Studio Codex ACP 握手超时，曾查到npx安装超时；未确认最终解决，与本轮地图/传感器开发独立。
- D:\CMTS 根目录执行git status报告不是Git仓库；提交前先确认实际仓库边界，禁止随意初始化/重置。
