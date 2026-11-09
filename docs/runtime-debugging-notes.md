# Limbus 运行诊断记录

本文记录已解决黑屏问题的诊断过程。架构约定仍以 `AGENTS.md` 为准,VirtualApp 兼容细节见 `docs/container-runtime-notes.md`。

## 宿主异常与安装后打开

宿主在 `attachBaseContext` 安装的有界脱敏异常记录器必须保留到初始化结束，记录后继续
交给 Android 原异常处理器。VirtualCore 不得用只写原始文件、不转发异常的旧企业记录器
替换它，否则主线程退出后进程可能仍留存，系统崩溃记录和常规诊断也会缺失。
主界面只记录创建、恢复、获得焦点、重入和销毁的有限状态，不保存启动 extras 或 URI。
导出时附带自身 launcher 是否可解析、组件是否启用和异常处理器是否仍有效；只补收旧
记录器中宿主和服务进程最近四份、每份最多 32 KiB 的脱敏堆栈，不扫描访客日志或资源。
这些改动修复诊断与异常处理链，不能据此宣称 70036 或 OEM 安装器的具体故障已解决。

## 2026-09-23：v471 采光与加载界面回归

### 修复后的第二次检查（进行中）

对设备导出与零协 2026092102 做全量实际编译器检查，初始为 2144 个接受文件、33 个跳过文件、167166 个配对显示字段。其中 160303 条命中其精确/上下文译文、5921 条日中相同、837 条精确歧义、105 条被其他全局译法覆盖；不能把这些静态统计直接当成屏幕漏译率。

发现并准备修复三类新增根因：

- APK 的 Unity TextAsset 内置 BattleHint 与热更新 JSON 并非同一版本。真机未译提示多出「同じ威力なら」，已在当前 Google Play split 的 `JP_BattleHint` TextAsset 原文中找到。增加只读、严格格式校验的 v22 单 TextAsset 解析器，仅采集三个 BattleHint 资源，与同名零协文件结构化配对；原文和元数据参与构建指纹，不修改 APK、不添加手写剧情译文、不扩大资源就绪分母。未知格式保持原路径。
- RPGSystem 使用 `key` 作为身份，显示字段为 `speaker/displayName/statText/goalDescription1..6`。补入已核对的字段白名单；`key` 必须按稳定身份配对，不允许在记录重排后按位置串译。native 同步字段策略，但没有取得参数格式化入口证据的 RPG 页面不能宣称已真机验证。
- 说明句派生术语继承了 Bufs/BattleKeywords 的词典优先级，能把完整名字「羅生蝶」覆盖为「与罗生蝶」。派生条件降低优先级，不覆盖独立完整词条；完整字段和正式词典名称优先。

附带审查：实例 formatter 还必须检查参数 ABI，浮点/值类型不能套用通用寄存器包装器。现有按参数数量选择的逻辑需要补这一边界。单独记录购买初始化失败弹窗，未触发购买、未将其冒充翻译问题。

v471 metadata 进一步确认新剧情的模型类为 `RPGSystem.RpgLocText_*`、`RpgLocDialogueText`、`RpgLocNarrationText` 和 `RpgLocQuestStep`，使用 `GetText/GetDisplayName/GetStatText/GetGoalDescription` 格式化显示字段。将这些本地化模型纳入相同的 ABI/函数空间审查，不纳入同名 displayName 的音频、玩家资料、地图触发器或静态路径工具。QuestStep 还声明目标 7/8，一并纳入字段策略；仍不将嵌套 index 当作跨文件全局身份。

剩余 19 个低覆盖/未知文件中，18 个源为 `{}` 占位，另一个楼层地点表为 5/6 配对，缺失项为零协未提供的占位地点。文件覆盖率用于整体资源就绪判定，但不应丢弃已经通过严格身份配对的那五条译文：收集与就绪计数分开；整套资源的 90% 和记录下降保护保持生效。畸形 JSON、身份不符或有歧义的行仍不进入索引。

