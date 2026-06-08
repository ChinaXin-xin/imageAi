package xin.students.imageaioriginal.service;

import org.junit.jupiter.api.Test;
import xin.students.imageaioriginal.model.ImageTaskPayload;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImageTaskPromptBuilderTemplateFillTests {

    private final ImageTaskPromptBuilder builder = new ImageTaskPromptBuilder(null);

    @Test
    void generationItemPromptUsesTemplateFillPromptWhenLayoutImageEnabled() {
        ImageTaskPayload payload = payload();
        String finalPrompt = "主图最终提示词，包含排版图约束。";
        ImageScenePromptService.ScenePrompt scene = new ImageScenePromptService.ScenePrompt(
                1,
                "主图斜角场景",
                "原有场景规划：手机与钢化膜左右错位，轮廓光不同。"
        );

        String itemPrompt = builder.generationItemPrompt(finalPrompt, "主图", 1, 3, scene, payload, true);

        assertThat(itemPrompt)
                .startsWith(finalPrompt)
                .contains("# 【本张图片排版图填充生成要求】")
                .contains("排版图/样板图是本次生图接口参考图中的最后一张输入图片")
                .contains("按最后一张排版图完成素材填充")
                .doesNotContain("# 【本张图片场景规划】")
                .doesNotContain("手机与钢化膜左右错位");
    }

    private ImageTaskPayload payload() {
        return new ImageTaskPayload(
                "测试商品",
                "三星S23U",
                "Amazon",
                "1536:1536",
                1536,
                1536,
                "1536:1536",
                1536,
                1536,
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
                3,
                0,
                "英文",
                "生成主图。",
                "生成介绍图。",
                "1. 套装合集图\n2. 高清透亮卖点图",
                null,
                null,
                List.of("MAIN", "INTRO"),
                List.of("MAIN", "INTRO"),
                List.of()
        );
    }
}
