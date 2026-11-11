# 车辆姿态估算交付与验证（2026-11-11）

## 安装

已覆盖安装到 Samsung SM-N9860 / R5CN90V1J7V，保留应用数据。
APK：app/build/outputs/apk/debug/app-debug.apk；versionName 0.1.3。
SHA256：855C516CB8C2DF975691189ADB02E39716E313AB5279E90C29C235F8B210FBDB。
RK3588 未修改。

## 测试结果

assembleDebug / testDebugUnitTest / lintDebug 成功，143 tests，0 failures。
指定理想 5s/20s 阶跃干扰与组合俯仰/侧倾测试满足 <=1°目标。
0.4g 转弯 + 3°侧倾：最大误差 9.4107e-7° / 9.8507e-7°。
60s 缓增 0..0.3m/s²、0.0003rad/s 偏置及固定种子噪声：最大伪俯仰 1.69661°。
60s 缺失重力参考、0.001rad/s 残余偏置：残余侧倾 3.37931°。
后两项是局限性测量，不是精度保证或 <=1°阶跃目标的适用场景。

手机验证：安装成功、进程正常；横竖屏面板可读，后台返回正常，旋转设置已恢复。
读取真实 samples.csv 验证 phone.*、vehicle.*、estimate.* 独立保存，初始化为 null 角度。
安装后 crash buffer 无新异常；旧 crash 记录不能归为本次版本。
当前 GNSS 无可靠速度，未执行实机停车校准完成/动态融合验收。
没有独立姿态参考，不宣称手机实测绝对精度。

## 启用、校准和回退

Dashboard 打开“车辆姿态估算 · 实验对照”。
首版固定安装约定：屏幕朝上，手机顶部指向车头，保持固定；不支持未知安装航向。
可靠手机 GNSS 定位下停车，点击“确认已停车”，保持稳定至少3秒。
条件：速度<0.2m/s、速度精度<=0.3m/s、稳定陀螺/加计；不满足时保持初始化。
转弯/加减速降低重力校正；真转动由陀螺传播。长空白或饱和后需重新停车校准。
关掉对照开关即可隐藏新结果；原系统姿态始终保留，成绩/坡度算法没有替换。
原“归零”语义保持，新估算不把归零位置声明为水平。

## 边界

GNSS 定量补偿未启用：没有已验证时间对齐、车头航向关系与杆臂数据。
外接 NMEA heading 混合 HPR/RMC 语义，新门控仅用可靠手机 GNSS。
六轴 yaw 无长期绝对参考；慢增长平稳加速度仍可能产生假倾斜。
文件重算要求完整原始停车初始化历史；中途才开始录制的文件可回放派生数据，
但不能恢复未记录的滤波内部历史，重算会明确拒绝。没有反造数据。
传输新增 estimate.attitude/status，不替换旧字段；Windows 接收端显示未实机验收。
实机户外停车校准、道路动态一致性与独立参考精度验收仍需实际条件。

## 文件

VehicleAttitudeEstimator.kt：纯 Kotlin 四元数传播与加速度拒绝。
AttitudePipeline.kt：停车校准、手机GNSS运动门控、状态及派生输出。
AttitudeRecompute.kt：因果、流式 CSV 重算，不覆盖原始记录。
CaptureService.kt：并行接入、版本/参数/上下文记录。
Dashboard.kt / RecordsScreen.kt / Replay.kt：对照显示、重算入口和派生回放。
remote/RemoteSamplePayload.kt：独立派生传输字段。
VehicleAttitudeEstimatorTest.kt / AttitudePipelineTest.kt / remote/RemoteSamplePayloadTest.kt：回归。
MainActivity.kt 的既有未提交修改未在本任务中改写。
