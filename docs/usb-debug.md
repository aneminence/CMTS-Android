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
