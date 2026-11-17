# 已知问题与技术债

问题解决后更新状态，保留必要历史，不直接抹掉重要信息。

## 开放

### 高德底图上 20 Hz 轨迹呈锯齿

- 状态：开放
- 优先级：低（用户 2026-11-17 确认可先放在待解决问题里，暂不修复）
- 发现：2026-11-16/17，用户实车轨迹对比
- 可复现条件：定位源为外接 20 Hz GNSS，地图切到高德；同一份轨迹切到谷歌卫星图后平滑
- 当前排查结论：不是 UM982 或 20 Hz 采样本身导致的可见锯齿。高德路径使用 `CoordinateConverter` 将 WGS84 转为显示坐标后再画 `Polyline`；Google 路径按 WGS84 自绘。原始 `TrackPoint`、CSV、VBO、KML 仍为 WGS84。根因尚未在代码里修完（转换精度或折线渲染都有可能）。
- 临时方案：需要看平滑轨迹时改用谷歌卫星图。不要给原始定位加滤波来掩盖显示问题。
- 关联模块：`AmapScreen.kt`、`TrackRendering.kt`、`MapScreen.kt`

### 室外实车验收未完成

- 状态：开放（明确未宣称通过）
- 说明：各版本发布说明与 README 均保留此边界。性能测试门限是调参建议，不是已验证规格。
- 关联模块：采集、轨迹、Performance Test、姿态、导出

### 0.1.6 录制控制待用户实机确认

- 状态：开放
- 可复现 / 待验：长按 2 秒停止的手感；不足 2 秒或旋转/松手应取消；录制→暂停→继续→停止时，暂停期间普通 CSV 无新样本、继续后追加到同一会话
- 当前结论：代码已合入，S23 Ultra 已覆盖安装。代理未做这些交互的实机操作验收，不能把安装成功写成已验收。
- 关联模块：`MainActivity.kt`、`CaptureService.kt`

### 实验姿态尚无实车绝对精度验收

- 状态：开放
- 当前结论：单测与合成测试有记录（见 `docs/vehicle-attitude-delivery.md`）。定量 GNSS 加速度补偿未启用。户外停车校准、动态融合、独立参考精度未验收。外接 HPR/RMC 混合航向未作为新门控输入。
- 关联模块：`VehicleAttitudeEstimator.kt`、`AttitudePipeline.kt`

### 远程暂停状态与 estimate 字段待对端验收

- 状态：开放
- 当前结论：本端暂停不改变 `RecordingStateMachine` 的 RECORDING，远程不会单独报告暂停。`estimate.attitude/status` 为附加字段，Windows 显示未实机验收。
- 关联模块：`remote/`、`RecordingState.kt`

### Android 导出与 Windows 直接导入未核对完毕

- 状态：开放
- 当前结论：`docs/windows-compatibility.md` 仍写尚未完成单位和文件结构逐字段核对；不能把本应用回读成功视为 Windows 或 VBOX Test Suite 验收。
- 关联模块：`Vbo.kt`、`docs/data-format.md`

### Google 瓦片授权与国内网络稳定性

- 状态：开放（ADR-003）
- 当前结论：产品选择使用 Google 卫星 XYZ 瓦片，不宣称商用授权。更换底图必须先问用户。
- 关联模块：`MapScreen.kt`、`docs/adr/ADR-003.md`

### RK3588 系统重力感应方向与屏幕物理方向不一致

- 状态：开放（超出本应用范围）
- 当前结论：属系统镜像 / 传感器 layout / 面板方向，不是 CMTS 应用内旋转。未改 DTS 或刷机。不要用 `wm size` / `wm density` 或长时间重的 `dumpsys` 去“修”。
- 临时方案：用户若只需固定方向，可在系统设置里处理；若要自动旋转跟随正确重力，需要硬件/镜像侧修改。
- 关联模块：系统镜像，非本仓库功能代码

### 过时文档：`docs/architecture.md`

- 状态：开放（文档债）
- 当前结论：该文件仍称 USB UM982 只预留契约、未宣称已接入。`master` 自 0.1.4 起已实现外接 UM982。现以 `docs/agent/ARCHITECTURE.md` 为准，旧文件暂未改写，以免和本轮新增文档职责重叠。

## 已记录的限制（不是缺陷，但容易误判）

- 无 GNSS 的会话故意不能导出 VBO，不得编造定位。
- 高德模式在 x86 模拟器不可用（官方库无对应地图 so）；以 ARM64 真机为目标。
- 部分三星机型省电可能导致锁屏后 CSV 停写；已验证场景不等于全部机型验收。
