# Android 设备适配

本轮把设备基础条件、游戏二进制资格和实际运行结果分开。目标为 Android 8.0+
的 arm64 手机和平板，不根据厂商、机型或屏幕尺寸决定是否可启动。32 位系统、x86
和 Android 8.0 以下不在当前原生容器的支持范围，不能用“全安卓”掩盖这些边界。

## 启动条件

- 基础条件为 API 26+、64 位 ARM 进程和实际查询到的 4 KB / 16 KB 内存页。
  不再只接受 API 36。未知页大小仍拒绝，不猜测为 4 KB。
- 自动兼容族按官方签名、原始 ELF 布局、现有保护代码指纹判断，名称不绑定 SDK。
  检查每个游戏 arm64 库的加载段是否满足设备页大小，读取头部而不再完整解压一遍。
  16 KB 设备不依赖系统可选的旧页兼容模式，也不改游戏 APK。
- 471 的精确版本、SDK 36 和 4 KB 例外原样保留；未知代码族仍拒绝。
  原游戏 CRC、完整范围重算和仅归一化本进程登记修改的规则不变。
- Android 特有的账户权限、隐藏 API 和 Binder 适配继续按实际 API/成员能力选择，
  不把 Android 16 的权限例外扩展到旧系统，不用手机品牌作为功能判断。
- 清单中相机、蓝牙、位置、麦克风、电话与 Wi-Fi 硬件为可选。容器声明的访客权限
  不能隐式排除无蜂窝网络或无相机的平板。主页明确允许窗口调整，布局使用窗口 dp。

## 原生运行时

统一页范围计算，校验空指针、零长度、页大小及整数溢出。inline hook 只授权真正
要写入的指令范围，不再额外授权固定 4096 字节或因 mprotect 失败 assert 终止。
跳板池按支持的最大页边界隔离并在使用前检查权限设置结果；失败不修改目标函数。
AArch64 ADRP 的 4 KB 指令语义、mmap2 的 4096 字节偏移单位不能替换为内核页大小。
renameat/renameat2 根据实际入口指令选择，避免厂商将短跳转包装回移到旧系统时，
仅按 API 号选择错误入口并覆盖相邻函数。
信号诊断使用提前初始化的真实页大小。老内核忽略 MAP_FIXED_NOREPLACE 时，必须
核验返回地址，不覆盖已有映射，不把远距离地址误认作可用的近跳板。
映射处理只识别本进程登记的 BYBUS 跳板页，登记失败则不安装跳板；不把任意同大小的
匿名可执行映射认作 BYBUS 所有，失败分支释放页面时同时撤销登记。

## 验证与交付范围

定向检查 API 26—36、4 KB / 16 KB 与不支持的设备条件，使用现有原始游戏库核验
结构与加载段，同时覆盖坏 ELF、错误对齐和指纹改变的拒绝路径。原生页计算与
跳板写入边界用本地 C++ 检查，正式 APK 做 NDK 编译、签名、所有打包库及内置
Google APK 的对齐检查。未连接用户设备；这些检查不等于所有 ROM 的 Google
网络登录、游戏页面或实际支付均已实测。新系统仍可能因私有接口变化需要升级。

当前固定签名的 microG v0.3.17.252432 仍是上游最新发行版。其六个 arm64 库中，
Conscrypt、Cronet、Mapbox、图像处理和 libc++ 五项未满足 16 KB 对齐。上游
ProviderInstaller 捕获加载失败并保留系统 TLS，常规认证 HTTP 走平台连接；这只能
说明存在回退路径，不能据此宣称完整登录/付款可用。BYBUS 保留原组件与账号数据，
在 16 KB 设备兼容报告显示限制，不重签 Google APK 或修改 TLS 校验来掩盖问题。

v1.0.8 的实际本地结果：18 项定向 JVM 测试通过、无跳过，覆盖设备条件、原生
结构、旧精确配置及登录路由；主机 C++ 页范围、跳板归属、短入口和完整性回归通过。
生产 Java 头部校验器对本地原始游戏的 19 个 arm64 库均通过 4 KB / 16 KB 检查。
正式 NDK 构建通过；Android lint 为 0 错误、29 个已有警告；完整包签名及资源摘要
通过。三个差异包均由生成器重建核验，另用旧 build48 的实际 Kotlin 解码器重建
本版，结果与签名完整包逐字节一致。以上不是 Android 真机执行测试。

依据：[Android 页大小指南](https://developer.android.com/guide/practices/page-sizes)。
上游：[microG 发行版](https://github.com/microg/GmsCore/releases/tag/v0.3.17.252432)、
[ProviderInstaller](https://github.com/microg/GmsCore/blob/v0.3.17.252432/play-services-conscrypt-provider-core/src/main/java/com/google/android/gms/providerinstaller/ProviderInstallerImpl.java)。
