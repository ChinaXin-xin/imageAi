package xin.students.imageaioriginal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import xin.students.imageaioriginal.config.GptProperties;
import xin.students.imageaioriginal.config.ImageGenerationProperties;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class ImageGenerationService {

    private static final Logger LOG = LoggerFactory.getLogger("imageai.gpt");

    private final GptProperties gptProperties;
    private final ImageGenerationProperties imageGenerationProperties;
    private final UploadImageAnalysisService uploadImageAnalysisService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ImageGenerationService(
            GptProperties gptProperties,
            ImageGenerationProperties imageGenerationProperties,
            UploadImageAnalysisService uploadImageAnalysisService,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.gptProperties = gptProperties;
        this.imageGenerationProperties = imageGenerationProperties;
        this.uploadImageAnalysisService = uploadImageAnalysisService;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public GeneratedImage generate(String taskId, String resultType, int itemIndex, String prompt, int width, int height) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        String model = imageGenerationProperties.resolvedModel();
        String size = Math.max(1, width) + "x" + Math.max(1, height);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("prompt", prompt);
        request.put("n", 1);
        request.put("size", size);

        LOG.info(
                "gpt.image.start id={} taskId={} type={} index={} model={} size={} prompt={}",
                requestId,
                taskId,
                resultType,
                itemIndex,
                model,
                size,
                prompt
        );

        JsonNode response = restClient.post()
                .uri(imageGenerationUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + uploadImageAnalysisService.resolveApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);

        JsonNode first = response == null ? null : response.path("data").path(0);
        String imageUrl = text(first, "url");
        String b64Json = text(first, "b64_json", "b64Json");
        String revisedPrompt = text(first, "revised_prompt", "revisedPrompt");
        String rawResponse = toJson(response);
        if ((imageUrl == null || imageUrl.isBlank()) && (b64Json == null || b64Json.isBlank())) {
            throw new IllegalStateException("生图接口未返回图片地址或图片 base64：" + abbreviate(rawResponse, 1000));
        }

        LOG.info(
                "gpt.image.response id={} taskId={} type={} index={} model={} imageUrl={} b64Bytes={} revisedPrompt={}",
                requestId,
                taskId,
                resultType,
                itemIndex,
                model,
                imageUrl == null ? "-" : imageUrl,
                b64Json == null ? 0 : b64Json.length(),
                revisedPrompt == null ? "-" : revisedPrompt
        );
        return new GeneratedImage(imageUrl, b64Json, revisedPrompt, rawResponse);
    }

    private String imageGenerationUrl() {
        String baseUrl = gptProperties.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = gptProperties.resolvedBaseUrl();
        }
        baseUrl = baseUrl.trim().replaceAll("/+$", "");
        if (baseUrl.endsWith("/v1")) {
            return baseUrl + "/images/generations";
        }
        return baseUrl + "/v1/images/generations";
    }

    private String text(JsonNode node, String... names) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private String toJson(JsonNode response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception ex) {
            return String.valueOf(response);
        }
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    public record GeneratedImage(
            String imageUrl,
            String imageBase64,
            String revisedPrompt,
            String rawResponse
    ) {
    }
}
