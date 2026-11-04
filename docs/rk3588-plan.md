# 下一阶段

先完成手机端构建和 P1 真机验收，再通过授权 ADB 读取 RK3588 Android 版本、ABI、USB Host 能力，安装同一 APK。保留供应商 APK，不刷机、不 root。

枚举 USB VID/PID，确认 USB-TTL 芯片和串口驱动许可证，申请 Android USB 授权，验证是否被供应商占用。先保存带时间戳原始字节，再依据官方 UM982 文档和实测输出解析 NMEA。RS485 需明确电气接口、波特率及协议。Remote 后续采用版本化协议，并确保断网不停止主机记录。
