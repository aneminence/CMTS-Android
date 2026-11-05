# CMTS Android 交接 — 2026-11-05

## 接手入口与边界
- 工程 D:\CMTS\android；先读 AGENTS.md。本文件接续 GROKBOT_HANDOFF_2026-11-04.md。
- 仅改 Android；Windows 工程不改，RK3588 不刷机。
- 本轮修改尚未提交 git；包含未跟踪新文件，勿只看 git diff。勿覆盖用户修改。
- 用户偏好：继续直接做；完成构建后覆盖更新三星手机，保留数据。安装前检查是否正在录制。
- 手机 SM-S9180，ADB serial R5CW51WPC6B；另有 emulator-5554。
- 最新 APK app/build/outputs/apk/debug/app-debug.apk，已 install -r 成功并启动。

## 已实现
### USB 调试
- app/libs/CH34XUartDriver.jar 来自用户文件；入口为 cn.wch.uartlib.WCHUARTManager，不是旧 CH34xUARTDriver。
- USB Host 枚举、VID/PID/接口、授权、驱动支持判定、串口索引、115200/230400/460800/921600 和自定义波特率、后台读取、HEX/ASCII、完整 bin 文件保存/SAF 导出。
- UsbDebugActivity.kt 内含 UsbTransport；无串口发送接口调用，不发 UM982 配置指令。
- 重要限制：仍是 Activity 生命周期，关闭/重建会停止，尚无 USB 前台服务、唤醒锁、长时锁屏保证。USB OTG 和 UM982 实际接收及插拔未验收。驱动 disconnect 内部会复位 DTR/RTS 和串口参数。
- 细节 docs/usb-debug.md，审计本地 evidence/wch-*.txt。

### 三星锁屏采集
- 真机复现省电开启时 CaptureService 仍前台，但 CMTS:capture 唤醒锁 DISABLED，CSV 停止；亮屏恢复。
- 用户明确授权关闭整机省电；已关闭，保留 CMTS 电池优化豁免。单独豁免不足以解决。
- 改为初始化前获取唤醒锁，优先唤醒型传感器（该三星实际只有非唤醒型），增加省电提示/设置入口。
- 关闭省电后真机锁屏 CSV 从 17500 增至 33250，服务正常，测试已停止落盘；不等于长期后台/Doze全验收。

### 地图
- 保留 Google 卫星图，新增高德标准图切换和首次隐私同意。
- 高德 Key 仅在本机 local.properties 的 AMAP_API_KEY，经 manifestPlaceholders 注入，勿提交/抄进交接或日志。
- 依赖：com.amap.api:3dmap-location-search:11.3.100_loc11.3.101_sea9.8.1；androidx.graphics:graphics-path:1.1.0。
- 旧 3dmap:10.0.600 引发手机 16 KB 对齐警告，已替换。arm64 所有 SO LOAD 对齐 0x4000，ZIP zipalign -c -P 16 4 通过；安装后无警告，高德道路底图已看到。
- 手机 PAGE_SIZE=4096，不是16 KB运行验收。高德32位 ARM库仍4 KB；新版不带 x86 地图库，x86模拟器高德模式不保证可用。不要用隐藏警告或关闭debug代替修复。
- scripts/check_elf_alignment.py 检查64位LOAD对齐；evidence/page16-elf.txt 与 page16-proxy-build.log。
- 高德 CoordinateConverter GPS 转换仅用于显示，原始数据/导出始终 WGS84。未启用高德定位。
- Google 无GNSS默认浏览天津东丽区附近 39.086,117.314，zoom13；不是采集坐标。
- Google 改为最多4路并行、160块会话内存缓存、缓存层级缩放过渡，无磁盘缓存/批量预取；已更新 ADR-003。新区域仍联网，不能宣称免加载。
- 控件靠右上、深色半透明圆角+金字；记录与文件也已统一 MapControl 样式。