函数空间审查原先只比较同类中的相邻方法，并把该类最后一个方法当作空间足够；它仍可能紧邻另一个类的短方法。利用初始化时已经进行的类枚举，一次收集所有已知托管函数地址并排序，按全局下一个函数计算空间；没有后继地址则拒绝。此临时表安装完成即释放，不添加常驻索引。

真机确认新增本地化模型安装后为 12/59/5 个 0/1/2 参数入口；多个 RPG 正文 getter 因函数过短被正确跳过。它们共享 `RPGSystem.RpgLocFormat.Apply`，metadata 显示是静态双参数函数。仅在运行时确认完整 `System.String,System.Object[] -> System.String` 签名、static 标志及安全空间后使用专门静态 ABI 包装，先翻译格式模板再调用原格式化逻辑，保持参数数组不变。不得将它套进实例包装器。

用户在 vivo 设备报告采光经验/纺锤、Tip 正文、Loading 字样漏译及字体深浅不一。实机采光截图确认关卡名称为中文，左栏仍显示「経験値」，部分说明混合日中词汇。当前零协包包含 RewardDungeonUI 与 BattleHint 译文；公开 JP 对照中的对应记录完整，仍需核对实际渲染入口。

设备 Localize 导出与零协包核对确认：同文异译被全局索引丢弃是已复现的原因。例如「経験値」在 ProjectGS 译作「经验值」、RewardDungeonUI 译作「经验」；「推奨レベル」在采光译作「难度」，其他界面译作「推荐等级」。BattleHint 中也存在相同原文的不同译法。TMP ParseInputText 候选拦截已通过启动验证，但没有解决这些索引缺项，不能以 hook 安装成功宣称修复。

schema 9 仅为同文异译记录增加稀疏上下文表，以对象实际 id、显示字段名、完整原文联合匹配；同一个联合键仍有冲突则继续跳过。id 从 JSON 结构取得，在运行时通过 IL2CPP 反射读取，不解析诊断路径、不使用裸 id 猜测。无上下文的 TMP 路径保留原有全句/术语规则。修正 JP_ 文件名前缀导致 Bufs/BattleKeywords 词典优先级未生效的问题。索引仍只有日文源，升级原子切换并保留上一份已校验的 schema 8 文件，配对缓存一并失效。游戏 APK、原始 JSON、账号和资源不改动。设备激活并加载 100648 条完整映射、22742 条术语、5832 条上下文映射，采光实测恢复中文。

加载界面元数据确认存在 LoadingSceneManager._loadingText 与 _hintTxt，零协 JSON 包中没有独立英文 Loading 常量的译文。对这类固定 UI 状态增加独立、明确的宿主补充：仅 TMP 全字符串 Loading/Now Loading（对应全大写、全小写形式，含 1–3 个点或省略号）与 CONNECTING/Connecting/connecting 使用「加载中」「连接中」；不修改图片、不做英语子串替换，也不将这些词归为零协正文译文。游戏内容和 Tip 正文继续来自零协包。

采光已实测显示「经验、纺锤、有利属性、推荐等级」，但观察到切页后玩家编号出现旧排版状态泄漏。组件状态表以原生地址为键，Unity 销毁并复用地址后不能把旧组件字号应用到新组件。恢复操作须以当前仍使用本层中文字体为条件；组件从其他字体重新切入中文时重建原状态。渲染准备边界只负责已识别译文与固定状态，不对普通未翻译数字/标识符执行恢复操作。修正后新进程多次切换主页、驾驶席与放映室，玩家编号及等级未再出现旧字号泄漏。

用户明确反馈 Regular 过细，因此改用零协仓库原始 ChineseFont.ttf（Sarasa Gothic SC Bold，SHA-256 a56a06f1af27726bc5def015b61deecbbdf5ae6d954a91b1131d8a17290def35），继续使用完整字体与压缩封装。原生实际加载路径及登录、加载、剧情页面已验证。未获取 Kuyo/UU 的字体文件，不宣称与其完全相同。

