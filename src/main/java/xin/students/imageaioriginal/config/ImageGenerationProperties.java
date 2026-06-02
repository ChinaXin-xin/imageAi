package xin.students.imageaioriginal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "image-ai.image-generation")
public record ImageGenerationProperties(
        String model,
        Integer maxTaskConcurrency,
        Integer maxImagesPerTask,
        Integer maxGlobalImageConcurrency
) {
    public String resolvedModel() {
        return model == null || model.isBlank() ? "gpt-image-2" : model.trim();
    }

    public int resolvedMaxTaskConcurrency() {
        return bounded(maxTaskConcurrency, 4, 1, 16);
    }

    public int resolvedMaxImagesPerTask() {
        return bounded(maxImagesPerTask, 10, 1, 12);
    }

    public int resolvedMaxGlobalImageConcurrency() {
        return bounded(maxGlobalImageConcurrency, 10, 1, 32);
    }

    private int bounded(Integer value, int fallback, int min, int max) {
        int normalized = value == null ? fallback : value;
        return Math.max(min, Math.min(max, normalized));
    }
}
