# USB 调试集成

入口：主页面底部面板 → USB 设备调试。页面关闭或旋转重建时停止接收；不承诺后台锁屏长期记录（尚未实现 USB 前台服务）。后台线程持续读取，不在 UI 线程执行串口读取或文件写入。

用户 JAR SHA256: 12869751A6A26B57066F9C68059B87DE74D00C88147BC4DDFF43ADF5618BFD6A。
入口为 cn.wch.uartlib.WCHUARTManager，使用 init(Application)、enumDevice、openDevice、getSerialCount、setSerialParameter(device, port, baud, 8, 1, 0, false)、enableSerial、readData、disconnect。没有使用旧 CH34xUARTDriver API。jdeps 发现的外部类属于 Android 和 Java 标准库，JAR 不含 .so。完整字节码审计位于本地 evidence/wch-*.txt。

USB Host API 枚举所有设备，驱动 enumDevice 再判定可连接范围，不人为注册未知 VID/PID。设备型号判定只表示 USB 桥接芯片，不能认定下游是 UM982。应用无串口发送 API 调用；设置 8N1/波特率是 USB 桥接器配置。注意供应商 disconnect 内部可能复位串口至 115200、清除 DTR/RTS；接线时不要把这些控制线用于未知复位功能。

数据自动保存在应用私有 files/usb/usb-时间戳.bin；断开后通过系统保存选择器导出。显示窗口最多 4096 字节，100 ms 更新；文件保存原始字节，不做字符解码。尚未接入 NMEA/Unicore 解析及现有 VBO。

待真机验收：三星 OTG 枚举、拒绝/允许权限、各波特率持续接收、原始文件校验、拔插重连、长时间满速无丢字节、页面返回/旋转回归。RK3588 Android 12 同样需实测。构建成功不代表这些验收已通过。

## 2026-11-04 三星锁屏诊断
实机 SM-S9180 旧版本复现：锁屏后 CaptureService 仍为 isForeground=true，CMTS:capture 唤醒锁为 DISABLED，CSV 停止增长；亮屏恢复。系统 low_power=1；单独加入电池优化白名单未解决。用户明确授权关闭省电模式后，锁屏唤醒锁恢复 ACQ，CSV 持续增长。保留 CMTS 电池优化豁免。
本次改进：录制初始化前获取唤醒锁；优先使用唤醒型传感器（无则回退）；主页面提供省电模式警告及系统设置入口。没有绕过系统强制停止，也不承诺省电模式下连续记录。USB 页面仍为 Activity 生命周期，USB 后台服务尚待实现。
构建、现有单元测试、Lint 通过：evidence/lockscreen-build.log；已 adb install -r 更新手机，无清除数据。

## 2026-11-11 CH344Q 0 字节排查

现象：CHIP_CH344Q（VID 1A86 / PID 55D5，8 个接口 0–7 交替为 CDC 控制/数据）已连接 115200 8N1、串口索引 0，UI 显示正在保存 usb-*.bin，但已接收 0 字节。Note20 上已复现空文件 iles/usb/usb-1794366105790.bin。

驱动结论：WCHUARTManager 把串口索引 n 映射到接口对 (2n, 2n+1)，即 UART0–UART3；索引 0 不是控制接口本身，打开/claim/读线程在 openDevice 后对全部口都会启动。enableSerial 对 CH344Q 为 no-op（官方说明仅 CH9114 需要）。
eadData 从内部缓冲区轮询，本身可用。

更可能的产品原因：紫板 UM982 接在 UART0 以外的口，页面默认只配置并读取索引 0。无天线时模块仍可能输出状态或空 NMEA，但接错口会是 0 字节。

本次改动：连接时对全部串口设置波特率并同时计数；文件按 usb-时间戳-uartN.bin 分口保存；UI 标明接口角色、显示各口字节数，并可在「全部监听」下自动把预览切到第一个有数据的口。可选拉低 DTR，默认关闭。


## 2026-11-11 主界面定位源与后台接收