### UI/回放/导出
- 上拉仪表盘全屏，展开矩形顶角不再漏地图；下拉返回。
- 小仪表卡片已改为左上标题/右上单位、第二行数值居中，垂直padding7dp；最终版本已安装。
- 历史列表紧凑，左侧时间/状态，右侧 VBO/KML/CSV，移除ZIP入口。原始文件未删除。
- CSV 原样导出 samples.csv，包含完整高频源、时间戳、质量；目前导出选中session只在Activity内存，旋转恢复待完善。
- Kml.kt 输出WGS84 lon,lat地面轨迹，clampToGround，过滤mock/simulated/非法坐标，至少2点；无时间动画/真实高度/断点分段。
- 点击已保存session返回主地图与Dashboard回放；Replay.kt从CSV读取，约100ms快照，支持IMU-only（不造坐标）。与Live隔离。
- 播放/暂停、滑条、退出、0.5/1/2/4/8倍速。依据单调时钟推进，约33ms刷新，按目标时间跳帧。
- 回放折叠面板270dp+导航栏高度，减小间距、隐藏开始录制按钮，避免滑条落入导航栏；视觉需用户复核更多字号/横屏。
- 打开历史慢：旧实现全量split+逐行Double解析，现先筛所需源，仅快照时解析最新IMU，GPS保留全量；百分比进度（按字符数估算字节进度），仅最近一条记录的进程内缓存，键=路径/长度/mtime。首次仍全扫描。未测得真机加速倍数，不要声称秒开。
- 限制：回放最多72000快照（约2小时），无磁盘索引；外部“打开VBO”仍旧文件页预览，不是主界面回放；不要把本地session回放说成任意格式文件通用回放。


### 安装归零 / 线加速度（本轮修订）
- Dashboard 加速度：旋转矢量估重力后扣除，显示车辆坐标线加速度（静止≈0）；不再用含重力安装偏置。
- 归零/复位：仅姿态与陀螺显示偏置；CSV 仍原始含重力加速度。
- AttitudeTest 覆盖去重力与归零；assembleDebug + unit test；真机 install -r。

## 验证
- 多轮 assembleDebug/testDebugUnitTest/lintDebug 成功。
- 新增 KmlTest：经纬度顺序/XML合法/过滤mock非法点/拒绝IMU-only。
- ReplayTest：初始帧无未来GPS，真实坐标和最终IMU值保留。
- 最近逻辑版本 evidence/csv-replay-final.log（构建+单测+Lint）；最终纯布局 evidence/compact-gauges-build.log（构建+Lint）。
- 真机确认高德道路显示、16KB警告消失、Google默认东丽区文本、全屏仪表盘、紧凑列表；最新卡片/倍速/滑条布局未完成系统性真机回归。
- 尚未完整跑 SAF 导出到文件→第三方KML/VBO/CSV软件打开。USB和RK3588未实机验收。

## 建议下一步（优先）
1. 真机回归滑条无遮挡、倍速、进度拖动、暂停/退出、卡片单位标题不挤压、大字体/横屏、导出按钮不触发整行回放。
2. 测量大CSV冷/热打开耗时及内存。必要时建立版本化磁盘回放索引；保留高频原始数据，不将10Hz显示快照当原始采样。
3. 性能：每次replayIndex变化 takeWhile 重建轨迹；高德每次map.clear/重建所有点线+坐标转换，长轨迹可卡，优先增量覆盖物及缓存坐标。高德跟随时固定zoom16也会干扰缩放。
4. KML断点/跨日期/地理范围测试；确认导出文件第三方打开。补旋转/进程恢复时SAF源文件恢复。
5. USB重构独立前台服务+唤醒锁，生命周期/并发断连/后台异常与设备插拔测试，再做三星OTG/UM982测试；不发未知配置命令。
6. 依赖仍要真实16KB ARM64环境验证；RK3588 Android12实测另做。旧Google直连发布授权仍未解决。

## 构建
PowerShell cwd D:\CMTS\android：
$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat --offline --console=plain assembleDebug testDebugUnitTest lintDebug
网络下载需要时使用已有代理127.0.0.1:7897（Gradle -Dhttps.proxyHost / -Dhttps.proxyPort）。不要修改全局代理。
本机 evidence/、local.properties、APK和build产物不提交。