JP_E715B 存在重复 id=11（两个不同 model），零协第二条为 id=12，导致此前 17/19 配对、整文件低于 90% 而跳过。重复 ID 现在允许通过双方唯一且相同的 model/voicefile 元数据选择同 ID 记录，使其中一条恢复、达到 18/19；不跨 ID 猜测另一条，不按数组位置修复原始数据错误。无唯一辅助信息的重复条目仍跳过。

用户指定「蜘蛛巢 环指 父辈 鸿璐」人格剧情 P10614；设备日文与零协中文均为 118 条。v471 IL2CPP 实际签名确认剧情对象由 StorySystem.Dialog..ctor(Int32,Int32,SimpleJSON.JSONNode,SimpleJSON.JSONNode,Single) 构建。只在这个完整签名且有安全 prologue 空间时拦截，原构造完成后通过反射读取 OriginalId，仅处理 Place/Teller/Title/Content 对应显示字段，在逐字播放之前进入精确/上下文翻译。StoryDialogScript 的构造函数实际接收 Dialog、属于另一方向，不能把它误当作 JSON 加载入口。其他构造函数、ID/Model/Voice/WaitTime/控制字段不修改。

实机随后确认 Connecting、NOW LOADING 和两条不同 Tip 正文显示中文；P10614 开头两段对白及旁白完整显示中文。放映室列表仍显示去掉换行的日文人格标题：TextData_Personality.GetTitle_ExceptionNewLine() 是无参数格式化器，旧的「只接管 1–2 参数」条件误将它排除。扩展为发现 0–2 参数的实例字符串 getter，继续逐一检查相邻函数间距，跳过短函数和静态方法；在格式化前翻译明确显示字段，避免从已拼接的标题猜测译名。已有零参数包装器保持原 ABI。当前编译器与实际设备日文、同版本零协包的离线全量配对检查中，P10614 的 222 个显示字段均命中精确/上下文索引；这不等同于全剧情逐句播放验证。

零参数入口实测安装 9 个（另有 54 个单参数、4 个双参数），人格列表及故事标题已恢复中文。标题被删除换行或拼接姓名后，返回值不再等于已登记的中文字段，导致 TMP 又混用日文主字体。格式化调用现在最多收集 64 个已确认译文的显示字段，只有返回值包含同一调用对象的完整中文字段（允许仅换行展平）才传递字体标记；不改返回正文，不依据「包含非 ASCII」或任意全局子串来切换主字体。纯数字/ASCII 字段不作为证明，保存有界 UTF-8 副本，避免跨托管调用保留临时裸字符串指针。

正式包复测中，放映室标题由调用方继续拼接，仍丢失字体标记；实际显示与零协完整标题仅逗号宽度和空白不同。增加只读的短译文视图集合（引用已载入索引的字符串，不复制正文）：当前完整标签必须与包内完整译文相等，只允许忽略 ASCII 空白及中英文逗号区别，才作为字体来源证明。限定 256 UTF-8 字节以内的非 ASCII 标签，不按子串切换，不改文字或标点，不对纯数字编号生效。此集合与索引同生命周期，必须在索引释放前清空。更新 AGENTS 字体规则以明确这项严格的整串等价例外。

## Limbus 1.109.1 Japanese-only full text takeover

The multilingual schema-2 experiment produced an 88 MiB runtime index on the test
device: 245,587 source entries, 332,862 ID entries and 376,296 context rows. Native
startup loaded both hash tables and installed sixteen `TextData_*::GetText` hooks.
That coverage was too expensive for the normal launch path.

Production mode compiles only container-visible `jp/JP_*` resources into a compact
source-string index. Compilation must recursively include nested skill/coin tables
and story display fields rather than accepting only top-level `dataList` strings.
TMP setter hooks are only the final UI fallback; the string acquisition and
parameter-formatting paths must be covered for full character, skill and story
translation. `GlobalGameManager.Lang` is forced to the confirmed enum value
`JP=2` after the IL2CPP initialization boundary, with
`LocalSave.LocalGameOptionData.GetLanguage()` retained as a compatibility hook.
Schema 7 keeps the compiler and native display-field policies aligned and adds the
verified `story`, `relatedChapterText`, and `openConditionNumber` fields found in
the current public package. Entries containing replacement characters or illegal
control bytes are skipped instead of displaying damaged text. Validation requires
an actual getter-hit log; hook installation alone does not
prove the game selected Japanese. A user stuck before the settings screen does
not need to edit the saved English value (`EN=1`) first. PlayerPrefs itself is
left unchanged.

