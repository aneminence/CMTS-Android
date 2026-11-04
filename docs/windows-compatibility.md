# Windows 兼容性

已检查现有 csproj 和 Core/ChannelIds.cs，Windows 使用 lat / long 通道标识，存在 Communication/UdpPacket.cs。尚未完成导入器的单位和文件结构逐字段核对。

Android CSV 是显式版本化的新采集格式，**未验证 Windows 可直接导入**。不冒充已有格式。下一步检查现有导入器后制作独立转换工具和固定样本测试，保留原始 Android 时间戳及单位，不静默修改 Windows 协议。
