package xin.students.imageaioriginal.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import xin.students.imageaioriginal.config.CliProxyProperties;
import xin.students.imageaioriginal.config.GptProperties;
import xin.students.imageaioriginal.model.StoredUploadImage;
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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UploadImageAnalysisService {

    private static final Logger LOG = LoggerFactory.getLogger("imageai.gpt");
    private static final String MANAGEMENT_SUFFIX = "/v0/management";
    private static final int MAX_ANALYSIS_IMAGES = 6;
    private static final long MAX_UPLOAD_FILE_BYTES = 20L * 1024 * 1024;
    private static final int PRIMARY_MAX_EDGE = 1400;
    private static final int FALLBACK_MAX_EDGE = 1000;
    private static final int TARGET_IMAGE_BYTES = 1_200_000;
    private static final String DEFAULT_ANALYSIS_PROMPT = """
            请客观深析上传图片，重点输出后续生图必须锁定的真实产品结构，不要编造看不见的信息。

            如果图片中包含手机膜、镜头膜、保护壳或电子配件，必须逐项描述：
            1. 产品类型、外轮廓、边缘形状、缺口、倒角和厚度感；
            2. 开孔/孔位数量、相对位置、排列方向、每个孔的相对大小；
            3. 哪些孔位大小不一致、哪些结构是非对称或异形结构；
            4. 材质、颜色、透明度、反光、高光、表面纹理；
            5. 仅在图片可见时描述客户可用配件：无尘布、酒精清洁包、定位神器、刮板、安装辅助贴、镜头膜安装辅助贴防滑垫；输出它们的形状、颜色、数量和相对尺寸；
            6. 生图时必须禁止模型改成通用款、标准款或常见款的关键细节。
            7. 如果出现酒精清洁包/湿巾包，必须识别它是黑色还是白色、方形还是长方形、是否有文字；若有文字，必须抄出可见文字（例如 WET WIPES、Remove Dust、Effective Sterilization），后续生图只能复现这些参考图文字，不要生成无字小袋或凭空文字。
            8. 客户产品范围只有钢化膜、防窥膜、镜头膜；除已上传或已选择的上述配件外，不要推断包装盒、收纳袋、卡片、托盘、支架、底座或其他赠品。

            输出请按“图片1、图片2...”分别描述，最后增加“结构锁定要点”小节，用简短明确的生成约束总结孔位、外形和数量。
            """;
    private static final String STRUCTURE_ANALYSIS_REQUIREMENTS = """

            【精密孔位强制要求】
            如果图片中出现镜头膜/镜头保护片，必须额外输出：
            - 它是一体式片状结构还是分离镜圈；
            - 大孔数量、小孔数量，以及左右/上下排列；
            - 每个小孔的相对大小顺序，例如“右上最大、右中最小、右下居中”；
            - 小孔是否等大，若不等大必须明确写出“禁止生成成等大孔”；
            - 外轮廓是否有台阶、凹口、凸起、圆角、异形边缘；
            - 哪些包装/配件没有出现在实拍图里，后续生图不得凭空添加。

            如果是三星 S23U / S23 Ultra 镜头膜，尤其要检查右侧三个小孔是否大小不一致，并明确写出三者的大小关系。
            """;

    private final GptProperties gptProperties;
    private final CliProxyProperties cliProxyProperties;
    private final RestClient restClient;

    public UploadImageAnalysisService(
            GptProperties gptProperties,
            CliProxyProperties cliProxyProperties,
            RestClient.Builder restClientBuilder
    ) {
        this.gptProperties = gptProperties;
        this.cliProxyProperties = cliProxyProperties;
        this.restClient = restClientBuilder.build();
    }

    public UploadImageAnalysis analyze(String type, String prompt, List<MultipartFile> files) {
        List<StoredUploadImage> uploadedFiles = files == null ? List.of() : files.stream()
                .filter(file -> file != null && !file.isEmpty())
                .map(file -> {
                    try {
                        return new StoredUploadImage(file.getOriginalFilename(), file.getContentType(), file.getBytes());
                    } catch (IOException ex) {
                        throw new IllegalStateException("读取上传图片失败：" + file.getOriginalFilename(), ex);
                    }
                })
                .toList();
        return analyzeStored(type, prompt, uploadedFiles);
    }

    public UploadImageAnalysis analyzeStored(String type, String prompt, List<StoredUploadImage> files) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        List<StoredUploadImage> uploadedFiles = files == null ? List.of() : files.stream()
                .filter(file -> file != null && file.bytes() != null && file.bytes().length > 0)
                .toList();
        if (uploadedFiles.isEmpty()) {
            throw new IllegalArgumentException("请先上传需要深析的图片");
        }
        for (StoredUploadImage file : uploadedFiles) {
            if (file.size() > MAX_UPLOAD_FILE_BYTES) {
                throw new IllegalArgumentException("单张图片不能超过 20MB：" + file.fileName());
            }
        }
        String apiKey = resolveApiKey();

        List<StoredUploadImage> analysisFiles = uploadedFiles.stream()
                .limit(MAX_ANALYSIS_IMAGES)
                .toList();

        String finalPrompt = buildPrompt(prompt);
        LOG.info(
                "gpt.analysis.start id={} type={} model={} uploadedCount={} analyzedCount={} prompt={}",
                requestId,
                type,
                gptProperties.resolvedModel(),
                uploadedFiles.size(),
                analysisFiles.size(),
                finalPrompt
        );

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of(
                "type", "text",
                "text", finalPrompt
        ));

        for (StoredUploadImage file : analysisFiles) {
            EncodedImage encodedImage = toDataUrl(file);
            LOG.info(
                    "gpt.analysis.image id={} fileName={} contentType={} originalBytes={} encodedBytes={} width={} height={}",
                    requestId,
                    safeValue(file.fileName()),
                    safeValue(file.contentType()),
                    file.size(),
                    encodedImage.bytes(),
                    encodedImage.width(),
                    encodedImage.height()
            );
            content.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", encodedImage.dataUrl())
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
                .uri(chatCompletionsUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);

        String result = extractText(response);
        LOG.info(
                "gpt.analysis.response id={} model={} result={}",
                requestId,
                gptProperties.resolvedModel(),
                result
        );
        return new UploadImageAnalysis(type, gptProperties.resolvedModel(), result);
    }

    public String resolveApiKey() {
        String configuredApiKey = gptProperties.apiKey();
        if (configuredApiKey != null && !configuredApiKey.isBlank()) {
            return configuredApiKey.trim();
        }

        JsonNode response = restClient.get()
                .uri(managementBaseUrl() + "/api-keys")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + cliProxyProperties.managementKey())
                .retrieve()
                .body(JsonNode.class);

        JsonNode apiKeys = response == null ? null : response.path("api-keys");
        if (apiKeys == null || !apiKeys.isArray()) {
            apiKeys = response == null ? null : response.path("apiKeys");
        }
        if (apiKeys != null && apiKeys.isArray()) {
            for (JsonNode apiKey : apiKeys) {
                if (apiKey.isTextual() && !apiKey.asText().isBlank()) {
                    return apiKey.asText().trim();
                }
            }
        }

        throw new IllegalStateException("未配置 image-ai.gpt.api-key，且未能从 CLIProxy 管理接口读取 api-keys");
    }

    public String chatCompletionsUrl() {
        String baseUrl = gptProperties.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = cliProxyProperties.baseUrl();
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("未配置 image-ai.gpt.base-url 或 image-ai.cli-proxy.base-url");
        }

        baseUrl = baseUrl.trim().replaceAll("/+$", "");
        if (baseUrl.endsWith("/v1")) {
            return baseUrl + "/chat/completions";
        }
        return baseUrl + "/v1/chat/completions";
    }

    private String managementBaseUrl() {
        String baseUrl = cliProxyProperties.baseUrl() == null ? "" : cliProxyProperties.baseUrl().trim();
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException("未配置 image-ai.cli-proxy.base-url");
        }
        baseUrl = baseUrl.replaceAll("/+$", "");
        return baseUrl.endsWith(MANAGEMENT_SUFFIX) ? baseUrl : baseUrl + MANAGEMENT_SUFFIX;
    }

    private String buildPrompt(String prompt) {
        String normalized = prompt == null || prompt.isBlank() ? DEFAULT_ANALYSIS_PROMPT : prompt.trim();
        if (normalized.contains("【精密孔位强制要求】")) {
            return normalized;
        }
        return normalized + STRUCTURE_ANALYSIS_REQUIREMENTS;
    }

    private EncodedImage toDataUrl(StoredUploadImage file) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(file.bytes()));
            if (source == null) {
                throw new IllegalArgumentException("仅支持图片文件：" + file.fileName());
            }
            byte[] imageBytes = encodeJpeg(source, PRIMARY_MAX_EDGE, 0.78f);
            if (imageBytes.length > TARGET_IMAGE_BYTES) {
                imageBytes = encodeJpeg(source, FALLBACK_MAX_EDGE, 0.72f);
            }
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            return new EncodedImage(
                    "data:image/jpeg;base64," + base64,
                    imageBytes.length,
                    source.getWidth(),
                    source.getHeight()
            );
        } catch (IOException ex) {
            throw new IllegalStateException("读取上传图片失败：" + file.fileName(), ex);
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

    private String safeValue(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private record EncodedImage(
            String dataUrl,
            int bytes,
            int width,
            int height
    ) {
    }
}