The launcher must not treat a downloaded translation archive as an active
translation. On a new install, official Japanese resources appear only after the
game reaches its post-login download flow. Activating an index from the few files
present before that point creates a misleading partial installation. The launcher
therefore requires at least 90 percent of candidate manifest JSON files to have a
readable `jp/JP_*` counterpart. Below that threshold it preserves the previous
index, caches the package, launches a resource-preparation session, and rebuilds
from cache on the next run.

On the Redmi/Android 12 device, recursive compilation of package `2026071001`
increased the Japanese-only index from 81,642 unique entries / 123,695 source
records to 93,369 unique entries / 151,950 source records, with 1,080 ambiguous
sources excluded. This confirms that top-level-only traversal had omitted a
substantial portion of skill levels and coin descriptions.

Schema 6 retained 19,659 idempotent embedded-term mappings after filtering 26
unsafe schema-5 candidates, alongside 93,488 exact
mappings. It resolves dominant UI terminology such as `囚人 -> 罪人`, extracts
bracketed keywords such as `攻撃前 -> 攻击前`, and strips TMP tags to recover
terms such as `長姉 -> 长姊`. Runtime validation on the identity skill page showed
the nested `coinlist/coindescs` traversal running before formatting and replaced
the formerly mixed Japanese effect sentences with Chinese.

An identity skill later displayed a long run of `或` although its Chinese resource
contained the correct single sentence. TMP-input logging proved the run already
existed before layout. The schema-5 term table contained `以上 -> 或以上`; because
the result still ends in `以上`, each `SkillPerLevel -> Skill -> TMP` translation
layer matched it again. Schema 6 excludes every term whose translation contains
its source, with the same defensive filter when the native trie is built. Rich
text tags are still copied verbatim and ASCII-only terms require word boundaries.

Dante-note details initially remained Japanese except for a few term replacements.
TMP-input logging showed that `TextData_DanteNote` had inserted
`<line-height=170%>` before each newline and `<line-height=100%>` after the next
dash. Removing only those tags reproduced the exact indexed Japanese source.
The exact fallback now normalizes this layout-only markup and reapplies the same
paragraph spacing to translated Chinese lines.

Historical font experiment (superseded by the 2026-09-23 Bold restoration above):
the original bundled LocalizeLimbusCompany `ChineseFont.ttf` was Sarasa Gothic SC
Bold. It was replaced by the official Sarasa Gothic SC Regular face because
the bold contours made skill descriptions visibly heavier than the game UI. Its
initial 1024x1024 dynamic atlas filled after roughly one thousand
translations and produced black blocks or missing letters, so production uses a
4096x4096 atlas. Keeping the Japanese primary face for translated paragraphs
then exposed a separate visual defect: common Han glyphs came from that face
while Simplified-Chinese-only glyphs came from the bold fallback, producing
alternating weights within a word. The runtime now tags managed strings it
creates as translation results and temporarily assigns the same Chinese asset as
primary only for those tagged strings. It remembers and restores the component's
original font and material when a recycled TMP object receives untagged text. The
source material's outline width/color are copied to the per-component Chinese
material, preserving the black edge used by coin-skill text. Translated multiline
text without explicit `line-height` markup receives a bounded spacing adjustment
to prevent adjacent rows from overlapping. The global
fallback remains registered for early layout and this policy is not inferred
from non-ASCII content alone.

