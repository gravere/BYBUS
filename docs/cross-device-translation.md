# 跨设备汉化初始化与竖屏布局

本次处理其他 Android 设备未正常汉化的报告。没有新设备运行日志，因此以下是代码中
可以确认的脆弱条件及针对性修复，不把本地验证称为所有设备故障已复现或解决。

## 从实际加载的 IL2CPP 模块解析接口

旧路径假设 `nativeLibraryDir/libil2cpp.so` 可直接打开，且 `/proc/self/maps` 中同路径、
文件偏移为零的最高地址就是运行中的库。APK 内直接加载、路径别名及重复只读映射都可能
破坏这些假设；接口解析失败会使后续文本接管完全不执行。

在 Unity 实际查询 `il2cpp_init` 时保留它提供的库句柄，借用原始 dlsym 查询该句柄，
不从 BYBUS 的 linker namespace 重新 dlopen。通过 dladdr 核对初始化入口属于精确的
`libil2cpp.so`，每个后续接口也必须属于同一模块基址。只允许 `il2cpp_` 接口；拒绝
空结果、其他模块及第二个同名库。句柄由游戏持有，BYBUS 不关闭它。

仍在原始 `il2cpp_init` 返回后读取 domain 和安装接管。官方签名、原生结构资格、
函数签名/长度、owned-patch 字节登记与完整 CRC 规则均不变。

## 字体与线程

优先使用经元数据验证的 `UnityEngine.Object.CurrentThreadIsMainThread()`：静态、
零参数、Boolean 返回且属于对应类。不再仅凭 init 线程猜测主线程；缺少该 API 时
保留原来的 init 线程回退，并记录所用判断方式。判断失败或后台线程不会创建字体。

字体准备只在符合上述条件的文本调用中进行，同一进程最多三次。重入及后台调用不消耗
次数；已创建的字体复用，只有字体创建和 fallback 注册都成功才报告可用，失败保留原文。
运行确认区分索引、文本接管和字体阶段，避免把接管安装等同于实际中文已显示。

## 平板竖屏

根据应用实际可用窗口 dp 判断布局：宽度不大于高度时一律单列，包括大平板和分屏。
横屏同时满足最小宽度及字体缩放可读空间时才用双列。竖屏平板的项目状态接在启动区
下方，沿同一个页面滚动；保留现有配色、组件和二级菜单尺寸。

## 验证

以主机模拟实际句柄解析，覆盖独立库/APK 路径、不可读文件、不同模块、丢失接口及重复库；
提取真实字体准备函数验证主线程、失败重试、资源复用及次数上限。窗口尺寸逻辑与运行
确认文本做少量定向 JVM 回归，再检查正式 NDK 构建、签名和增量包。真机显示仍未复测。

Unity 接口依据：[UnityEngine.Object 源码](https://github.com/Unity-Technologies/UnityCsReference/blob/master/Runtime/Export/Scripting/UnityEngineObject.bindings.cs)。
