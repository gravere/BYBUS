# 容器应用身份查询

安全提示前的查询摘要同时保留各受支持方法的累计次数和最近 32 次结果。累计次数按
固定方法白名单与容器／系统路径归类，计数饱和，不保存参数或扩大查询。这样不会因后续
包信息查询覆盖环形缓冲区，而把较早的权限查询误判为从未发生。统计仍不能判定 70036
的具体触发原因，不能作为修改安全判断的依据。
已安装应用列表另区分空列表、单项与多项；只读取已有返回对象的长度，不保存包名、
具体数量或列表内容，也不额外查询系统。无法展开的列表仍记为 present，不能当成有效多项。

定位列表故障时，独立云端用例从 Android 公开 PackageManager 经生产代理、跨进程 Binder
读取临时注册记录，并在 Java 线程与 JNI 附加线程分别调用旧式 int 与新版 Flags 接口。
JNI 使用游戏构建 488 所用的 GET_META_DATA、List.size/get 及包名字段访问约定；不加载
游戏保护库、不修改其检查结果。该用例只证明接口链路，不代替实际游戏启动验证。

Android 17 的 `IPackageManager.getInstalledPackages` 返回 `PackageInfoList`，它继承
`ParceledListSlice<PackageInfo>`。以返回类严格等于 `ParceledListSlice` 判断类型，会误走
普通 List 分支；Java 动态代理随后在返回位置转换失败。这发生在 InvocationHandler
返回之后，因此旧的内部查询摘要可以是 present，而公开 API/JNI 实际收到异常。
列表适配须识别继承关系并按接口声明的具体返回类构造，保留空列表、真实成员及元数据；
不按 Android 版本猜类型，不把构造失败吞成空列表。安全检查及宿主包可见范围保持原约定。
父类中的公开 `getList()` 也必须能读取；仅扫描子类 declared methods 的旧镜像在这里会
缺失方法。读取与构造均保留具体平台类型，不能用兼容读取掩盖错误的返回类型。

修复前的独立 Android 17 用例已复现 `ArrayList` 到 `PackageInfoList` 的
`ClassCastException`，栈位于系统 `ApplicationPackageManager.getInstalledPackagesAsUser`。
证据：[修复前运行记录](https://github.com/gravere/BYBUS/actions/runs/42862487414)。

依据：[AOSP Android 17 PackageInfoList](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/core/java/android/content/pm/PackageInfoList.java)、
[IPackageManager](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/core/java/android/content/pm/IPackageManager.aidl)。

应用身份查询必须保持 Android API 的返回约定，不能用“查询失败时返回当前游戏”或
“两个参数相等就签名匹配”代替实际注册记录。

- UID 查询只返回对应虚拟用户已安装的应用。无效、未知或该用户未安装的 UID 返回
  `null`；未知包返回 `-1`。共享 UID 保留全部已安装成员。
- 游戏使用真实进程 UID 的现有约定同时适用于单包、批量列表及嵌套组件信息。反向查询自身
  UID 时使用当前虚拟进程的身份，不能取碰巧正在调用它的 Binder 客户端身份。
  Google 等其他访客保留其服务鉴权需要的 Binder 调用方归属规则。
- 签名比较必须先找到双方应用；不存在的包、未知 UID 不得返回签名匹配。保留
  实际签名比较以及容器 Google 组件既有的受限签名配置。
  同时接入旧式双参数与新增用户参数的三参数平台接口，避免新版系统落回另一份
  系统安装的记录。用户限定使用容器身份，不直接采用宿主用户参数。
- `VersionedPackage` 查询使用与普通包查询相同的容器记录和可见性规则，并检查
  请求的版本；它不能直接读到另一份系统安装的元数据。
- 启动诊断仅保留最近的有限应用查询结果分类，不记录查询包名、UID、权限名称、
  参数、签名或异常消息。游戏主进程发起已有安全提示时输出摘要，不改变原调用、
  返回结果、提示或进程退出行为。诊断失败不能阻断业务调用。

这些是容器接口自身的正确性修复。现有 70036 诊断没有给出具体失败调用，不能把
接口回归检查通过等同于真实游戏启动恢复。未知保护结构和游戏 APK 不在此次修改范围。

参考：[Android PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager)、
[AOSP ApplicationPackageManager](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/app/ApplicationPackageManager.java)。
