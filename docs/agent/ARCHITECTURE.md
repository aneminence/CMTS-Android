# 架构与接口约定

本文对照 `master` @ `0abde54`（版本 0.1.6）的源码整理。旧文件 `docs/architecture.md` 仍写「USB UM982 仅预留契约、当前未接入」，**已过时**，不要再按那份理解现状。

架构未在本轮发生变化；只有实际代码结构或协议变化时才需要改本文。

## 核心模块及职责

| 模块 | 主要文件 | 职责 |
|------|----------|------|
| 界面入口与浮动面板 | `MainActivity.kt` | Compose 主界面、横竖屏 Peek/半开/全屏、录制按钮与长按停止、定位源切换入口 |
| 采集服务 | `CaptureService.kt` | 前台服务；在 HandlerThread 采集并写盘；主线程更新 `Live` 显示状态 |
| 样本模型 | `Model.kt` | 统一 `Sample` 等数据结构；仍保留 `SensorDataSource` 扩展契约 |
| 定位源 | `GnssSource.kt`、`NmeaParser.kt` | 手机 GNSS 或外接 UM982 NMEA；`NmeaFixAssembler` 同一 UTC 历元最多输出一次定位 |
| USB 串口 | `UsbSerialHub.kt`、`UsbSerialPorts.kt`、`UsbDebugActivity.kt` | CH344Q / CH340 共享会话；调试页与后台定位共用连接，应用不发送模块配置命令 |
| 地图 | `MapScreen.kt`、`AmapScreen.kt`、`MapMath.kt`、`TrackRendering.kt` | Google 卫星瓦片按 WGS84 自绘；高德 SDK 仅作显示，用 `CoordinateConverter` 转 GCJ-02 |
| 仪表与性能 | `Dashboard.kt`、性能测试引擎 | 实时仪表、连续计分、成绩表 |
| 姿态 | `Attitude.kt`、`VehicleAttitudeEstimator.kt`、`AttitudePipeline.kt`、`AttitudeRecompute.kt` | 原系统旋转矢量姿态保留；实验估算并行输出 `estimate.*` |
| 记录与导出 | `RecordsScreen.kt`、`Vbo.kt`、`Kml.kt`、`SessionCsvWriter.kt` | 会话列表、回放、VBO/KML/CSV |
| 回放 | `Replay.kt` 及 `Replay*.kt` | 与 Live 隔离；可应用会话保存的安装归零 |
| 远程 | `remote/RemoteWsServer.kt`、`RemoteProtocol.kt`、`RemoteSamplePayload.kt`、`RemoteDiscovery.kt` | 局域网 WebSocket 服务端与发现 |
| 录制状态 | `RecordingState.kt` | 本端录制状态机；暂停目前不是独立协议状态 |

采集和写入在 HandlerThread，主线程更新显示。前台 location 服务由可见 Activity 发起；`START_NOT_STICKY`，避免进程恢复时未经用户操作重新采集。屏幕旋转不终止服务。WakeLock 在服务销毁时释放，系统强杀仍可能丢失尾部未刷盘数据。

## 模块依赖关系

`MainActivity` / Dashboard 观察进程内 `Live` 状态，不直接写原始传感器。`CaptureService` 是采集、写盘、对外接 GNSS 与远程样本的中心。远程和性能测试从采集侧取数，不另开一套定位源。Windows 客户端不在本仓库，只通过局域网协议与导出文件交互。

## 通信协议与数据格式

- 远程 WebSocket 协议 v0.1（`hello` / `subscribe` / `sample` / `record` 等）。协议 SSOT 在 Windows 仓库；本端实现见 `remote/` 与 `docs/REMOTE_WS_V0.1_NOTES.md`。
- 连接串格式：`cmts://IP:8787/cmts/v1?token=…`（端口以实际发布为准）。当前支持无 token 的 `device.hello`，同一局域网可访问的客户端均可连接（单人使用模式）。
- `stream.sample` 推送 `phone.*` / `vehicle.*`；安装归零开启时 `vehicle.*` 按 `mountZero` 变换后再推。实验姿态为附加的 `estimate.attitude` / `estimate.status`，**不得替换**既有 `vehicle.attitude`。
- 录制暂停不会单独出现在远程状态里，`Live.running` 在暂停期间仍为 true。
- 本地会话：`metadata.json` + `samples.csv`，正常关闭后有 `closed.marker`。坐标记录与导出保持 WGS84。CSV / VBO 通道说明见 `docs/data-format.md`。
- VBO：每条有效 GNSS 一行；车辆 IMU 取不超过 200 ms 的最近 `vehicle.*`；无 GNSS 的会话不生成 VBO。

## 关键接口与约定

- 定位源：`GnssSource.PHONE` 或 `GnssSource.EXTERNAL`。外接优先选用 NMEA 口（CH344Q 默认偏好 UART3）；`onLocationChanged` 在外接模式下直接返回，避免两路定位画到同一条轨迹。
- 外接 NMEA：校验和无效的句子不成为定位；GGA quality>0 或 RMC status A 才有位置；同一历元优先用 GGA 的纬经度，RMC 补速度与时间。
- 车辆坐标（ADR-002）：屏幕朝上、顶部朝车头；`vehicle.X = device.Y`，`vehicle.Y = -device.X`，`vehicle.Z = device.Z`。固定轴映射不是安装标定，原始 `phone.*` 仍保留。
- 地图（ADR-003）：Google 卫星 XYZ 瓦片用于自绘底图，不宣称商用授权。高德 Key 仅来自被 git 忽略的 `local.properties`。切换底图会重建视图，视角暂不跨地图保留。
- UI（ADR-001）：Jetpack Compose，深色 + 香槟金 `#D4AF37`。

## 重要设计决策

- 不把高德显示坐标写回 `TrackPoint`、CSV、VBO 或 KML。
- 不向外接 UM982 发送配置命令；输出频率与语句以模块当前设置为准。
- 实验姿态与成绩/坡度原链路并行，关掉对照即隐藏新结果，不替换原系统姿态。
- 协议与跨端字段变更必须记录兼容性影响，并明确 Windows 是否需要同步适配。

## 跨平台兼容性

- Windows 工程只读参考，不在本仓库修改。
- Android CSV 是显式版本化的采集格式，**尚未验证** Windows 客户端可直接导入。
- 远程新增字段必须保持旧字段含义；Windows 对 `estimate.*` 的显示尚未实机验收。
