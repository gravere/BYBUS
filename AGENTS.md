# Limbus Company 汉化器 - 协作约定

本文件只保留当前必须遵守的架构边界和调试入口。详细背景拆到:

- `docs/project-architecture.md`: 模块、数据流、补丁管线
- `docs/container-runtime-notes.md`: VirtualApp 接入和设备实测
- `docs/runtime-debugging-notes.md`: 运行时排障记录
- `docs/commercial-container-reference.md`: 商业容器参考结论

方案级变更先更新本文件或对应 docs, 再改代码。

## 项目边界

- 目标游戏包名: `com.ProjectMoon.LimbusCompany`
- 汉化器包名: `com.example.limbuszhcn`
- 主路径: VirtualApp 容器模式。同步系统已安装的官方游戏 APK/splits 到容器（不限安装渠道），配对日文原文与零协译文生成宿主持久索引，从容器内启动游戏并接管显示文本；不覆盖游戏原始 JSON。用户选择保留系统安装包，大资源只在 BYBUS 使用；本轮不切换独立容器模式。多渠道查询、本地包校验安装和快照去重边界见 `docs/multichannel-game-updates.md`。
- 禁止修改游戏 APK, 禁止 Frida 注入, 禁止 Shizuku 生产路径。
- App 模块: `:app`; 容器模块: `:virtualapp` -> `third_party/virtualapp-upstream/lib`。
- UI 不直接访问真实 `Android/data`; 安装、卸载、复原必须走 `GameStorage`。
- JSON 补丁只能结构化修改明确的显示文本字段。基础字段为
  `content/dialog/dlg/teller/name/nameWithTitle/desc/description/title/summary/flavor/place`;
  `story/relatedChapterText/openConditionNumber` 等新增显示字段也须加入共享的
  `TranslationTextPolicy.DISPLAY_FIELDS` 白名单,并同步到 native 对象字段接管层后才能启用。
  不改 `id`、`personalityid`、`voicefile`、`usage`、`model`、图标键等元数据。
  `successDesc/failureDesc/result` 的纯字符串数组在父对象身份匹配后按相同长度配对；运行时只通过完整签名核对的 System.Array/List 方法读写，不推测托管内存偏移。集合元数据缓存按线程隔离。
- Token、签名口令、账号信息等敏感配置不得写死进源码或文档；汉化包只允许从公开的 GitHub Releases 获取。

## 容器硬约束

