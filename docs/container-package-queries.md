# 容器应用身份查询

系统 PackageManager 调用与容器查询使用不同的用户编号。容器注册表继续使用虚拟用户；
仅在调用真实系统接口时，将请求的当前虚拟用户转换为宿主所在的 Android 用户。
这也适用于创建宿主占位服务时的包信息、组件及 Provider 查询，避免工作资料或隐私空间
中的应用访问系统用户 0。转换按 AIDL 方法名与完整参数类型核对，不寻找“最后一个整数”；
查询标志、UID、显式请求的其他用户及未知厂商签名保持原样。原系统异常不被吞掉。

安装来源查询不再将所有包名一律标记为 Google Play。已从系统导入的游戏读取实际安装
来源；仅存在于容器的组件没有系统来源，返回空来源。未知或不可见的包按 Android
约定抛出 `IllegalArgumentException`，不伪造安装渠道或禁用状态。来源与签名、完整分包
校验分别处理；年龄分级图形不能代替实际安装来源。

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

修复后的同一生产代理通过 Android 11（8 项）及 Android 17（13 项）的公开 Java/JNI
用例，包括旧式参数、新版 Flags、原生附加线程、跨进程注册记录，以及空／单项／多项
具体平台类型的 Parcel 往返；应用元数据保留，临时注册记录与进程身份在结束时恢复。
证据：[修复后运行记录](https://github.com/gravere/BYBUS/actions/runs/42864872885)。
这些用例运行于普通应用 UID；测试夹具只进入 debug 构建，正式 APK 不包含夹具原生库。

游戏构建 488 的只读分析显示，其 70036 路径会检查上述列表查询的 JNI 异常、列表长度
与包名读取结果。此次修复消除了在 Android 17 实际复现的查询异常，未更改游戏检查、
注册成员或包可见范围；该云端用例不启动游戏，受影响真机的完整启动仍需实际运行确认。

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

这些是容器接口自身的正确性修复。70036 还可对应其他查询失败，不能把
接口回归检查通过等同于所有设备的真实游戏启动恢复。未知保护结构和游戏 APK 不在此次修改范围。

参考：[Android PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager)、
[AOSP ApplicationPackageManager](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/app/ApplicationPackageManager.java)。

组件启用状态查询按实际注册的包及虚拟用户读取。`null` 组件保留平台的默认值；未知或该用户
未安装的包抛出 `IllegalArgumentException`，不能创建一条默认状态后返回成功。已安装包中
没有显式设置的组件返回 DEFAULT，这与组件在 manifest 中是否 enabled 是两个概念；
不能额外要求查询的类名必须存在。允许访问的系统组件仍查询系统，其异常原样传回。

状态管理器创建时加载已有持久文件，单次状态查询不创建记录。文件在每个用户的 Map 前有单独的条目数，读取时必须
消费并核对该整数，再读 Map；文件格式保持不变，避免重启后丢失组件开关。

依据为固定版本的 [Android 15 ComputerEngine](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/pm/ComputerEngine.java)
及 [Settings](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/pm/Settings.java)。
这些查询约定在 Android 14 已存在，不能仅凭设备使用 Android 15 就把退出归因于新增限制。
诊断只增加组件查询的结果分类，以及带时间和 PID 的固定退出事件，不导出参数或原始安全日志。

启动诊断为每个游戏进程保存前 90 秒、最多 64 次支持的包查询的开始和结束，包含顺序号、
方法、容器/系统路径、耗时和固定结果类别。观察器失败不能改变原结果；不读取查询参数。
JNI 后备类加载只保存类别和成功/失败类型，最多 32 次。启动首分钟增加有限内存采样，
随后恢复原频率；已读取的游戏退出事件即刻去重保存，避免导出时 logcat 已滚动而丢失时间。
诊断包列出采集范围和不可用项，不把 Android 未提供的信号发送者或系统栈伪装成已获取。

引用系统 APK 的虚拟安装还必须保留真实安装包的现代签名及分包元数据。返回 `PackageInfo`
时，使用未接管的系统 PackageManager 读取同包信息，并核对缓存版本、当前签名、base 路径
以及有序分包路径；任何一项不一致都不能拼接两次安装的数据。只补充已核对的分包名称、
修订号和请求标志要求的签名信息，不替换虚拟 ApplicationInfo、组件、权限或用户状态。
签名历史由 Android 的 SigningInfo 原样提供；未请求签名时不返回它。不将容器内独立安装
或已配置不同签名的组件套用系统另一份安装，查询失败也不伪造签名或检查成功结果。
这条路径按每次实际安装查询，不持久化系统路径，避免系统更新后复用旧分包与证书。

依据：[PackageInfo 的签名与分包约定](https://developer.android.com/reference/android/content/pm/PackageInfo)、
[SigningInfo](https://developer.android.com/reference/android/content/pm/SigningInfo)。
