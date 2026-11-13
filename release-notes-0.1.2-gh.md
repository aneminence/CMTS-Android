# CMTS Android 0.1.2 发布说明

手机端 Standalone：实时预览与录制、姿态归零与坡度、横竖屏交互、轨迹选色、回放曲线与原始 GNSS 离线计分、KML 速度/海拔导出。

## 本版亮点

- **实时预览与录制**：App 可见即可绑定采集服务刷新仪表；显式「开始记录」才建会话。记录行左滑可删已保存/中断会话（保护当前写入目录）。
- **姿态归零与可选起步标定**：归零重建展示坐标轴；可选直线向前起步标定车身 X 正向。左视俯仰、后视侧倾可视化，旁侧实时角度。
- **俯仰换算坡度**：按车头向下为正的俯仰约定，100 × tan(俯仰°)，上坡为正，一位小数。坡度为车身俯仰换算值，非独立坡度传感器。
- **横竖屏交互**：竖屏半开/全开左右翻页；横屏窄栏/展开上下翻页。旋转保留当前页；系统栏安全区；仪表运动读数一位小数。
- **轨迹选色**：实时与历史共用 12 色色板 + RGB 滑杆，线条预览并持久化。
- **回放曲线**：速度、俯仰、侧倾、三轴加速度；时间窗缩放与共享游标；进度条拖动预览时间。
- **回放离线计分**：可设多个速度边界并逐段出结果；直接读已关闭会话 samples.csv 原始 phone.gps，复用 PerformanceEngine.recompute，结果写 result-vN.json（不覆盖原始）。禁止用 UI/回放抽样当成绩输入。
- **KML**：gx:Track 逐点导出原始 GNSS 海拔、速度（m/s）与水平精度；旧记录可从手机原始会话重新导出。

## 验证状态（请分别阅读）

| 类别 | 状态 |
|---|---|
| 离线构建 assembleDebug | **通过**（clean 后全量 --offline） |
| 单元测试 testDebugUnitTest | **通过**：50 项，失败/错误/跳过均为 0（含 GradeTest、MountBasisTest） |
| Lint lintDebug | **通过** |
| 装机 | 交接日曾用改版本号前的测试包覆盖安装到 SM-S9180；**本 Release 附件请用户按需 adb install -r 再验** |
| 室外实车验收 | **未宣称通过**；计分、坡度/起步标定、新版 KML↔Win 端到端仍待路上验收 |

### 附件指纹

- 文件：CMTS-Android-0.1.2-debug.apk
- SHA-256：61ED30CCEA1E4EC28F80590582CC921489AA09A9E7BD1ADEBBA953B476152485
- 签名：Android Debug（证书 SHA-256 a4b15392d4ae4c7edb9404e195d1a4cc2b8ca9c08aa340985a677ccd5181e3da），与本地 0.1.1 debug 包证书一致，可覆盖升级
- aapt：com.cmts.mobile / versionName 0.1.2 / versionCode 3 / label CMTS

## 重要声明

- 插值仅用于估计穿越时刻与制动距离积分，**不宣称提高 GNSS 测量精度**。
- 质量门限为调参建议，不是已验证规格。
- Windows 客户端只读诊断（地图红线过滤、Time→Test Elapsed 闪退等）见交接文档，**不在本 Android Release 修复范围**。
- 过去仅含经纬度的 KML 需从手机**原始会话重新导出**后再在 Win 中打开。

## 安装

- 附件为 **debug 签名** APK（与 0.1.1 同为 Android 默认 debug 密钥库），可用 adb install -r 覆盖升级并保留数据。
- 安装前确认手机**未在录制**；开启「安装未知应用」。
- 包名 com.cmts.mobile，显示名 **CMTS**，versionName 0.1.2 / versionCode 3。

## 资源

- 文档：[README](https://github.com/aneminence/CMTS-Android#readme) · docs/GROKBOT_HANDOFF_2026-11-06_RELEASE.md
- 构建：仓库根目录设置 JAVA_HOME 后 ./gradlew.bat --offline --console=plain assembleDebug