- 容器启动必须走 `VirtualCore.getLaunchIntent(packageName, 0)` + `VActivityManager.startActivity(intent, 0)`, 不得回退宿主 launcher。
- Limbus 可见 `ApplicationInfo` / `LoadedApk` 的 `sourceDir`、`splitSourceDirs`、`nativeLibraryDir`、`dataDir` 必须与系统真实安装源和容器重定向一致。安装来源是展示/更新入口信息，不是签名或兼容性证明。
- Limbus 通用 PackageManager 查询默认隔离宿主环境; 宿主 Google 包只允许按白名单精确暴露。
- 2026-09-30 用户接受容器内独立 Google 计费环境和本人重新登录/授权。游戏 Billing 必须绑定
  容器 Companion，不回退宿主 Play Store，不改写游戏业务包名；宿主 Play 的真实 UID 校验
  已证明阻断旧直连方案。Google 组件使用固定签名版本、保留数据升级，不通过卸载清空账号。
  商品可见、购买初始化、结算页、实际到账分开验证，不伪造或隐藏服务错误，不自动付款。
  Android 16 的 ACCOUNT_MANAGER 兼容仅可安装到容器 GoogleLoginService 的
  IAccountAuthenticator Binder，且只认可同宿主 UID、确切虚拟账号管理服务 PID。
  不给宿主或其他访客授予系统账号权限。容器 Google 组件保留业务包名；读取容器
  GServicesProvider 时可保留真实 Binder UID 与准确 Google 包名，宿主 Provider 不适用。
  Android 16 的 authenticator 是 oneway 接口，调用 PID 为 0；不能因此放宽身份检查。
  仅虚拟账号管理服务到上述 authenticator 的客户端 Binder 使用同步传输，并移到有界
  后台队列，保留原账号结果回调；其他 Binder 不改，异常必须返回而非等待超时。
  microG SettingsProvider 只在其主进程持有，UI 必须走同一 Provider 的 Binder，不能为
  绕过空客户端在 :ui 复制本地实例（SharedPreferences 缓存会分叉）。Android 16 宿主
  占位 Provider 的空 Binder 不代表目标虚拟 Provider 不可用；精确查询后再判断失败。
  已实测 Companion 的 GServices android_id 查询为空而注册页已成功。只对 Android 16+
  容器 Companion 发给虚拟 GServicesProvider 的单键根 URI 查询，可在原结果为空/零时
  从同一虚拟 GMS 的 SettingsProvider 读取已启用且成功注册的真实 ID；保留 uint64 位模式。
  不生成标识，不读取宿主 GSF，不访问账号/Token，不替换已有有效值，不扩大到其他调用方。
  内部版本 33 已在 vivo / Android 16 实测恢复商品价格：两批商品查询返回 0，共 39 项
  详情，账号与资源保留。用户已能打开结算页，并纠正了账号/币种疑虑，确认两者一致；
  退出后仍卡在连接中。游戏自身发给自身的精确 `PURCHASES_UPDATED` 广播须按容器已注册
  接收器的同一受保护 action 转发；仅接管无显式组件、目标包为游戏自身的结果通知，
  不改返回码、账号、金额或票据，不制造取消/成功结果。真实付款仍单独验证。
  Companion 的精确结算 PendingIntent 不增加 NEW_TASK，也不移除其不可变属性。
  发起 Activity 经虚拟记录确认属于游戏后，宿主桥接页必须持有子结算页面直到返回，
  原样转发 Activity 结果，重建时不得重复开启结算；不能立即 finish 丢失返回链。
  code34 的 Activity 返回已发生，但用户仍卡住，取消流程未通过。Billing 8.3 本地结果
  使用 `LOCAL_BROADCAST_PURCHASES_UPDATED`，也须接入相同受保护广播。仅游戏自身发出的
  这两个精确结果 action 可把现有 Application context 的宿主目标别名归一到游戏；
  其他虚拟应用不能借宿主目标向游戏发结果。receiver 注册参数按方法签名定位，
  permission 为 null 不能回落旧索引并误清 receiver；原有导出标志保持不变。
  code35 已在同一真机确认恢复本地接收器，真实取消码 1 发送并到达游戏，用户确认退出
  结算后可正常操作。商品显示、结算打开和取消已验证，实际扣款与到账仍未验证。
- PlayCore asset pack 必须通过注入 `local_testing_dir` 走 `FakeAssetPackService`, 不得绑定宿主 Play Store `AssetModuleService`。
- GMS broker 请求必须通过 `ServiceConnectionDelegate` 改写残留调用包名, 避免 `Unknown calling package name 'com.ProjectMoon.LimbusCompany'`。该改写同时适用于宿主 GMS 和容器内安装的 microG,不得因 `com.google.android.gms` 已虚拟安装而跳过。
- Limbus Credentials `HiddenActivity` extras 含 GMS Parcelable 和 Binder, 不得经过 VAMS server 解包。必须在游戏进程内包装为当前 vpid 的 host stub Intent 并调用系统 `ActivityTaskManager`; 目标 Activity 仍由 `AppInstrumentation` 使用游戏 classloader 创建。该瞬态 Activity 没有 VAMS `ActivityRecord`, create/resume/finish/destroy 不得登记到 VAMS 任务栈, 但系统 token 和 Activity result 链路必须保留。
- 容器内 microG Services/FakeStore 与 Limbus 一样不得进入旧式 ART `jmethodID` 内存改写 VM hook；它们所需的 IO 重定向、Binder 身份和 Activity result 兼容均由独立层完成。Firebase Apple/验证码浏览器回调只允许由宿主精确接管 `genericidp://firebase.auth/` 与 `recaptcha://firebase.auth/`,再显式转发到容器内 Limbus 对应 Activity；不得把其它 URI 或浏览器 extras 带入容器。
- Limbus 原生 DNS 与 socket 连接必须绕过 VirtualApp 域名/IP 策略。允许有限日志诊断, 不得修改测试机 VPN、代理或路由。