Page-by-page formatter whitelisting was removed after identity passives and Dante
notes exposed the same omission pattern. The runtime now discovers all 59
`TextData_*` classes and selects string-returning getters with one or two
formatting arguments. Limbus 1.109.1 installed 54 one-argument and four
two-argument hooks, including passive/summary, abnormality events, EGO gifts,
quests, UI, Dante notes and mirror/railway text. Every selected body must still
have at least 16 bytes before the next method; adjacent trivial getters remain on
the TMP/UI exact-match fallback.

Acquisition hooks can return Chinese before TMP setters run. Initially the setter
therefore saw no local pointer change and created the fallback font too late: the
first loading hint showed squares for Simplified-Chinese-only glyphs. Font setup
now happens before any non-ASCII TMP string is laid out. A cold-start loading hint
then rendered the whole Chinese sentence without missing glyphs.

The global `dlsym` lifecycle hook must be installed after VirtualApp finishes its
libc IO-hook pass. Installing it during translation configuration caused the later
ARM64 relocator to process an already-patched prologue and crash in
`__fix_instructions`; the corrected order still intercepts Unity's first
`dlsym("il2cpp_init")` request before managed initialization.

The following section records the retired multilingual experiment for comparison.

## Retired multilingual ID translation index

Runtime inventory confirmed that `LocalizeTextData` owns a string `id`, while
character, personality, skill, passive, enemy, EGO and buff tables expose derived
`TextData_*::GetText(System.Object[]) -> System.String` methods. The runtime resolves
the base field with `il2cpp_class_get_field_from_name` and reads it through
`il2cpp_field_get_value`; it does not embed a managed object-layout offset.

Index schema 2 stores exact-source mappings and unambiguous `id + source` mappings.
The compiler reads every installed `en`, `jp`, and `kr` source row and maps them to
the same Chinese target. Translation package `2026071001` produced 245,587 source
entries, 332,862 ID entries, and 376,296 context rows on the test device. Sixteen
targeted `TextData_*::GetText(Object[])` hooks installed before `PlayDone`, alongside
the TMP fallback hooks. Japanese mode therefore retains the game's CJK-oriented
font/material path without the localizer forcing a language.

## Limbus 1.109.1 runtime translation index

On the Android 12 Redmi test device, translation package `2026071001` was
compiled against the container-visible official English tables into:

- 61,318 unambiguous source-to-translation entries;
- 76,359 context rows;
- 617 ambiguous source strings excluded from the context-free fast index.

The immutable index was loaded in the Limbus guest before application startup.
Because AppSealing loads `libil2cpp.so` in an isolated linker namespace,
`RTLD_NOLOAD` from the VirtualApp library cannot obtain its handle. The resolver
instead uses the offset-zero `/proc/self/maps` mapping plus validated ELF64
`dynsym` values. After `PlayDone`, the resolver observed 149 assemblies and
resolved both target methods:

```text
TMPro.TMP_Text.set_text
UnityEngine.UI.Text.set_text
```

No method hook is installed in this stage. The resolver waits until after the
measured startup window before calling IL2CPP domain APIs, because AppSealing
maps the library before IL2CPP initializes its domain.

An apparent startup regression with AppSealing code `20033` was caused by a
mixed package view: the visible APK paths pointed to host versionCode 450 while
the Virtual PackageManager record still reported 448. Running the installed
game sync updated the package record and native libraries while preserving
container data. The next launch completed `PlayVideo -> PlayWarningAnim ->
PlayDone` and restored `SetLoginInfo : GOOGLE`. Treat this as an APK/package
consistency failure, not as a new AppSealing termination offset.

## Issue #1 登录崩溃与回调诊断

2026-08-11 的 vivo V2314A / Android 13 诊断包记录了三次可复现原生崩溃：`:p1` 一次、
`:p2` 两次，栈顶均为 `libv++_64.so` 的 `hookAndroidVM()`，上层依次是
`NativeEngine.launchEngine()` 与 `VClient.bindApplication()`。这些槽位在登录阶段承载
microG/FakeStore 辅助进程；主游戏 p0 已跳过同一旧式 VM Hook，所以账号框消失的直接原因
不是点击事件，而是结果提供方进程在应用绑定阶段死亡。

