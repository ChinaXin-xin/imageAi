package xin.students.imageaioriginal.service;

import org.junit.jupiter.api.Test;
import xin.students.imageaioriginal.model.ImageTaskPayload;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImageTaskPromptBuilderTests {

    private final ImageTaskPromptBuilder builder = new ImageTaskPromptBuilder(null);

    @Test
    void buildGenerationPromptDoesNotAppendLayoutImageAnalysis() {
        ImageTaskPayload payload = payload(List.of("MAIN", "INTRO"));
        Map<String, String> analysis = Map.of(
                "实拍图", "实拍结构：异形一体式镜头膜，6 个孔位，大小不一致。",
                "排版图", "旧版式分析文本：上方大卡片，下方三列小卡片。"
        );

        String prompt = builder.buildGenerationPrompt(
                "主图",
                "生成主图。",
                payload,
                analysis,
                new UploadMaterialContext(true, true, false),
                null
        );

        assertThat(prompt)
                .contains("实拍结构：异形一体式镜头膜")
                .contains("【主图排版图约束】")
                .doesNotContain("【主图排版图版式分析】")
                .doesNotContain("旧版式分析文本");
    }

    @Test
    void generationItemPromptKeepsFullFinalPromptThenAppendsScene() {
        ImageTaskPayload payload = payload(List.of("MAIN", "INTRO"));
        String finalPrompt = "【最高优先级：结构锁定】完整最终提示词\n【生成要求】保留全部原始约束。";
        ImageScenePromptService.ScenePrompt scene = new ImageScenePromptService.ScenePrompt(
                7,
                "第七张场景",
                "场景 7：按排版图主体区摆放，光影与前几张不同。"
        );

        String itemPrompt = builder.generationItemPrompt(finalPrompt, "主图", 7, 7, scene, payload);

        assertThat(itemPrompt)
                .startsWith(finalPrompt)
                .contains("【当前生成】主图第 7 / 7 张")
                .contains("场景标题：第七张场景")
                .contains("场景 7：按排版图主体区摆放")
                .contains("【本张成品自审与修正】");
    }

    private ImageTaskPayload payload(List<String> templateUsages) {
        return new ImageTaskPayload(
                "测试商品",
                "三星S23U",
                "Amazon",
                "1536:1536",
                1536,
                1536,
                "自动",
                "",
                "",
                "自动",
                "自动",
                List.of("高清透亮", "镜头保护"),
                true,
                false,
                1,
                0,
                7,
                0,
                "英文",
                "生成主图。",
                "生成介绍图。",
                null,
                null,
                templateUsages,
                List.of("MAIN", "INTRO"),
                List.of()
        );
    }
}