主界面底部面板增加「定位源：手机 GNSS / 外接 UM982」。选外接后由 CaptureService 前台服务打开 CH344Q（115200 8N1，监听全部 UART，优先使用输出 NMEA 的口，实测紫板 UM982 在 UART3），解析有效 GGA/RMC 后写入与手机 GNSS 相同的 `phone.gps` 路径。空 GGA、RMC 状态 V 或无效校验和不会生成定位。离开 USB 调试页或切到 Dashboard/地图时，只要定位源仍是外接，接收会继续。USB 调试页与外接定位共用同一个 USB 会话；仅调试页打开时，返回仍会停止接收。应用不发送 UM982 配置指令。默认定位源仍是手机 GNSS。

## 2026-11-11 CH340 直连与授权状态修复

支持板载 CH340（1A86:7523）作为外接定位候选，保留 CH344Q；优先复用当前 USB 会话，无会话时优先 CH344Q。USB 桥接芯片 ID 不能证明下游设备身份，仍需有效 NMEA 数据。

调试页和主界面分开权限回调 action，使用不可变 PendingIntent 内预置的设备名称定位请求对象，以 UsbManager.hasPermission 实际状态为准，不再将缺失的 EXTRA_PERMISSION_GRANTED 误判为拒绝。授权设备集合使用 Compose 状态，回调、刷新及返回页面时更新，避免设备列表不变导致连接按钮不刷新。已授权时不重复请求，并给出明确提示。

验证：assembleDebug、testDebugUnitTest、lintDebug 通过（evidence/usb-ch340-fix-build.log）。ADB 确认 Note20 上枚举到 1A86:7523，检查时尚无 USB 授权；系统弹窗、CH340 实际收数和后台定位尚需真机操作验收。

## 2026-11-11 CH340 零字节真机诊断

Note20（SM-N9860）上实际枚举并授权 1A86:7523；WCH 驱动成功打开 CHIP_CH341，单串口。真机 instrumentation 在 115200、230400、460800、921600、9600、38400、57600 各读取约 2.5 秒，全部 0 字节；115200 下 DTR=true 读取 3 秒同样 0 字节。随后独立 claim USB 接口、启用 CH340 RX/TX、关闭流控并直接 bulkTransfer，以上七种速率仍全部 0 字节。没有向 UM982 发送串口命令，未证明模块配置或板上跳线正确；需核实板载 USB 到模块 UART 的接线/跳线、模块供电及输出配置。诊断源码留在本地 evidence/UsbProbeInstrumentation.kt，不作为默认测试 runner。

修正调试页复用后台会话时参数不生效：展示当前会话参数，新增“应用参数（同时更新后台定位）”，由接收线程实际调用驱动更新波特率和 DTR。主界面竖屏实时收起面板从 328dp 收紧为 248dp，去掉卡片下方多余留白。

## 2026-11-11 零字节原因与配置保存

后续 Windows COM6、115200 查询 CONFIG 正常回复，VERSIONA 确认 UM982 R4.10Build7362；单次 GPGGA/GPRMC 均响应。当前口缺少周期 NMEA 输出是本次零字节现象的原因，之前仅监听为零不能据此认定硬件或驱动损坏。临时发送 GPGGA 1、GPRMC 1 后 6.5 秒收到各 7 条；用户明确要求保存后执行 SAVECONFIG，回复 OK。仍为未定位报文。手机换接与断电保持待复测。应用不自动发送模块配置命令。

## 2026-11-12 CH340 current-port output raised to 20 Hz
User requested higher output rate and connected board to PC. COM6 VID 1A86/PID 7523; VERSIONA confirmed UM982 R4.10Build7362. Issued GPGGA 0.05 and GPRMC 0.05 to current port; both replied OK. Received 100 GGA + 100 RMC in 5.012 seconds (5300 bytes, empty no-fix sentences). SAVECONFIG replied OK. Port closed. Valid-fix 20 Hz and phone compact-panel rendering still require antenna lock / phone reconnection verification. No baud or other UART settings changed. Evidence: evidence/um982-20261112-*.txt.