Apple ID 成功验证后无响应是另一条可独立确认的链路：游戏 manifest 的 Firebase Auth
返回 Activity 只声明在被虚拟安装的游戏 APK 中，系统浏览器看不到容器内 intent filter。
宿主必须以精确 URI 跳板把 `genericidp`/`recaptcha` 回调重新送进 p0，不能让回调误开宿主
主页或设备上真实安装的原游戏。

该诊断包还暴露出两个诊断层问题。首先，同步 `SIGSEGV` 下游处理器若返回但不修改 PC/SP，
包装器继续返回会重新执行同一故障指令并刷满 logcat；此时应恢复默认处理而不是循环。
其次，`ApplicationExitInfo.traceInputStream` 的 native tombstone 是 protobuf 二进制，不能先
按 UTF-8 解码再写回文本。导出器应保持二进制字段长度和地址不变，并在等长覆盖常见凭据后
以 `.pb` 保存；超过单项上限的 tombstone 应跳过，不能截断成无效 protobuf。

Issue #1 随后的 v1.2 回归包又确认了一个更窄的例外：vivo V2314A / Android 13 上，
AppSealing 后台 `Thread-5` 会稳定在 `libcovault-appsec.so +0x248d4` 以空 `x0` 读取
`+0x30`，其下游 handler 在 `exit_group(139)` 被容器拦截后原样返回。该现场不能恢复
默认 SIGSEGV（会杀死整个游戏），也不能返回重试（会死循环）；运行时必须同时核对基址、
偏移、故障地址、寄存器、指令和非主线程身份后，仅以原始 `exit` 隔离当前后台线程。

## Android 16 / ColorOS 16 兼容结论

2026-08-08 在 PHY110、Android 16（API 36）和 Limbus v1.109.1 上完成真机验证。启动已
走完 `PlayVideo -> PlayWarningAnim -> PlayDone` 并渲染到 `TRIGGER WARNING` 页面；主游戏
进程持续存活，未再出现此前的 `libc rmdir+0 SIGILL` 或 Java 深色模式空指针。

- `readlinkat` hook 必须保持 bionic 的 `ssize_t` 返回 ABI。错误的窄返回值会污染调用方寄存器，
  并在 libc realpath 路径中形成看似无关的 native 崩溃。
- `SIGSEGV` 包装器调用已登记的下游 handler 后必须立即返回。下游 handler 已通过修改上下文
  消费信号时，再恢复默认处理并重发信号会把 ART 的隐式空指针检查误判为进程崩溃。
- arm64 必须同时阻断 libc `syscall(__NR_exit)`、`syscall(__NR_exit_group)` 和扫描到的直接
  `exit`/`exit_group` 指令；否则 AppSealing 能绕过普通导出符号 hook 终止主线程。
- Android 16 arm64 bionic 的 `renameat` 导出只有 12 字节，紧邻的下一函数是 `rmdir`。旧 inline
  hook 需要覆盖 16 字节，会把 `rmdir` 首条 `BTI c` 改成无效 trampoline 指令。因此 API 36
  跳过 `renameat` inline hook，保留其余 IO 重定向；不要把该现象当作 AppSealing 新偏移。
- ColorOS 16 的 `OplusActivityManager.isAppInDarkModeDataEntity()` 没有检查厂商服务是否为空。
  VirtualApp 的 ActivityManager 代理无法创建该扩展服务时，应在 Limbus Activity `onCreate`
  前向厂商 Singleton 安装其 AIDL `Default` 无操作实现。触发条件使用 API、目标包名和厂商类
  是否存在，不依赖虚拟进程内可能被代理的 ROM 系统属性。

## 当前结论