## Native / AppSealing 约束

- 2026-10-05 用户要求同结构后续版本自动适配。按 `docs/automatic-native-compatibility.md`
  在官方签名、ELF 加载边界及已验证代码族指纹成立时解析当前节位置；允许使用已验证
  游戏校验器提供的预期 CRC，但完整重算及 owned-patch 边界不变。不得仅去掉版本门禁，
  不得从当前已修改内存学习可信 CRC，未知保护结构仍拒绝。471 的专属例外不扩展。

- Limbus native 终止路径只做容器进程内轻量拦截; `SIGPWR` / `SIGXCPU` 必须放行。
- `SIGSEGV` 仅可忽略 `si_code <= 0` 的用户主动投递; 同步内存错误必须交回原 handler。若下游 handler 返回后 PC/SP 均未改变,表示它没有消费当前同步故障,必须恢复默认处理并终止,不得返回原指令形成信号/日志死循环。
- libc hook 必须保持公开 API 返回值语义。例如 `getcwd()` 成功时返回调用方 buffer 指针, 不能暴露 raw syscall 长度。
- native 路径伪装必须覆盖 `readlinkat` / `syscall(__NR_readlinkat)` 和 `/proc/self/fd` 反向路径。
- AppSealing 50040 已确认由 `/proc/self/maps` 审计路径触发; `/sys/module`、`/proc/modules` 走 direct syscall, libc 文件 API 伪装不能覆盖。
- 只允许按反汇编或运行时 backtrace 确认的版本偏移跳过 50040/50048 上报调用; 不得泛化屏蔽 maps 解析、错误策略表或未知 syscall。
- v471 / SDK 36 的 `30010` 仅可处理已实测的 `libil2cpp.so` 调用点 `+0x70128`：
  必须逐字节覆盖两个完整代码段，按原算法重算校验值；仅将本进程登记的汉化/容器
  补丁替换回修改前字节，并核对当前字节仍等于登记的修改后字节。重算结果必须匹配
  本版本原始校验值（库在加载时解密，不能直接和磁盘加密字节比较）。其他调用点、模块、版本或
  未登记变化继续执行原上报；不得简单 NOP `30010` 或改写错误策略表。
  校验必须位于该失败分支的 JNI 释放之前：`+0x700ec` 调用 `+0xac878` 会
  DetachCurrentThread，释放后再从上报入口恢复执行会留下失效的 JNIEnv。
  仅在 caller=`+0x700f0`、栈记录与当前线程 JNIEnv 同时匹配且完整 CRC 校验成功时，
  从该调用点直接进入已验证的成功清理 `+0x70464`；其他释放调用执行原函数。
- 同版 `30009` 实测 caller=`+0x70ba8`，校验保护库自身的 179 个代码范围。
  兼容判断同样必须前移至 `+0x70b68` 的 JNI 释放之前；必须限定相同模块、描述表、
  原始 CRC，并对全部范围重算字节 CRC，仅恢复已登记的静态兼容补丁原字节。
  不得把 `30010` 的整段 IL2CPP 校验结果用于放行 `30009`，未知差异执行原路径。
- AppSealing 50048 的 `SIGILL` 兜底只允许在 `ILL_ILLOPC`、PC=`base+0x4e564`、
  LR=`base+0x4e550` 同时匹配时跳到 `base+0x4e998`;其余 `SIGILL` 必须转发原 handler。
  该包装器须独立通过原始 `sigaction` 维护,不得扩展已被 inline hook 的 `sigaction` 函数体,
  否则会改变前导指令布局并导致 Android 12 在 `__fix_instructions` 崩溃。

## 当前调试状态

- 后续更新以本地构建、相关回归和 GitHub Release 交付，由用户在应用内检测更新。
  连接、覆盖安装或调试用户手机须有新的明确授权；不把未验证的真机画面标记为通过。
