# CMTS Android 开发交接 · 2026-11-06

**交给 Grokbot**。今天的 Android 增量已在本机离线构建，最终 debug APK 已覆盖安装到三星 SM-S9180。下一步是复核工作区、准备新版本和 GitHub Release。本次交接时改动尚未提交，尚未创建新标签或发布 Release。

## 接手位置与发布基线

- 工程在 D:\CMTS\android，分支 master，交接时 HEAD 为 c5be8c9。先读 AGENTS.md。当天需求来源为 docs/GROKBOT_HANDOFF_2026-11-06_BACKLOG.md，性能输入、质量门限和横屏交互的前置约束见 11 月 5 日三份专项交接。
- 应用显示名已改为 CMTS，applicationId 为 com.cmts.mobile。当前配置仍为 versionName 0.1.1、versionCode 2，本地已有 v0.1.1 标签。工作区的 release-notes-0.1.1.md 描述的是旧版，含“半开仅摘要”等过期表述。
- 今天仅修改 Android。沿用现有底图、品牌、深色香槟金及实时性能纯引擎。Windows 客户端仅作只读参考和故障诊断。

## 今天实现、验证与待验

| 范围 | 已实现 | 已验证 | 未完成或待验 |
|---|---|---|---|
| 实时预览与记录管理 | App 可见时绑定采集服务，仪表在开始记录前即可刷新。显式开始记录才创建会话。归零可在传感器就绪时执行。记录行左滑露出删除按钮，可清理已保存或中断的会话，并保护当前写入目录。 | SM-S9180 上查到预览服务绑定和气压、旋转向量、陀螺仪订阅。 | 实测预览→记录→停止→退后台的切换、室外 GNSS，以及用专门的测试记录验证删除。未用用户历史记录试删。 |
| 地图与面板 | 实时和历史共用轨迹颜色，12 色色板、RGB 滑杆、线条预览、持久化。按面板实际遮挡面积调整可见地图，使锁定位置居中。错开记录与文件、轨迹色及地图按钮。 | 接入两种现有地图，最终代码通过构建、单测和 Lint。 | 复核不同侧栏宽度和不同底图下的锁定中心、按钮可达性及重启后的配色。 |
| 姿态、归零与坡度 | 左视俯仰、后视侧倾、实时和回放读数；按用户实测修正后视侧倾绘制方向。归零重建展示坐标轴，可选直线向前起步标定车头 X 正向。坡度按车头向下为正的俯仰约定计算，－100 × tan(俯仰角)，上坡为正，保留一位小数。 | MountBasisTest 覆盖风挡立装和换轴，GradeTest 覆盖正负坡、水平、缺失及垂直边界。 | 坡度是车身俯仰换算值。待实车核对不同安装方式、水平归零、起步标定门限和道路坡度。实时标定只改变展示；历史回放尚未按标定生效时刻还原坐标系。 |
| 横竖屏与仪表 | 竖屏半开/全开左右翻页，横屏窄栏/展开栏上下翻页。窄栏展示仪表、成绩和俯仰/侧倾。旋转时保留当前页。竖屏地图、小屏、全屏分别对应横屏地图、小屏、全屏；横屏大屏转竖屏全屏。系统栏留出安全区。仪表运动读数统一一位小数，横屏字号为负号、三位整数及一位小数预留空间。 | 已根据多轮手机截图修订。最终版本恢复 Compose 原生上下拖动；横屏宽度足够时回放工具合为一行。 | 在最终包复验连续横滑、斜滑误触、上下拖动、旋转档位和系统栏边界。早期版本的交互反馈不能算最终验收。 |
| 性能与回放 | 横屏性能左页默认成绩、右页设置及状态；正常 PASS 在摘要中隐藏，保留质量提醒。回放曲线支持速度、俯仰、侧倾和三轴加速度、时间窗缩放及共享游标。进度条按下暂停，拖动预览时间，松手同步跳转。回放可设置多个速度边界并逐段输出结果。 | 核对离线计分直接读已关闭会话 samples.csv 中的原始 phone.gps，保留采样/回调单调时间、速度和质量字段，复用 PerformanceEngine.recompute；每个结果写新 result-vN.json。 | 曲线目前一次一个通道。待用同一趟行程对照实时与离线各段成绩、时间、距离和质量原因，复验长记录拖动。禁止用 ReplayFrame 或 UI 刷新流给成绩引擎供数。 |
| KML 互通 | 由仅经纬度的 LineString 更新为带 UTC 的 gx:Track，逐点导出原始 GNSS 海拔、速度及水平精度。速度扩展按现有 Win 导入器接受的 GxTrackExtendedData 编码，速度单位 m/s。旧记录可从手机重新导出。 | KmlTest 核对经纬顺序、速度与海拔、时间戳、缺失字段对齐、模拟点过滤和无 GNSS 情况。 | 待用最终包重导真实会话并在 Win 中打开核对曲线。过去导出的文件需从原始会话重新生成。 |

## Windows 只读诊断，另案交接

1. **昨天记录的前段没有红线。** 11 月 5 日会话 009d42a9-23e2-46dd-82af-1e7c784bd84d 的原始 CSV 有 1692 个连续 GNSS 点，最大相邻间隔约 1.02 秒。按 Win 的 TrackProjection.FilterGpsOutliers 规则复算，5 公里半径内有 1350 点，前 342 点被排除。截图中 15.768 km/h、－9.094 m 的样本能匹配到，距中位中心约 6.4 公里。地图线使用过滤后的点，游标使用原始样本。重新导出 KML 不会改变 Win 的画线规则。
2. **Time 切到 Test Elapsed 闪退。** Windows 应用程序事件 1026 在 16:57 和 17:13 两次记录同一异常。MainWindow.xaml.cs 的 ApplyDateTimeAxisIfNeeded（当时第 4454 行）试图给 ScottPlot 的 DateTimeXAxis 设置 NumericAutomatic。应在离开 Time 时切换整个轴类型；Windows 修改和回归另行安排。
3. **旧 KML 速度/海拔显示 0。** 原 Android 导出只写经纬度，Win 导入器对缺失通道使用 0。与前两项分别处理。

## 发布前操作

1. 运行 git status --short、git diff 并检查未跟踪文件。新实现 MountBasis.kt、ReplayCurves.kt，以及 GradeTest.kt、MountBasisTest.kt 均尚未跟踪，提交时需要纳入。核对两份当天交接文档。不要误收 local.properties、.toolchain、evidence、APK、私钥或构建目录。
2. 核对远端已有标签和 Releases，选用新的版本号及递增 versionCode，更新对应 release notes。正式发布的提交与标签应包含这批工作区增量；不要移动旧标签。今天没有发布或验证正式签名包。
3. 在 Android 工程根目录设置 JAVA_HOME 为 C:/Program Files/Android/Android Studio/jbr，然后运行下列离线验证。发布版本号或签名调整后重新构建，记录实际附件名称、签名方式和 SHA-256。
   
   ./gradlew.bat --offline --console=plain assembleDebug testDebugUnitTest lintDebug

4. 交接时最终 debug 构建验证通过，JUnit XML 共 50 项，失败、错误、跳过均为 0。安装命令 adb install -r 返回 Success。已装测试包位于 app/build/outputs/apk/debug/app-debug.apk，SHA-256 为 **9159A1DDD666CED9ACB29C39CBFD882BCC9234294CD037BD22D00F102314A126**。此摘要仅标识当天已装的 debug 包。
5. 发布说明可直接取用上表的已实现部分；对外分别标明单测、安装、只读数据复算和待验项。室外实车计分、坡度/起步标定以及新版 KML 的 Win 端到端互通仍待验收。
