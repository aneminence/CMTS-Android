## CMTS Android 0.1.1

手机端 Standalone MVP：GNSS+IMU 采集、Compose 地图仪表、录制回放导出，以及 **Performance Test** 多选段成绩。

### 本版亮点
- **Performance Test**：四种段可多选（0→X / X→Y 加速，X→Y / X→0 制动）、自定义阈值、待命自动起止、成绩表（时间·类型·时间/距离）、质量标识、历史与重算（`result-vN`，不覆盖原始）
- 引擎 `perf-engine-1.0.0`：原始 GNSS 速度与采样时间戳；阈值穿越线性插值；制动距离梯形积分
- Dashboard ↔ 性能测试同级分页（竖屏全屏后左右滑，横屏上下滑）；半开仅摘要
- 横屏面板跟物理底边侧拉；竖/横启动图
- 去重力线加速度显示、姿态/陀螺归零、气压；Google/高德；回放与 VBO/KML/CSV；USB 调试页

### 重要声明
- **未宣称室外实车验收通过**；质量门限为调参建议，不是已验证规格
- 插值仅用于估计穿越时刻，**不宣称提高了 GNSS 测量精度**
- 成绩禁止使用 UI 刷新数据或回放抽样

### 资源
- 源码标签：`v0.1.1`（含 README 更新 `d0cdea3`；功能发布提交 `373ffa7`）
- 附件：`CMTS-Android-0.1.1-debug.apk`（debug，约 81 MB）。安装需开启「安装未知应用」
- 自行构建见仓库 [README](https://github.com/aneminence/CMTS-Android#构建说明--build)

### 安装注意
覆盖安装保留数据：`adb install -r ...`；安装前确认手机**未在录制**。