- 2026-10-05 用户明确要求自行排查，不再反复要求其进入事件、战斗或停在指定页面定位。
  优先结合 BYBUS 代码、游戏元数据、零协资源与现有诊断；按影响范围验证，未覆盖的
  事件视觉效果或战斗滚动必须如实标明，不以接口启用代替完整场景验证。
- 设备地址最近变动频繁; 以用户最新给出的 adb 地址为准。
- 用户已明确说明: 不要给测试机设置代理。可观察 VPN/网络状态, 不要修改。
- Redmi/Android 12 + Limbus v1.107.1 已验证容器可进入标题页。
- 历史黑屏根因: arm64 `getcwd` hook 返回 raw syscall 长度 `2`, 导致 il2cpp 执行 `strlen(0x2)`。
- 最近新增 Firebase C++ Auth shim 后, 容器已从黑屏推进到 `PlayVideo -> PlayWarningAnim -> PlayDone` 并显示标题页。
- Google 登录选择器可打开; 选择账号后仍需继续跟踪 Credentials result 回调和 Unity 登录状态。
- vivo/Android 13 的 Issue #1 已确认 Google 登录会启动 `:p1/:p2` microG 辅助进程,随后在 `libv++_64.so -> hookAndroidVM -> NativeEngine.launchEngine` 同步崩溃；Apple 登录同时缺失浏览器 `genericidp` 回调入口。修复后仍须在真机确认账号选择结果和 Apple OAuth 状态确实返回 p0 游戏进程。
- 游戏文本文件在进入主页面登录后才下载, 不要在标题页阶段期待 `Localize/en/` 已完整存在。
- Redmi/Android 12 + Limbus v1.109.1 已验证同步到容器 versionCode 450 后可再次走完
  `PlayVideo -> PlayWarningAnim -> PlayDone`,并恢复 `SetLoginInfo : GOOGLE`。若 APK
  `sourceDir` 已是 450、Virtual PackageManager 仍报告 448,AppSealing 会在启动期报
  `20033`;必须先同步宿主安装,不能把它作为新的终止路径特判。
- Redmi/Android 12 已验证统一 service bind 包装会命中 microG
  `MeasurementService`:依次出现 `bindIsolatedService intercept`、
  `Wrap GMS service broker`、`Created explicit Limbus GMS broker proxy` 和
  `Rewrite Limbus GMS GetServiceRequest`,随后仍可进入 `PlayDone`。其中
  `libcovault-appsec.so +0x4e564` 的 `SIGILL` 被放行后游戏继续运行,不得把它单独作为
  GMS broker 修复失败的判据。Android 13/MIUI 设备仍需用相同四条日志复测。
- 运行时文本索引当前为日语单源 schema 9（稀疏身份/字段/完整原文上下文），构建指纹为 jp-pairing-9。策略版本同时参与索引内容摘要，避免生成目录错误复用。裸数字、时间和比例不能进入无上下文全局文本/术语规则；包内数字标题仅可按稳定资源身份与显示字段翻译，旧缓存也须在 native 层排除数字术语。schema 6 的版本 2026071001 曾生成
  93488 条完整映射、19659 条幂等短术语映射和 151950 条源记录;schema 7 在此基础上
  统一编译端/native 显示字段、覆盖 `story/relatedChapterText/openConditionNumber`,并拒绝
  Unicode 替换字符、非法控制字符等确定损坏的译文。Limbus 1.109.1 可解析 149 个 IL2CPP
  assembly。接管必须在访客配置阶段挂接 `dlsym`,只把 Unity 请求的 `il2cpp_init`
  返回值替换为生命周期包装函数;原初始化返回后才解析 domain 和安装文本 hook。不得
  inline patch `il2cpp_init`,不得并发读取半初始化 domain,也不得用固定启动秒数补偿。
