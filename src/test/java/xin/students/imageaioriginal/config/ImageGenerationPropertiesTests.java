package xin.students.imageaioriginal.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageGenerationPropertiesTests {

    @Test
    void defaultsSupportTenConcurrentImageApiCalls() {
        ImageGenerationProperties properties = new ImageGenerationProperties(null, null, null, null);

        assertThat(properties.resolvedMaxImagesPerTask()).isEqualTo(10);
        assertThat(properties.resolvedMaxGlobalImageConcurrency()).isEqualTo(10);
    }

    @Test
    void configuredConcurrencyIsStillBounded() {
        ImageGenerationProperties properties = new ImageGenerationProperties(null, 99, 99, 99);

        assertThat(properties.resolvedMaxTaskConcurrency()).isEqualTo(16);
        assertThat(properties.resolvedMaxImagesPerTask()).isEqualTo(12);
        assertThat(properties.resolvedMaxGlobalImageConcurrency()).isEqualTo(32);
    }
}