- Redmi/Android 12 + Limbus v1.107.1 可在容器内启动到 `PlayVideo -> PlayWarningAnim -> PlayDone` 并进入完整标题页。
- `FakeAssetPackService` 生效,GMS broker 包名改写生效,前台保持 `ShadowActivity$P0`,进程 UID 仍为宿主。
- 跳过 libc `read` inline hook 后,已规避 `perfetto_hprof_` 线程在 bionic `read+8` 触发的 `SIGILL`。
- 黑屏根因是 arm64 `getcwd` hook 返回 raw syscall 字节数 `2`,导致 il2cpp 的 C++ 字符串构造路径执行 `strlen(0x2)`。恢复 `char *getcwd(...)` 的返回值语义后,Addressables 和 FMOD 正常初始化。

## 历史排查方向

1. 对比容器外 Google Play 原游戏运行日志。
   - 先确认同一设备、同一 VPN 下原游戏是否能从开屏进入登录/主页面。
   - 对比原游戏在 `PlayDone` 后的 Firebase Auth、GMS sign-in、Play Games、Remote Config、资源热更和网络请求日志。

2. 观察容器内 Google/商店链路。
   - 重点看 `com.android.vending`、`com.google.android.gms`、`GmsApiService`、`MeasurementBrokerService`、Play Games sign-in。
   - 若容器内只有 `SetLoginInfo : NONE` 后沉默,优先怀疑登录或服务发现链路被隔离/伪装破坏。

3. 检查渲染与视频层差异。
   - 当前 SurfaceFlinger 能看到 `SurfaceView[ShadowActivity$P0]` 和 Activity 主窗口,但截图仍全黑。
   - 需要对比容器外开屏视频结束后是否还会创建额外 Surface、切换 EGL/Vulkan 上下文或进入登录 UI 场景。

4. 检查 AppSealing 后续保护分支。
   - 已知 `libcovault-appsec.so +0x23088` 和 `+0x20c58` 会调用 `exit(0)`,并有后续 direct `exit_group`。
   - 当前策略是补掉后续 `exit_group` 并终止当前 native 调用线程;如果原游戏在同一时间点没有这些分支,需要继续定位触发条件。

## 建议采集

普通用户应先在汉化器“高级设置”中选择“导出调试日志”。ZIP 包包含本应用 UID 的近期
日志、容器/汉化索引状态和 Android 历史进程退出原因,适合排查启动失败、闪退和汉化未
生效。导出器会脱敏常见凭据,但不采集游戏资源、存档、PlayerPrefs 或汉化渠道配置。
若应用自身无权读取某个 logcat buffer,包内会保留采集失败说明,而不是申请全设备
`READ_LOGS` 权限。

新安装设备不应再出现 `microG APK 未配置`。生产包内置 microG Services 与
Companion/FakeStore,首次导入游戏时从 assets 校验并暂存。如果日志出现
`Bundled microG digest mismatch` 或无法识别内置包名,应视为 APK 构建/分发损坏;
不要让用户通过 adb 补 `microg_apk_paths`。旧设备日志中的 `microG already installed`
仍表示复用原有有效容器组件。

Android 13 新机即使已完成内置 microG 迁移,broker 请求仍可能携带真实游戏包名。若随后
出现 `Unknown calling package name 'com.ProjectMoon.LimbusCompany'`,先确认
`Wrap GMS service broker`、`Created explicit Limbus GMS broker proxy` 和
`Rewrite Limbus GMS GetServiceRequest` 三条日志。包装不得因为 microG 已安装在 VirtualApp
内而被短路;其后的 AppSealing `SIGILL`/`SIGSEGV` 可能只是未捕获 Java 异常触发的清理路径。
Limbus 的 service bind 必须在分支解析前统一包装 `IServiceConnection`;broker 回调除目标
组件外还须以 `IGmsServiceBroker` Binder descriptor 兜底识别,避免 MIUI 或厂商改写后的
Intent/Component 绕过。Android 14 起还必须覆盖 `bindServiceInstance` 并兼容 `long flags`,
不得按系统版本或 ROM 名称做特判。