- `TMP_Text.set_text`、`SetText(String)`、`SetText(String, Boolean)` 与安全的
  `UnityEngine.UI.Text.set_text` 已安装运行时替换 hook。内置 `ChineseFont.ttf`
  注册为全局 fallback;只有能证明由接管层新建或由已译字段格式化得到的中文字符串,才可在赋值期间
  把当前 TMP 组件切换到该中文主字体,以避免日文主字体与粗体 fallback 逐字混排;
  组件复用于非托管文本时必须恢复原主字体,不得按“含非 ASCII”泛化替换所有 TMP。
  镜像迷宫事件逐行显示须沿 ChoiceScrollViewBox 的确切显示入口传递全文来源证明，
  只授权同一 Unity instance ID 的组件显示已核对全文在换行边界上的段落/逐字前缀；
  内容替换或 Clear 时失效，不把这些片段登记为全局译文，保留原分段/滚动协程。
  具体边界与 478 兼容记录见 docs/runtime-text-and-478.md。
  已译格式模板拆成多个 TMP 控件时，仅可对同一直接父节点的有界、有序显示文本组验证完整模板；固定片段、参数和重复参数必须全部吻合，才切换该组固定中文片段的字体。不能把片段登记为全局译文。忽略禁用的池模板，但允许父弹窗隐藏时完成配置；数字控件保留原字体与样式。
  调用方格式化后丢失标记时，以完整包内译文或完整格式模板证明来源：允许识别明确的装饰标签、空白和逗号差异；模板参数仅接受有长度上限的数字/时间、包内已知名称，或已实测的个性化预览固定占位符 `■■■■■`，重复参数必须一致。固定占位符不得单独授予字体身份，不泛化到其他方块数量。拒绝任意正文子串、未知玩家名字、font/sprite/noparse 标签泛化与无边界通配；缓存和模板匹配开销须有上限。包内同文记录必须参与上下文冲突判断，不能被其他语境覆盖；同文汉字标签可登记字体身份，不能把其出现次数当作其他译法的否决票。
  内置主字体使用零协包的 Sarasa Gothic SC Bold（用户明确反馈 Regular 太细）,动态 SDF 图集必须使用 4096x4096,1024x1024
  已实测会在技能页加载约千条文本后丢失旧字形。切换中文字体时须保存并恢复组件原字体
  与材质,并把原 TMP 材质的黑色描边继承到中文材质;已汉化的普通多行文本须做有限行距
  补偿,带显式 `line-height` 的文本不得重复补偿。字体创建或 fallback 注册失败时必须
  保持原文,不得输出方框文本。
  材质重设优先通过 TMP_MaterialManager 转换原预设与中文图集，保留描边、颜色和状态样式；禁止把所有组件的预设直接换成同一默认材质。短文本只有在原控件不换行或实际高度只够一行时才按标签处理，不能仅凭字符数把弹窗正文裁成单行。
  容量/速率参数只在完整已知模板中接受严格十进制数和明确字节单位，拆分组件共用该规则；完整已知段落按换行拼接时须逐段验证并限制数量。材质由仍存活的组件共享持有，最后一次使用结束后平衡释放 TMP 引用，不能因累计缓存达到 128 项而丢失新样式。旧 UI.Text 尝试同一字体文件时必须核验句柄、动态字体及中文字形；失败保留系统后备，不能把构造器调用成功等同于字体已可用。
- 当前生产文本接管目标为 OurPlay 风格的日语全量模式:只编译设备上的日文
  `jp/JP_*` 原文到中文,但必须递归覆盖 `dataList`、嵌套技能/硬币表、故事对白等显式
  显示文本字段。TMP 文本赋值只作为最终 UI fallback;人物、人格、技能和剧情还要在
  `TextData_*` 等字符串取得/参数格式化路径接管。不得重新引入英日韩三语索引,也不得
  用裸 `id` 猜测跨资源表文本。
- 参数化描述不得维护页面级类白名单。运行时必须枚举全部 `TextData_*` 类,并仅 hook
  返回 `System.String`、带 0--2 个参数的实例 formatter，并按 hook 地址对齐检查与下一函数至少相隔 16/20 字节。无参数不代表没有格式化，例如 `GetTitle_ExceptionNewLine()` 会删除换行；不得笼统排除。通过 `il2cpp_method_get_flags` 排除静态方法，不能套用实例 ABI。
  Limbus 1.109.1 实测发现 59 个类并安全安装 54 个单参数和 4 个双参数入口。多个简单
  Getter 在 arm64 上仅相隔 4--8 字节,仍由 TMP/UI 精确匹配兜底,不得批量 inline hook。
