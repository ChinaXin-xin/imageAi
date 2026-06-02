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
        assertThat(service.containsForbiddenObject("展示无尘布、酒精清洁包、定位神器、刮板、安装辅助贴和防滑垫")).isFalse();
        assertThat(service.containsForbiddenObject("展示黑色方形 WET WIPES 包，保留白色 WET WIPES 文字")).isFalse();
    }

    @Test
    void fallbackSceneKeepsS23UltraLensHoleLock() {
        var scenes = service.planScenes("主图", "三星 S23U 镜头膜，一体式片状结构，镜头保护", 1);

        assertThat(scenes).hasSize(1);
        assertThat(scenes.get(0).prompt())
                .contains("右侧三个小孔")
                .contains("不等大")
                .contains("一体式片状结构");
    }

    @Test
    void simulatedFallbackScenesStaySafeAndKeepS23UltraLock() {
        var scenes = service.fallbackScenes("主图", 5, true);

        assertThat(scenes).hasSize(5);
        assertThat(scenes)
                .anySatisfy(scene -> assertThat(scene.prompt()).contains("3D 立体斜角"))
                .anySatisfy(scene -> assertThat(scene.prompt()).contains("平铺"))
                .anySatisfy(scene -> assertThat(scene.prompt()).contains("近景"));
        assertThat(scenes)
                .anySatisfy(scene -> assertThat(scene.prompt()).contains("手机模型在右侧"))
                .anySatisfy(scene -> assertThat(scene.prompt()).contains("手机模型在左侧"));
        assertThat(scenes).allSatisfy(scene -> {
            assertThat(service.containsForbiddenObject(scene.prompt())).isFalse();
            assertThat(scene.prompt())
                    .contains("无文字")
                    .contains("右侧三个小孔")
                    .contains("不等大")
                    .contains("一体式片状结构");
        });
    }
}
