# 数据格式 v1

2026-11-04 扩展：`phone.rotation_vector` 保存 Android TYPE_ROTATION_VECTOR 原始值
（四元数向量 x/y/z、标量 w、方位误差 rad；按硬件实际提供列数保存）。
`phone.attitude` 保存从旋转矩阵派生的俯仰 X、横滚 Y、方位 −Z、方位估计误差，单位均为度。
采用 Android getOrientation 定义和设备自然坐标，方位参考磁北，未经车体标定；quality 保留精度级别，时间戳沿用原始旋转矢量时间。

## VBO 导入/导出

从“记录与文件”导出已关闭的会话。VBO 使用 UTC HHmmss.SSS、Elapsed_time 秒、
velocity km/h、lat 北正角分、long 西正角分（东经为负）、height 米。
每条有效 GNSS 回调生成一行；Vehicle_Acc_X/Y/Z 为含重力 m/s²，Vehicle_Gyro_X/Y/Z 为 deg/s，
Vehicle_Roll/Pitch/Yaw 为度（车辆坐标：X前/Y左/Z上；右手：正横滚=左侧抬起，正俯仰=车头向下，正偏航=自磁北向左，约±180°）。
车辆通道取不晚于 GNSS 样本且不超过 200ms 的最新 vehicle.* 值；缺失/过旧写 NaN，不填零。
老会话无 vehicle.* 时车辆通道为 NaN。保留单调时间、UTC、定位精度及模拟标志扩展列。
没有 GNSS 的会话不会生成 VBO，仍能导出原始 ZIP。高频 IMU、原始质量信息和元数据以 ZIP 为完整记录。

导入按 [column names] 映射通道，显示标准 VBO 轨迹及逐样本原始通道。
当前上限 200000 行；不支持 GSpot 错位/deg*1e7 非标准坐标布局自动校正。
已用三个用户样例的前 100 行验证标准布局。VBOX Test Suite 对 NaN 和扩展通道的兼容性仍需实测，
不能把本应用回读成功视为第三方软件验收。

每个 UUID 会话目录包含 metadata.json、samples.csv；正常关闭后生成 closed.marker。metadata 中 incomplete 表示初始状态；是否关闭以 marker 为准，不代表所有传感器成功或数据完整。

CSV：sequence 为会话递增序号；source 为 phone.gps / phone.accel / phone.gyro / phone.rotation_vector / phone.attitude / vehicle.accel / vehicle.gyro / vehicle.attitude；sample_elapsed_ns 是 Android 原始单调时钟纳秒；callback_elapsed_ns 是写入入口时单调时钟；utc_ms 仅 GNSS 提供，为 Location.time 的 Unix 毫秒，未宣称 GPS 周秒或 PPS；quality 是系统报告状态或 MOCK。空值表示不可用，不以零填充。

GPS v1..v7：WGS84 纬度 °、经度 °、Location 高度 m、速度 m/s、航向 °、水平精度 m、垂直精度 m。IMU：phone.accel/gyro 为设备自然坐标；另有 vehicle.accel/gyro/attitude（安装约定：屏幕朝上、顶部朝车头；X前/Y左/Z上）。加速度含重力；CSV 角速度为 rad/s，VBO 为 deg/s。可变列数。固定轴映射不是安装标定，原始 phone.* 仍保留。

显示 Hz 为相邻同源原始时间戳间隔的倒数，并非配置频率或硬件能力保证。UTC 不可用于跨传感器精密同步。原始字节、时间转换误差、设备唯一标识与丢样字段仍待扩展。