- 通用 formatter 包装器仅接受已确认使用单个通用寄存器的参数类型，浮点、未知值类型和未知 ABI 跳过；不能只按参数个数匹配。
- 热更新文本之外，启动 Tip 可读取 APK 内置 BattleHint。只读解析当前已安装 APK 的 v22 单 TextAsset 格式，校验名称、类型、长度与对象边界，仅配对 BattleHint/BattleHint_NormalBattle/BattleHint_AbnorBattle；不扫取或改写其他资源，不用裸 id 跨文件替换。内置来源参与缓存指纹，但不代替下载资源的就绪校验。
- RPGSystem 的 key 是稳定身份；speaker/displayName/statText/goalDescription1..8 为已核对显示字段。其 RpgLocText_*、RpgLocDialogueText、RpgLocNarrationText、RpgLocQuestStep 是本地化模型，可按同样 ABI 和函数空间条件处理 getter；不得扩展到普通游戏对象的 displayName。说明句派生术语不得压过完整词条或正式名称。
- `TextData_SkillPerLevel.GetDesc` 调用原 formatter 前必须用 IL2CPP 反射递归处理
  `levelList -> coinlist -> coindescs`;列表元素通过 `get_Count/get_Item` 调用取得,
  不得硬编码托管 `List<T>` 或数组布局。当前 schema 7 同时存储完整原文索引与
  短术语 trie,允许从方括号及 TMP 富文本标签中提取 `攻撃前`、`長姉` 等嵌入术语。
  运行时最长匹配必须原样跳过 `<...>` 富文本标签,纯 ASCII 术语必须满足单词边界;
  编译和运行时还必须排除“译文仍包含原文”的非幂等术语,例如 `以上 -> 或以上`,否则
  同一文本经过 `SkillPerLevel -> Skill -> TMP` 多层接管时会不断前插“或”。
- formatter 可能在进入 TMP setter 前已经把日文替换成中文。TMP setter 对任何非 ASCII
  文本都必须先准备全局 fallback,不能只用“setter 中字符串指针是否变化”判断是否创建
  字体,否则首个已预翻译页面会缺失日文字体不包含的简体字。
- 但丁笔记 formatter 会在段落换行处插入 `<line-height=170%>` / `<line-height=100%>`。
  完整索引 miss 时只允许移除这两类布局标签后重试精确匹配,命中后须把同样的段落间距
  结构应用到中文换行;不得泛化剥离颜色、sprite、link 或 style 标签。
- 翻译层全局 `dlsym` 生命周期 hook 必须在 VirtualApp libc IO hook 完成后安装,否则
  IO 层会再次搬运已 patch 的前导指令并在 `__fix_instructions` 崩溃;但仍须早于 Unity
  首次请求 `il2cpp_init`。
- 轻量模式只支持游戏日语模式。必须在 IL2CPP 初始化边界后将
  `GlobalGameManager.Lang` 的运行时返回值固定为 `JP=2`。两个语言 getter 必须按
  `docs/language-getter-and-receiver-compat.md` 核对完整元数据、枚举值和相邻函数边界，
  使用 8 字节直接返回（保留 BTI 时为 12 字节），不得用普通 16/20 字节远跳覆盖短函数。
  安装结果不等于实际游戏语言已验证；没有真机观察时须明确标注。不得改写 PlayerPrefs
  中同一对象携带的 key、iv、账号或其他设置字段。
- 首页的普通用户主路径为“启动汉化版游戏”:默认联网检查 GitHub Releases、复用已校验的
  汉化包缓存、必要时同步游戏,再激活索引并启动。GitHub Releases 是唯一汉化获取途径；
  生产界面固定都市零协会最新正式 Release 地址，不提供修改源、中转服务地址或 Token 配置。