2026-07-17 的 Redmi/Android 13/MIUI 诊断包进一步确认:游戏先在已知的
`libcovault-appsec.so+0x4e564` 触发 `SIGILL`,约 0.7 秒前日志虽已记录 50048 branch
patch 成功,但 AppSealing 初始化完成后恢复了原指令。Unity 的信号处理随后在 PlayCore
注册 asset-pack listener 阶段触发同步 `SIGSEGV`,PC 为 MIUI `libc.so+0x86a90`,`x0`
和 fault address 均为 null。旧实现的进程级一次性标记使 `makeApplication` 后的收尾扫描
无法重补。当前改为逐条比较目标机器码,仅当已确认的 70034/50040/50048 版本偏移被恢复
时精准重写。Android 12 回归进一步观察到保护库会在收尾扫描数秒后再次恢复
`+0x4e564`。尝试包装全局 `SIGILL` handler 会干扰 ART/Unity 自身的信号链,该方案已
撤销。当前在完整 syscall 扫描结束后启动轻量
指令维护器,只读取五个确认偏移;发现 50048 cleanup branch 被恢复后立即精准重补并停止。
真机维护日志表明 `+0x4e564` 会被改写为每次不同的瞬态机器码,而非首次扫描值。因此仅
该多次 backtrace 确认的 50048 站点允许把瞬态值改为固定 cleanup branch;其余四个偏移
仍要求与首次机器码完全一致。保护库会在首次重补后约百毫秒内再次恢复该站点,因此维护器
覆盖完整 30 秒启动窗口,而不是首次重补后立即停止。
Android 13 最新日志进一步证明轮询刚重补 3ms 后同一线程仍会在 `+0x4e564` 执行
`ILL_ILLOPC`,继而触发原 `libc+0x86a90` 空指针崩溃。单纯缩短轮询间隔无法消除保护库
改写与执行之间的竞争窗口。因此保留轮询重补作为主路径,同时独立维护 `SIGILL` 包装器:
仅当 `si_code=ILL_ILLOPC`、PC=`base+0x4e564` 且 LR=`base+0x4e550` 三项同时满足时,
才把 PC 改到已确认的 cleanup `base+0x4e998`;其余 `SIGILL` 完整转发给下游 handler 或
恢复默认终止。包装器通过原始 `sigaction` 安装并在 2ms 维护周期内重新包裹保护库或
Unity 后续设置的 handler,不扩大现有 libc `sigaction` inline hook。直接扩展该 inline
hook 的实验会改变其前导指令布局,并在 Android 12 的
`A64HookFunctionV -> __fix_instructions` 阶段崩溃,因此该接入方式已撤回。

Redmi/Android 12 回归中,包装器经历 Unity/AppSealing 多次替换 handler 后仍能持续维护,
并走完 `PlayVideo -> PlayWarningAnim -> PlayDone -> SetLoginInfo : GOOGLE`;未出现
`__fix_instructions`、`SIGILL` 或 `SIGSEGV` 回归。Android 12 的该启动分支没有实际命中
上述精确 PC/LR,因此 Android 13 的重定向效果仍须由发生过该竞争的设备日志最终确认。

同步 `SIGSEGV` 诊断必须保留原 handler/默认终止语义。信号处理器只使用预先缓存的已加载
ELF 模块范围和固定缓冲区,输出 `si_code`、tid、PC/LR/SP、x0--x7、fault address 以及
PC/LR 的模块名和相对偏移;不得在 signal handler 内读取 `/proc/self/maps`、分配内存或调用
`dladdr`。模块缓存可在 IO hook 初始化和脱离 linker 回调的 native syscall 扫描阶段刷新,
不得在 `onSoLoaded` 内调用 `dl_iterate_phdr`;缓存只保存库文件 basename。

```powershell
adb -s <device> logcat -c
adb -s <device> shell monkey -p com.ProjectMoon.LimbusCompany 1
Start-Sleep -Seconds 90
adb -s <device> logcat -d -v time > logs\limbus_outside_playstore.log
```

容器内对照:

```powershell
adb -s <device> logcat -c
adb -s <device> shell am start -n com.example.limbuszhcn/.MainActivity --ez launch_container_game true
Start-Sleep -Seconds 90
adb -s <device> logcat -d -v time > logs\limbus_container.log
```
