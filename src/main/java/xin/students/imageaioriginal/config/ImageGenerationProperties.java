package xin.students.imageaioriginal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "image-ai.image-generation")
public record ImageGenerationProperties(
        String model
) {
    public String resolvedModel() {
        return model == null || model.isBlank() ? "gpt-image-2" : model.trim();
    }
}
