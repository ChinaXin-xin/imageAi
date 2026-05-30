package xin.students.imageaioriginal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "image-ai.gpt")
public record GptProperties(
        String baseUrl,
        String apiKey,
        String model
) {
    public String resolvedBaseUrl() {
        return baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com" : baseUrl.replaceAll("/+$", "");
    }

    public String resolvedModel() {
        return model == null || model.isBlank() ? "gpt-5.5" : model;
    }
}