- 汉化包已下载、日文资源已就绪、运行时索引已激活是三个独立状态。编译时若容器
  可读的 `jp/JP_*.json` 低于当前汉化 manifest 候选 JSON 的 90%,不得激活残缺索引、
  不得覆盖旧的可用索引。首次安装可按 `docs/first-run-android-compatibility.md` 独立准备
  启动页索引，仅使用当前 APK 或容器中可核对的五个启动表，不能标记完整资源就绪。
  完整索引与启动索引分开持久化；运行时优先选择已校验的完整索引。用户完成资源下载后，
  下一次启动从缓存重建完整索引。源文本 miss 保持日文，不应导致整体失效。
- 用户诊断包只能采集汉化器 UID 可见的有限 logcat、应用自身按进程分离且有大小上限的
  滚动日志、应用/设备版本、兼容检查、容器与索引摘要、历史进程退出原因。不得打包游戏
  资源、PlayerPrefs、账号信息或汉化器配置;私有滚动日志写入时及导出前都须对 Token、
  Authorization、URL 凭据、邮箱和疑似 JWT 做脱敏,并限制单项、文件数量与总包大小。
- 生产 APK 必须内置已验证的 microG Services 与 Companion/FakeStore APK 及 SHA-256
  清单。新安装在首次导入/启动时自动校验、暂存并装入 VirtualApp;不得再依赖
  `microg_apk_paths` 调试参数或首次启动联网下载。旧设备已有的有效外部配置可继续
  使用,调试入口仍只允许 debug 构建覆盖。超过 Git 托管单文件限制的内置 APK 必须
  拆为清单声明的资源分片,运行时按顺序合并后再对完整 APK 做 SHA-256 和签名校验。

## 常用命令

验证按本次改动缩小范围：优先运行受影响测试，已有通过记录不无故重跑。只有涉及启动链路或发现新问题时才启动游戏验证；一次有明确结论的启动即可，不凑启动次数。2026-09-29 用户明确要求提高效率，取消本轮同版本游戏重装测试；未做的设备安装/取消回调检查须如实列为未验证。

Linux 本机必须使用 Android Studio 自带的 JBR 和其 SDK 目录中的 NDK;不要使用系统
OpenJDK。容器模块的 NDK 版本以 `third_party/virtualapp-upstream/lib/build.gradle` 中的
`ndkVersion` 为准,当前为 `30.0.14904198`。

```bash
cd /path/to/limbusZhCN
export JAVA_HOME=/path/to/android-studio/jbr
export ANDROID_HOME=/path/to/Android/Sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.14904198"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
export JAVA_TOOL_OPTIONS=''
./gradlew --console=plain :app:testDebugUnitTest :app:assembleDebug
```

```powershell
cd D:\code\limbusZhCN
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:JAVA_TOOL_OPTIONS=''
.\gradlew.bat --console=plain :app:testDebugUnitTest :app:assembleDebug
```

```powershell
adb -s <device> install -r .\app\build\outputs\apk\debug\app-debug.apk
adb -s <device> shell am start -n com.example.limbuszhcn/.MainActivity --ez launch_container_game true
adb -s <device> shell am start -n com.example.limbuszhcn/.MainActivity --ez sync_installed_game true --ez launch_container_game true
```

关键观察:

- 优先从 `dumpsys activity processes` 中核对宿主 UID 下的 `com.example.limbuszhcn:p0` ProcessRecord；游戏会改写 Linux 进程名，`pidof com.example.limbuszhcn:p0` 返回空不能单独证明闪退。重新启动时须等待旧进程退出后再记录新进程，不把准备期间仍存活的旧 PID 当成本次启动。
- `dumpsys activity activities` 应出现 `ShadowActivity$P0`, 不应回退到真实游戏进程。
- `logcat` 常用过滤: `LimbusZhCN` / `LimbusContainer` / `LimbusVA` / `Unity` / `AndroidRuntime` / `NativeEngine` / `firebase` / `LimbusSIG`。
- APK 必须包含 `lib/arm64-v8a/libv++_64.so`。
