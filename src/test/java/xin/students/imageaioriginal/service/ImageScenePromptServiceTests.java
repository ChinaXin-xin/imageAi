package xin.students.imageaioriginal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class ImageScenePromptServiceTests {

    private static final String TEST_SCENE_PROMPT = """
            1. 3D立体斜角安装态：手机完整入画，屏幕膜/钢化膜与手机分层错位展示。
            2. 规整平铺套装图：俯拍或45度俯拍，手机、膜片和已选配件按真实比例整齐分区。
            3. 近景结构细节图：保留主商品整体关系，用旁侧局部放大或边缘高光展示贴合细节。
            4. 防窥卖点图：展示侧视隐私效果。
            5. 高清透亮卖点图：突出透明清亮玻璃质感。
            """;

    private final ImageScenePromptService service = new ImageScenePromptService(
            null,
            null,
            RestClient.builder(),
            new ObjectMapper()
    );

    @Test
    void detectsForbiddenSceneObjects() {
        assertThat(service.containsForbiddenObject("加入一个黑色便携袋和展示支架突出套装质感")).isTrue();
        assertThat(service.containsForbiddenObject("加入一个空白白色小袋和无字黑色小袋")).isTrue();
        assertThat(service.containsForbiddenObject("只展示手机模型、屏幕膜和一体式镜头膜")).isFalse();
        assertThat(service.containsForbiddenObject("展示按参考图识别的清洁配件、安装辅助贴和除尘贴")).isFalse();
        assertThat(service.containsForbiddenObject("展示参考图清洁包，保留参考图可见文字")).isFalse();
    }

    @Test
    void fallbackSceneKeepsGenericLensStructureLock() {
        var scenes = service.planScenes(
                "主图",
                "某型号手机镜头膜，上传图显示异形外轮廓和大小不同的孔位，镜头保护",
                1,
                TEST_SCENE_PROMPT
        );

        assertThat(scenes).hasSize(1);
        assertThat(scenes.get(0).prompt())
                .contains("按上传图")
                .contains("对应机型")
                .contains("孔位数量")
                .contains("大小差异");
    }

    @Test
    void simulatedFallbackScenesStaySafeAndKeepGenericLensLock() {
        var scenes = service.fallbackScenes("主图", 5, true, TEST_SCENE_PROMPT);

        assertThat(scenes).hasSize(5);
        assertThat(scenes)
                .anySatisfy(scene -> assertThat(scene.prompt()).contains("3D立体斜角"))
                .anySatisfy(scene -> assertThat(scene.prompt()).contains("平铺"))
                .anySatisfy(scene -> assertThat(scene.prompt()).contains("近景结构细节"));
        assertThat(scenes)
                .anySatisfy(scene -> assertThat(scene.prompt()).contains("手机完整入画"))
                .anySatisfy(scene -> assertThat(scene.prompt()).contains("真实比例"))
                .anySatisfy(scene -> assertThat(scene.prompt()).containsAnyOf("已选配件", "整齐"));
        assertThat(scenes).allSatisfy(scene -> {
            assertThat(service.containsForbiddenObject(scene.prompt())).isFalse();
            assertThat(scene.prompt())
                    .contains("主图必须无文字")
                    .contains("对应机型")
                    .contains("孔位数量")
                    .contains("大小差异");
        });
    }

    @Test
    void fallbackScenesUseConfiguredSceneLines() {
        var scenes = service.fallbackScenes("主图", 3, false, """
                第一张展示高清透亮卖点
                第二张展示防指纹疏油卖点
                第三张展示易安装步骤感
                """);

        assertThat(scenes).hasSize(3);
        assertThat(scenes.get(0).prompt()).contains("展示高清透亮卖点");
        assertThat(scenes.get(1).prompt()).contains("展示防指纹疏油卖点");
        assertThat(scenes.get(2).prompt()).contains("展示易安装步骤感");
    }
}
