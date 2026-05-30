package xin.students.imageaioriginal.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import xin.students.imageaioriginal.config.GptProperties;
import xin.students.imageaioriginal.model.UploadImageAnalysis;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class UploadImageAnalysisService {

    private static final int MAX_ANALYSIS_IMAGES = 6;
    private static final long MAX_UPLOAD_FILE_BYTES = 20L * 1024 * 1024;
    private static final int PRIMARY_MAX_EDGE = 1400;
    private static final int FALLBACK_MAX_EDGE = 1000;
    private static final int TARGET_IMAGE_BYTES = 1_200_000;

    private final GptProperties gptProperties;
    private final RestClient restClient;

    public UploadImageAnalysisService(GptProperties gptProperties, RestClient.Builder restClientBuilder) {
        this.gptProperties = gptProperties;
        this.restClient = restClientBuilder.build();
    }

    public UploadImageAnalysis analyze(String type, List<MultipartFile> files) {
        List<MultipartFile> uploadedFiles = files == null ? List.of() : files.stream()
                .filter(file -> file != null && !file.isEmpty())
                .toList();
        if (uploadedFiles.isEmpty()) {
            throw new IllegalArgumentException("请先上传需要深析的图片");
        }
        for (MultipartFile file : uploadedFiles) {
            if (file.getSize() > MAX_UPLOAD_FILE_BYTES) {
                throw new IllegalArgumentException("单张图片不能超过 20MB：" + file.getOriginalFilename());
            }
        }
        if (gptProperties.apiKey() == null || gptProperties.apiKey().isBlank()) {
            throw new IllegalStateException("未配置 image-ai.gpt.api-key，无法调用 GPT 5.5 深析上传图");
        }

        List<MultipartFile> analysisFiles = uploadedFiles.stream()
                .limit(MAX_ANALYSIS_IMAGES)
                .toList();

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of(
                "type", "text",
                "text", buildPrompt(type, analysisFiles.size(), uploadedFiles.size())
        ));

        for (MultipartFile file : analysisFiles) {
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

    private String buildPrompt(String type, int analyzedCount, int uploadedCount) {
        String limitNote = uploadedCount > analyzedCount
                ? "用户实际上传了 %d 张，为控制请求体积，本次先分析前 %d 张。".formatted(uploadedCount, analyzedCount)
                : "";
        return """
                你是跨境电商手机配件视觉分析专家。请深度分析用户上传的 %s，共 %d 张。%s
                输出中文，结构清晰，不要编造看不见的信息。请包含：
                1. 产品类型与可能机型/适配范围；
                2. 包装、材质、颜色、透明度、边缘细节；
                3. 可用于主图的画面重点；
                4. 可用于介绍图的卖点提炼；
                5. 风险与缺失信息；
                6. 建议用于生成任务的提示词补充。
                """.formatted(type, analyzedCount, limitNote);
    }

    private String toDataUrl(MultipartFile file) {
        try {
            BufferedImage source = ImageIO.read(file.getInputStream());
            if (source == null) {
                throw new IllegalArgumentException("仅支持图片文件：" + file.getOriginalFilename());
            }
            byte[] imageBytes = encodeJpeg(source, PRIMARY_MAX_EDGE, 0.78f);
            if (imageBytes.length > TARGET_IMAGE_BYTES) {
                imageBytes = encodeJpeg(source, FALLBACK_MAX_EDGE, 0.72f);
            }
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            return "data:image/jpeg;base64," + base64;
        } catch (IOException ex) {
            throw new IllegalStateException("读取上传图片失败：" + file.getOriginalFilename(), ex);
        }
    }

    private byte[] encodeJpeg(BufferedImage source, int maxEdge, float quality) throws IOException {
        BufferedImage image = resizeToRgb(source, maxEdge);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private BufferedImage resizeToRgb(BufferedImage source, int maxEdge) {
        int width = source.getWidth();
        int height = source.getHeight();
        double scale = Math.min(1D, (double) maxEdge / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));

        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private String extractText(JsonNode response) {
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (content.isTextual() && !content.asText().isBlank()) {
            return content.asText();
        }
        throw new IllegalStateException("GPT 5.5 未返回有效分析结果");
    }
}
