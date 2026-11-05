# 高德地图切换

Google 卫星图保留；地图左侧按钮切换高德标准地图。首次使用需要阅读并同意高德 SDK 隐私政策。地图选择保存在本机。

Key 从被 git 忽略的 local.properties 中 AMAP_API_KEY 注入 manifest，不写入源码。当前依赖 com.amap.api:3dmap-location-search:11.3.100_loc11.3.101_sea9.8.1，graphics-path 显式升级至 1.1.0。旧 3dmap 坐标已停止更新。

高德使用 CoordinateConverter GPS 将 WGS84 转换为显示坐标，不修改 TrackPoint 或导出文件。未开启高德定位 SDK。切换地图会重建视图，视角暂不跨地图保留。

验证记录见 evidence/amap-build.log；真机鉴权/道路显示需要完成首次隐私同意后验证。RK3588 未实测。

2026-11-05：assembleDebug、testDebugUnitTest、lintDebug 通过（evidence/page16-proxy-build.log）。最终 APK 所有 64 位 ELF LOAD 段对齐 >= 0x4000，zipalign -c -P 16 4 通过。32 位高德库仍为 4 KB 对齐。已保留数据覆盖安装 SM-S9180，启动无兼容弹窗；手机 PAGE_SIZE=4096，不属于 16 KB 运行验收。新版高德不包含 x86/x86_64 地图库，因此 x86 模拟器不支持此高德模式，ARM64 三星与 RK 为目标。

