# 架构

Model.kt 定义统一样本和 SensorDataSource 扩展契约；CaptureService 当前直接适配 Android 传感器 API，后续应抽取 PhoneDataSource 实现契约。MainActivity 显示进程内 Compose 状态并通过 SAF 导出。未实现完整 MVVM 分层。

采集和写入在 HandlerThread，主线程更新显示。前台 location 服务由可见 Activity 发起，START_NOT_STICKY 避免进程恢复时未经用户操作重新采集。屏幕旋转不终止服务。WakeLock 在服务销毁时释放，但系统强杀仍可造成尾部未刷盘数据丢失。每 50 条刷盘，不保证断电原子性。

USB UM982、RS485、远程适配器应实现 SensorDataSource，保留原始来源、时间和质量。当前仅预留契约，不宣称已接入。
