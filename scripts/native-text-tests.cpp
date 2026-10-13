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
    assert(single_line_label(12, false, false, 40, 32));
    assert(!single_line_label(12, false, false, 160, 32));
    assert(!single_line_label(12, false, false, 0, 32));
    assert(!single_line_label(12, true, true, 40, 32));
    assert(single_line_label(12, false, true, 160, 32));
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
