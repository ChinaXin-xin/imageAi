package xin.students.imageaioriginal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class ImageScenePromptServiceTests {

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
        var scenes = service.planScenes("主图", "某型号手机镜头膜，上传图显示异形外轮廓和大小不同的孔位，镜头保护", 1);

        assertThat(scenes).hasSize(1);
        assertThat(scenes.get(0).prompt())
                .contains("按上传图")
                .contains("对应机型")
                .contains("孔位数量")
                .contains("大小差异");
    }

    @Test
    void simulatedFallbackScenesStaySafeAndKeepGenericLensLock() {
        var scenes = service.fallbackScenes("主图", 5, true);

        assertThat(scenes).hasSize(5);
        assertThat(scenes)
                .anySatisfy(scene -> assertThat(scene.prompt()).contains("3D 立体斜角"))
                .anySatisfy(scene -> assertThat(scene.prompt()).contains("平铺"))
                .anySatisfy(scene -> assertThat(scene.prompt()).contains("精密结构"));
        assertThat(scenes)
                .anySatisfy(scene -> assertThat(scene.prompt()).contains("手机完整入画"))
                .anySatisfy(scene -> assertThat(scene.prompt()).contains("尺寸匹配"))
                .anySatisfy(scene -> assertThat(scene.prompt()).containsAnyOf("配件区", "整齐"));
        assertThat(scenes).allSatisfy(scene -> {
            assertThat(service.containsForbiddenObject(scene.prompt())).isFalse();
            assertThat(scene.prompt())
                    .contains("无文字")
                    .contains("对应机型")
                    .contains("孔位数量")
                    .contains("大小差异");
        });
    }
}
