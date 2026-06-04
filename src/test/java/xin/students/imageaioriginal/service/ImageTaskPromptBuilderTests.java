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
        String finalPrompt = "【最高优先级：结构锁定】完整最终提示词\n# 【生成要求】\n保留全部原始约束。";
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

    @Test
    void generationItemPromptAppendsMainAndIntroScenesSeparately() {
        ImageTaskPayload payload = payload(List.of("MAIN", "INTRO"));
        String finalMainPrompt = "主图最终生图提示词：无文字平台首图，保留主图结构约束。";
        String finalIntroPrompt = "介绍图最终生图提示词：卖点介绍图，保留介绍图结构约束。";
        ImageScenePromptService.ScenePrompt mainScene = new ImageScenePromptService.ScenePrompt(
                1,
                "主图斜角场景",
                "主图场景规划：手机与钢化膜左右错位，轮廓光不同。"
        );
        ImageScenePromptService.ScenePrompt introScene = new ImageScenePromptService.ScenePrompt(
                1,
                "介绍图卖点场景",
                "介绍图场景规划：按信息区展示卖点层级，产品比例不变。"
        );

        String mainItemPrompt = builder.generationItemPrompt(finalMainPrompt, "主图", 1, 2, mainScene, payload);
        String introItemPrompt = builder.generationItemPrompt(finalIntroPrompt, "介绍图", 1, 2, introScene, payload);

        assertThat(mainItemPrompt)
                .startsWith(finalMainPrompt)
                .contains("【本张图片场景规划】")
                .contains("主图场景规划")
                .doesNotContain(finalIntroPrompt)
                .doesNotContain("介绍图场景规划");
        assertThat(introItemPrompt)
                .startsWith(finalIntroPrompt)
                .contains("【本张图片场景规划】")
                .contains("介绍图场景规划")
                .doesNotContain(finalMainPrompt)
                .doesNotContain("主图场景规划");
    }

    @Test
    void buildGenerationPromptAppendsReferenceStyleAfterImageTypePrompt() {
        ImageTaskPayload payload = payload(List.of("MAIN", "INTRO"));
        TargetTemplateService.TargetTemplateRecord targetTemplate = new TargetTemplateService.TargetTemplateRecord(
                1L,
                "MAIN",
                "主图参考风格",
                "style.png",
                "image/png",
                100L,
                null,
                null,
                null,
                "深色科技背景，蓝色轮廓光，玻璃边缘高光。",
                "gpt-test",
                null,
                null
        );

        String prompt = builder.buildGenerationPrompt(
                "主图",
                "生成主图：主体居中，排版干净。",
                payload,
                Map.of("实拍图", "实拍结构锁定。"),
                new UploadMaterialContext(true, false, false),
                targetTemplate
        );

        assertThat(prompt)
                .containsSubsequence(
                        "# 【主图画面要求】",
                        "生成主图：主体居中，排版干净。",
                        "【主图参考风格图风格】深色科技背景，蓝色轮廓光，玻璃边缘高光。",
                        "【主图参考风格图约束】"
                )
                .doesNotContain("【视觉特效】加强玻璃高光");
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
