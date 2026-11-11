#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusUiLabels.h"
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusTextProvenance.h"
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusFontProof.h"
#include "../third_party/virtualapp-upstream/lib/src/main/jni/Foundation/LimbusFlowTextProof.h"
#include <assert.h>
#include <stdio.h>

int main() {
    using limbus_ui_labels::translate_status;
    assert(translate_status("Loading") == "加载中");
    assert(translate_status("LOADING...") == "加载中…");
    assert(translate_status("NOW LOADING...") == "加载中…");
    assert(translate_status("Connecting…") == "连接中…");
    assert(translate_status("DOWNLOADING UPDATE") == "下载更新");
    assert(translate_status("DOWNLOADING UPDATE 33.22% 0/1") == "下载更新 33.22% 0/1");
    assert(translate_status("DOWNLOADING UPDATE 100% 1/1") == "下载更新 100% 1/1");
    assert(translate_status("DOWNLOADING UPDATE 33.22% 0/1 extra").empty());
    assert(translate_status("DOWNLOADING UPDATE assets/1").empty());
    assert(translate_status("DOWNLOADING UPDATE 33.% 0/1").empty());
    // A label rule must never rewrite URLs, resource paths, or game prose.
    assert(translate_status("LoadingSceneManager").empty());
    assert(translate_status("Loading/asset.bundle").empty());
    assert(translate_status("https://example.com/Loading").empty());
    assert(translate_status("Loading the story...").empty());
    assert(translate_status("LOADING\nplayer name").empty());
    using limbus_text_provenance::contains_translated_field;
    assert(contains_translated_field("蜘蛛巢 环指 父辈", "蜘蛛巢\n环指 父辈"));
    assert(contains_translated_field("蜘蛛巢环指 父辈，鸿璐的故事", "蜘蛛巢\n环指 父辈"));
    assert(contains_translated_field("鸿璐的故事", "鸿璐"));
    assert(!contains_translated_field("player 12345", "12345"));
    assert(!contains_translated_field("无关界面", "鸿璐"));
    assert(!contains_translated_field("显示为中文", "中文不完整"));
    assert(!contains_translated_field("环指 父辈", "<color=red>环指 父辈</color>"));
    using namespace limbus_text_provenance;
    assert(skill_root_key("100") == "@skill:100");
    assert(gift_root_key("9827") == "@ego-gift:9827");
    assert(gift_ability_key("@ego-gift:9827", "98271") == "@ego-gift:9827/ability:98271");
    assert(gift_ability_key("@skill:9827", "98271").empty());
    assert(gift_ability_key("@ego-gift:9827/ability:98271", "98271").empty());
    assert(gift_ability_key("@ego-gift:9827", "unknown").empty());
    assert(event_root_key("901006") == "@action-event:901006");
    assert(event_option_key(event_root_key("901006"), 1) == "@action-event:901006/option:1");
    assert(event_option_key("@skill:901006", 1).empty());
    assert(event_option_key(event_root_key("901006"), 512).empty());
    assert(guide_story_key(guide_root_key("8045"), "3") == "@abnormality-guide:8045/level:3");
    assert(guide_story_key(guide_root_key("8045"), "").empty());
    assert(guide_story_key("@abnormality-guide:8045/level:1", "2").empty());
    assert(rpg_dialogue_text_key(rpg_dialogue_root_key("chapter10_01"), "2") == "@rpg-dialogue:chapter10_01/text:2");
    assert(rpg_dialogue_root_key("chapter/10").empty());
    assert(rpg_dialogue_root_key(std::string(97, 'x')).empty());
    assert(rpg_dialogue_text_key("@rpg-dialogue:chapter10_01/text:0", "2").empty());
    assert(rpg_dialogue_text_key("@skill:100", "2").empty());
    assert(rpg_dialogue_text_key(rpg_dialogue_root_key("chapter10_01"), "-1").empty());
    assert(story_scene_key("StoryData/JP_P10612.json") == "@story:P10612");
    assert(story_scene_key("P10612") == "@story:P10612");
    assert(story_scene_key("../P10612").empty());
    assert(story_scene_key("P10612/8").empty());
    assert(story_scene_key(std::string(97, 'x')).empty());
    assert(story_dialog_key(story_scene_key("P10612"), "8") == "@story:P10612/dialog:8");
    assert(story_dialog_key("@story:P10612/dialog:8", "9").empty());
    assert(story_dialog_key(story_scene_key("P10612"), "-1").empty());
    assert(story_parent_key("@story:P10612/dialog:8") == "@story:P10612");
    assert(story_parent_key("@story:P10612/dialog:8/dialog:9").empty());
    assert(story_parent_key("@story:P10612").empty());
    assert(text_collection_type("TextData_DungeonEvents_Options[]", "TextData_DungeonEvents_Options", false));
    assert(text_collection_type("System.Collections.Generic.List`1<AbnormalityStoryStaticData>", "AbnormalityStoryStaticData", false));
    assert(text_collection_type("System.Collections.Generic.Dictionary`2<System.Int32, AbnormalityStoryStaticData>", "AbnormalityStoryStaticData", true));
    assert(!text_collection_type("System.Int32", "AbnormalityStoryStaticData", false));
    assert(!text_collection_type("System.Collections.Generic.List<System.String>", "AbnormalityStoryStaticData", false));
    assert(!text_collection_type("AbnormalityStoryStaticData[]", "AbnormalityStoryStaticData", true));
    assert(skill_root_key("not-an-id").empty());
    assert(skill_child_key("@skill:100", "levelList", "4", 0) == "@skill:100/level:4");
    assert(skill_child_key("@skill:100/level:1", "coinlist", "", 0) == "@skill:100/level:1/coin:0");
    assert(skill_child_key("@skill:100/level:1/coin:0", "coindescs", "", 0) == "@skill:100/level:1/coin:0/text:0");
    assert(skill_child_key("@skill:100", "levelList", "", 0).empty());
    assert(skill_child_key("@skill:100", "unknown", "1", 0).empty());
    assert(single_line_label(12, false, false, 40, 32));
    assert(!single_line_label(12, false, false, 160, 32));
    assert(!single_line_label(12, false, false, 0, 32));
    assert(!single_line_label(12, true, true, 40, 32));
    assert(!single_line_label(12, false, true, 160, 32));
    assert(single_line_label(12, false, true, 0, 32));
    assert(nominal_font_size(12, true, 32) == 32);
    assert(nominal_font_size(12, false, 32) == 12);
    assert(nominal_font_size(12, true, 0) == 12);
    assert(readable_minimum(30, 3) == 24);
    assert(readable_minimum(30, 27) == 27);
    assert(readable_minimum(8, 0) == 8);
    assert(limbus_rich_text::visible_codepoints("<……我想问你一些事。>") == 12);
    assert(limbus_rich_text::visible_codepoints("<color=#fff000>中文</color>") == 2);
    assert(limbus_rich_text::visible_codepoints("<B>中文</B><sprite=1>") == 3);
    assert(limbus_rich_text::visible_codepoints("<noparse><b>中文</b></noparse>") == 9);
    assert(limbus_rich_text::visible_codepoints("<未知标签>中文") == 8);
    assert(limbus_rich_text::protected_end("<术语>", 0) == 0);
    assert(limbus_rich_text::protected_end("<color=red>术语</color>", 0) == 11);
    assert(limbus_rich_text::protected_end("<noparse><color=red></noparse>", 0) == 30);
    assert(explicit_line_break("第一段\n第二段"));
    assert(explicit_line_break("第一段<br>第二段"));
    assert(explicit_line_break("第一段<BR>第二段"));
    assert(!explicit_line_break("<b>普通标签</b>"));
    assert(!explicit_line_break("<noparse><br></noparse>"));
    for (int overflow : {2, 3, 4, 5, 6})
        assert(!single_line_label(12, false, true, 40, 32, overflow));
    assert(is_gp_formatter_parameter("System.Object[]"));
    assert(is_gp_formatter_parameter("System.Int32"));
    assert(!is_gp_formatter_parameter("System.Single"));
    assert(!is_gp_formatter_parameter("System.Double"));
    assert(!is_gp_formatter_parameter("UnityEngine.Vector3"));
    assert(!is_gp_formatter_parameter(nullptr));
    const std::string label = "蜘蛛巢 环指 父辈，鸿璐的故事";
    const std::string rendered = "蜘蛛巢 环指 父辈, 鸿璐的故事";
    assert(LabelEqual{}(label, rendered));
    assert(LabelHash{}(label) == LabelHash{}(rendered));
    assert(!LabelEqual{}(label, rendered + "尚未翻译"));
    assert(!LabelEqual{}("ホンル的故事", "鸿璐的故事"));
    assert(!LabelEqual{}("<color=red>鸿璐</color>", "鸿璐"));
    assert(!is_short_translated_label("12345678"));
    assert(!is_short_translated_label(std::string(257, 'x') + "中文"));
    limbus_font_proof::Catalogue fonts;
    fonts.add("经验");
    // These wrappers exist in the shipped game's literal table. Font evidence
    // must survive TMP's case-insensitive tags and short color notation.
    assert(fonts.contains("<#a16a3b>经验</color>"));
    assert(fonts.contains("<B><COLOR=#ffffff>经验</COLOR></B>"));
    assert(fonts.contains("<font-weight=700>经验</font-weight>"));
    assert(!fonts.contains("<#not-a-color>经验</color>"));
    assert(!fonts.contains("<FONT=Unknown>经验</FONT>"));
    fonts.add("N公司E.G.O::<NOPARSE><b>凶弹</b></NOPARSE> 李箱");
    assert(fonts.contains("<COLOR=red>N公司E.G.O::<NOPARSE><b>凶弹</b></NOPARSE> 李箱</COLOR>"));
    assert(!fonts.contains("N公司E.G.O::<NOPARSE>凶弹</NOPARSE> 李箱"));
    fonts.add("し协会\n南部5科");
    assert(fonts.contains("<b>し协会<br>南部5科</b>"));
    assert(fonts.contains("し协会<BR><voffset=2>南部5科</voffset>"));
    assert(!fonts.contains("し协会<br=unknown>南部5科"));
    assert(!fonts.contains("し协会<sprite name=unknown>南部5科"));
    assert(!fonts.contains("し协会<br>南部5科新增正文"));
    fonts.add("N公司E.G.O::<noparse>凶弹</noparse> 李箱");
    assert(fonts.contains("N公司E.G.O::<noparse>凶弹</noparse> 李箱"));
    assert(!fonts.contains("<noparse>凶弹</noparse>"));
    assert(!fonts.contains("N公司E.G.O::<noparse>凶弹</noparse> 李箱额外正文"));
    fonts.add("初始<color=#dd0000>经费</color> +{0}");
    fonts.add("<color=#dd0000>主题卡包</color>出现数量 +{0}");
    assert(fonts.contains("• 初始<color=#dd0000>经费</color> +150\n• 主题卡包出现数量 +1"));
    assert(fonts.contains("• 初始经费 +150"));
    assert(fonts.contains("• 初始经费 +150<br>• 主题卡包出现数量 +1"));
    assert(!fonts.contains("• 初始经费 +150\n• 未识别的说明"));
    assert(!fonts.contains("• 初始经费 +150\n未识别的附加正文"));
    assert(!fonts.contains("• 初始经费 +玩家名字\n• 主题卡包出现数量 +1"));
    assert(!fonts.contains("• <font=Unknown>初始经费 +150</font>"));
    assert(!fonts.contains("• 初始经费 +150\n•"));
    fonts.add("脑啡肽模块");
    fonts.add("{0}花费x{1}");
    // The real diagnostic hash resolves to this complete official template
    // with the game's diamond prefix. The prefix is not a new translation.
    assert(fonts.contains("◆ 脑啡肽模块花费x2"));
    assert(fonts.contains("<COLOR=red>◆ 脑啡肽模块花费x2</COLOR>"));
    assert(fonts.contains("◆ 脑啡肽模块花费x2\n◆ 初始经费 +150"));
    assert(!fonts.contains("◆ 未知名称花费x2"));
    assert(!fonts.contains("◆ 脑啡肽模块花费x2附加正文"));
    assert(!fonts.contains("◆◆ 脑啡肽模块花费x2"));
    assert(!fonts.contains("• • 初始经费 +150"));
    assert(!fonts.contains("<noparse>◆ 脑啡肽模块花费x2</noparse>"));
    assert(!fonts.contains("◆ 脑啡肽模块花费x2\n◆ 尚未翻译"));
    for (const auto value : {"30", "04:30", "133 / 133", "251.91", "(25%)", "<color=yellow>04:30</color>"})
        assert(limbus_font_proof::numeric_display(value));
    for (const auto value : {"30层", "在04:30后恢复1脑啡肽。", "Sinner", "", ":/", "{0}"})
        assert(!limbus_font_proof::numeric_display(value));
    fonts.add("剩余{0}次");
    fonts.add("在{0}后恢复{1}脑啡肽。");
    fonts.add("要补充{0}脑啡肽吗？");
    fonts.add("{0}的故事");
    fonts.add("环指 父辈");
    fonts.add("第{0:D2}层，{0:D2}层入口");
    fonts.add("进度{0}/{1}");
    const std::string paragraph = std::string(270, 'x') + "已有中文正文";
    fonts.add(paragraph);
    assert(fonts.contains("剩余3次"));
    assert(fonts.contains("剩余<color=#ffaa00>3</color>次"));
    assert(fonts.contains("<size=30>要补充<color=yellow>120</color>脑啡肽吗？</size>"));
    assert(fonts.contains("在02:14后恢复1脑啡肽。"));
    assert(fonts.contains("<b>环指 父辈</b>的故事"));
    assert(fonts.contains("第02层，02层入口"));
    assert(fonts.contains("进度1/12"));
    assert(fonts.contains("<color=yellow>" + paragraph + "</color>"));
    assert(!fonts.contains("剩余abc次"));
    assert(!fonts.contains("剩余次"));
    assert(!fonts.contains("剩余3次，尚未翻译"));
    assert(!fonts.contains("在日本語后恢复1脑啡肽。"));
    assert(!fonts.contains("第02层，03层入口"));
    assert(!fonts.contains("玩家自定义名称的故事"));
    assert(!fonts.contains("<font=Unknown>剩余3次</font>"));
    assert(!fonts.contains("<sprite name=icon>剩余3次"));
    assert(!fonts.contains("<noparse>剩余3次</noparse>"));
    for (const auto value : {"{0}不足请不要靠近我。", "不要靠近我，不要向我伸出{0}！！",
                              "让更多的客人为{0}的时尚而狂热！", "战术视野展开。{0}之眼。"})
        fonts.add(value);
    assert(fonts.contains("■■■■■不足请不要靠近我。"));
    assert(fonts.contains("不要靠近我，不要向我伸出■■■■■！！"));
    assert(fonts.contains("让更多的客人为<color=#ffd090>■■■■■</color>的时尚而狂热！"));
    assert(fonts.contains("战术视野展开。■■■■■之眼。"));
    assert(!fonts.contains("■■■■■"));
    assert(!fonts.contains("■■■■不足请不要靠近我。"));
    assert(!fonts.contains("■■■■■■不足请不要靠近我。"));
    assert(!fonts.contains("未知玩家不足请不要靠近我。"));
    assert(!fonts.contains("■■■■■不足请不要靠近我。追加正文"));
    assert(!fonts.contains("<font=Unknown>■■■■■</font>不足请不要靠近我。"));
    assert(fonts.contains("第■■■■■层，■■■■■层入口"));
    assert(!fonts.contains("第■■■■■层，02层入口"));
    const std::vector<std::string> timer = {"在 ", "<color=yellow>02:14</color>", " 后恢复 ", "1", " 脑啡肽。"};
    assert(fonts.fragment_candidate("脑啡肽。"));
    assert(!fonts.contains("脑啡肽。"));  // Never grant global substring provenance.
    for (size_t n : {0u, 2u, 4u}) assert(fonts.contains_fragments(timer, n));
    assert(!fonts.contains_fragments(timer, 1));  // Preserve the numeric typeface.
    assert(!fonts.contains_fragments({"在", "日本語", "后恢复", "1", "脑啡肽。"}, 4));
    assert(!fonts.contains_fragments({"在", "02:14", "后恢复", "1", "脑啡肽。尚未翻译"}, 0));
    assert(!fonts.contains_fragments({"在", "02:14", "后恢复", "1"}, 0));
    assert(!fonts.contains_fragments({"<font=Unknown>在</font>", "02:14", "后恢复", "1", "脑啡肽。"}, 0));
    assert(!fonts.contains_fragments({"<sprite name=icon>在", "02:14", "后恢复", "1", "脑啡肽。"}, 4));
    assert(fonts.contains_fragments({"第", "02", "层，", "02", "层入口"}, 4));
    assert(!fonts.contains_fragments({"第", "02", "层，", "03", "层入口"}, 4));
    // Download dialogs format the released template after its getter returns.
    fonts.add("需要下载{0}的数据。");
    fonts.add("推荐在Wi-Fi环境下进行下载。");
    fonts.add("容量{0}，确认{0}。");
    for (const auto value : {"0 B", "123.4 MB", "1.2 GB", "1,024.25 MiB", "3 KB/s"}) {
        assert(fonts.contains(std::string("需要下载") + value + "的数据。"));
        assert(fonts.contains_fragments({"需要下载", value, "的数据。"}, 0));
        assert(!fonts.contains(value));
    }
    assert(fonts.contains("需要下载<color=#fff>123.4 MB</color>的数据。"));
    assert(fonts.contains("容量1.2 GB，确认1.2 GB。"));
    assert(!fonts.contains("容量1.2 GB，确认1.3 GB。"));
    for (const auto value : {"MB", ".5MB", "1.MB", "1..2MB", "1,23MB", "1,234,MB", "1MBextra",
                            "12MB/s/s", "1/2GB", "NaNMB", "-1MB", "1e3MB", "玩家MB",
                            "<font=Other>1MB</font>", "<sprite=0>1MB"}) {
        assert(!fonts.contains(std::string("需要下载") + value + "的数据。"));
        assert(!fonts.contains_fragments({"需要下载", value, "的数据。"}, 0));
    }
    assert(fonts.contains("需要下载123.4 MB的数据。\n推荐在Wi-Fi环境下进行下载。"));
    assert(fonts.contains("需要下载123.4 MB的数据。<br>推荐在Wi-Fi环境下进行下载。"));
    assert(!fonts.contains("需要下载123.4 MB的数据。\n未知的正文。"));
    assert(!fonts.contains("需要下载123.4 MB的数据。\n推荐在Wi-Fi环境下进行下载。附加未知内容"));
    assert(!fonts.contains("需要下载\n123.4 MB的数据。未知内容"));
    assert(!fonts.contains("<font=Other>需要下载123.4 MB的数据。\n推荐在Wi-Fi环境下进行下载。</font>"));
    std::string too_many_paragraphs;
    for (int n = 0; n < 17; ++n) too_many_paragraphs += "需要下载123.4 MB的数据。\n";
    assert(!fonts.contains(too_many_paragraphs));
    assert(!fonts.fragment_candidate(std::string(513, 'x')));
    assert(!fonts.contains(std::string(16385, 'x')));
    assert(limbus_font_proof::pattern("{0}{1}中文").slots.empty());
    assert(limbus_font_proof::pattern("剩余{99}次").slots.empty());
    assert(limbus_font_proof::pattern("剩余{0").slots.empty());
    assert(limbus_font_proof::pattern("https://{0}").slots.empty());
    for (int i = 0; i < 1600; ++i) assert(fonts.contains("剩余" + std::to_string(i) + "次"));
    // Only a verified complete event may authorize its own progressive view.
    const std::string event = "机器人吃力地举起双臂。\n\n终端上显示出这样的话。\n\n<欢迎光临，尊敬的客人。>";
    fonts.add(event);
    limbus_font_proof::FlowProof flow;
    assert(flow.assign(event, fonts.contains(event)));
    assert(flow.matches("机器人吃"));
    assert(flow.matches("机器人吃力地举起双臂。\n\n终端"));
    assert(flow.matches("<color=yellow>终端上显示</color>"));
    assert(flow.matches("<欢迎光临，尊敬的客人。>"));
    assert(!flow.matches("吃力地举起双臂。"));  // No arbitrary middle substring.
    assert(!flow.matches("终端上显示出未授权的新句子。"));
    assert(!flow.matches("<font=Other>终端上显示</font>"));
    assert(!flow.matches("123"));
    assert(!fonts.contains("机器人吃"));  // Scoped proof never changes the catalogue.
    assert(!flow.assign("未知事件。", false));
    assert(!flow.matches("机器人吃"));
    assert(!flow.assign("<NOPARSE>未知事件。</NOPARSE>", true));
    assert(!flow.assign(std::string(16385, 'x'), true));
    assert(flow.assign(event, true));
    flow.clear();
    assert(!flow.matches("机器人吃"));
    fonts.clear();
    assert(!fonts.contains("剩余3次"));
    puts("Native labels, font provenance, formatted values and rejection boundaries passed");
}
