> **DEPRECATED:** Progress snapshot superseded by [GROKBOT_HANDOFF_2026-11-04.md](GROKBOT_HANDOFF_2026-11-04.md). Prefer the handoff + [tasks.md](tasks.md); do not treat this file as current status.

# Windows Dashboard 对照

读取 Windows Controls/DashboardPanel.xaml、DashboardPanel.xaml.cs、Core/DashboardGaugeModels.cs。默认仪表为 Speed km/h、Yaw Rate deg/s、Lateral Accel. m/s2、Longitudinal Acc. m/s2、Heading deg。支持自由布局、添加/重置、通道绑定、试验结果绑定、光标冻结和多车摘要。

Android 本轮实现实时速度（m/s × 3.6）、航向、水平/垂直精度、WGS84 经纬度与高度、设备 XYZ 加速度和角速度（rad/s × 180/π）、实际样本 Hz。3 秒未更新或停止时数值显示 —；未知值不显示零。保留记录与导出功能。

手机加速度含重力，未标定车体坐标，因此不将设备轴称为横纵向加速度或车辆横摆率。此轮不移植拖动缩放、通道自定义、计算通道、试验结果和光标回放。Windows 源码仅阅读，未修改。

显示转换不改变 CSV 原始 SI 单位。模拟器保持 SIMULATED 标记，不能视为真机验收。测试覆盖速度/角速度换算、空值及过期/停止判定。
