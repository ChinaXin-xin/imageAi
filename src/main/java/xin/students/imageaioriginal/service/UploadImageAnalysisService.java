package xin.students.imageaioriginal.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import xin.students.imageaioriginal.config.GptProperties;
import xin.students.imageaioriginal.model.UploadImageAnalysis;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class UploadImageAnalysisService {

    private final GptProperties gptProperties;
    private final RestClient restClient;

    public UploadImageAnalysisService(GptProperties gptProperties, RestClient.Builder restClientBuilder) {
        this.gptProperties = gptProperties;
        this.restClient = restClientBuilder.build();
    }

    public UploadImageAnalysis analyze(String type, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("请先上传需要深析的图片");
        }
        if (gptProperties.apiKey() == null || gptProperties.apiKey().isBlank()) {
            throw new IllegalStateException("未配置 image-ai.gpt.api-key，无法调用 GPT 5.5 深析上传图");
        }

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of(
                "type", "text",
                "text", buildPrompt(type, files.size())
        ));

        for (MultipartFile file : files) {
            content.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", toDataUrl(file))
            ));
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", gptProperties.resolvedModel());
        request.put("temperature", 0.2);
        request.put("messages", List.of(Map.of(
                "role", "user",
                "content", content
        )));

        JsonNode response = restClient.post()
                .uri(gptProperties.resolvedBaseUrl() + "/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + gptProperties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);

        return new UploadImageAnalysis(type, gptProperties.resolvedModel(), extractText(response));
    }

    private String buildPrompt(String type, int count) {
        return """
                你是跨境电商手机配件视觉分析专家。请深度分析用户上传的 %s，共 %d 张。
                输出中文，结构清晰，不要编造看不见的信息。请包含：
                1. 产品类型与可能机型/适配范围；
                2. 包装、材质、颜色、透明度、边缘细节；
                3. 可用于主图的画面重点；
                4. 可用于介绍图的卖点提炼；
                5. 风险与缺失信息；
                6. 建议用于生成任务的提示词补充。
                """.formatted(type, count);
    }

    private String toDataUrl(MultipartFile file) {
        try {
            String contentType = file.getContentType();
            if (contentType == null || contentType.isBlank()) {
                contentType = "image/png";
            }
            String base64 = Base64.getEncoder().encodeToString(file.getBytes());
            return "data:" + contentType + ";base64," + base64;
        } catch (IOException ex) {
            throw new IllegalStateException("读取上传图片失败：" + file.getOriginalFilename(), ex);
        }
    }

    private String extractText(JsonNode response) {
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (content.isTextual() && !content.asText().isBlank()) {
            return content.asText();
        }
        throw new IllegalStateException("GPT 5.5 未返回有效分析结果");
    }
}
