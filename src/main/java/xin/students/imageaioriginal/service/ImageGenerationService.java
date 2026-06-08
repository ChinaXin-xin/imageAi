package xin.students.imageaioriginal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import xin.students.imageaioriginal.config.GptProperties;
import xin.students.imageaioriginal.config.ImageGenerationProperties;
import xin.students.imageaioriginal.model.StoredUploadImage;

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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ImageGenerationService {

    private static final Logger LOG = LoggerFactory.getLogger("imageai.gpt");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration HARD_TIMEOUT = Duration.ofMinutes(15);
    private static final int REFERENCE_MAX_EDGE = 1024;
    private static final float REFERENCE_JPEG_QUALITY = 0.78f;
    private static final AtomicInteger REQUEST_THREAD_INDEX = new AtomicInteger();

    private final GptProperties gptProperties;
    private final ImageGenerationProperties imageGenerationProperties;
    private final UploadImageAnalysisService uploadImageAnalysisService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService requestExecutor = Executors.newCachedThreadPool(imageRequestThreadFactory());
    private final Map<String, Map<String, Future<?>>> runningRequests = new ConcurrentHashMap<>();

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
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    public void shutdown() {
        requestExecutor.shutdownNow();
    }

    public void cancelTask(String taskId) {
        Map<String, Future<?>> taskRequests = runningRequests.remove(taskId);
        if (taskRequests == null || taskRequests.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Future<?>> entry : taskRequests.entrySet()) {
            Future<?> future = entry.getValue();
            if (future != null) {
                LOG.info("取消生图请求：任务ID={}，请求键={}", taskId, entry.getKey());
                future.cancel(true);
            }
        }
    }

    public GeneratedImage generate(
            String taskId,
            String resultType,
            int itemIndex,
            String prompt,
            int width,
            int height,
            List<StoredUploadImage> referenceImages
    ) {
        return generateInternal(taskId, resultType, itemIndex, prompt, width, height, referenceImages, false);
    }

    public GeneratedImage generateWithPreparedReferences(
            String taskId,
            String resultType,
            int itemIndex,
            String prompt,
            int width,
            int height,
            List<StoredUploadImage> referenceImages
    ) {
        return generateInternal(taskId, resultType, itemIndex, prompt, width, height, referenceImages, true);
    }

    public List<StoredUploadImage> prepareReferenceImagesForGeneration(List<StoredUploadImage> referenceImages) {
        return prepareReferenceImages(referenceImages);
    }

    private GeneratedImage generateInternal(
            String taskId,
            String resultType,
            int itemIndex,
            String prompt,
            int width,
            int height,
            List<StoredUploadImage> referenceImages,
            boolean referenceImagesPrepared
    ) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        String model = imageGenerationProperties.resolvedModel();
        String size = Math.max(1, width) + "x" + Math.max(1, height);
        List<StoredUploadImage> preparedReferenceImages = referenceImagesPrepared
                ? usableReferenceImages(referenceImages)
                : prepareReferenceImages(referenceImages);
        int referenceCount = preparedReferenceImages.size();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("prompt", prompt);
        request.put("n", 1);
        request.put("size", size);

        LOG.info(
                "生图请求开始：请求ID={}，任务ID={}，图片类型={}，序号={}，模型={}，尺寸={}，参考图数量={}，参考图字节数={}，参考图文件名={}，提示词字符数={}，提示词={}",
                requestId,
                taskId,
                resultType,
                itemIndex,
                model,
                size,
                referenceCount,
                preparedReferenceImages.stream().mapToLong(image -> image.bytes() == null ? 0 : image.bytes().length).sum(),
                preparedReferenceImages.stream().map(StoredUploadImage::fileName).toList(),
                prompt == null ? 0 : prompt.length(),
                prompt
        );

        JsonNode response = requestWithHardTimeout(
                () -> referenceCount > 0
                        ? generateWithReferences(preparedReferenceImages, model, prompt, size)
                        : generateFromText(request),
                requestId,
                taskId,
                resultType,
                itemIndex
        );

        JsonNode first = response == null ? null : response.path("data").path(0);
        String imageUrl = text(first, "url");
        String b64Json = text(first, "b64_json", "b64Json");
        String providerRevisedPrompt = text(first, "revised_prompt", "revisedPrompt");
        String rawResponse = sanitizedRawResponse(response);
        if ((imageUrl == null || imageUrl.isBlank()) && (b64Json == null || b64Json.isBlank())) {
            throw new IllegalStateException("生图接口未返回图片地址或图片 base64：" + abbreviate(rawResponse, 1000));
        }

        LOG.info(
                "生图接口响应：请求ID={}，任务ID={}，图片类型={}，序号={}，模型={}，图片地址={}，base64字符数={}，接口修订提示词={}",
                requestId,
                taskId,
                resultType,
                itemIndex,
                model,
                imageUrl == null ? "-" : imageUrl,
                b64Json == null ? 0 : b64Json.length(),
                providerRevisedPrompt == null ? "-" : providerRevisedPrompt
        );
        return new GeneratedImage(imageUrl, b64Json, null, rawResponse);
    }

    private JsonNode requestWithHardTimeout(
            ImageRequest request,
            String requestId,
            String taskId,
            String resultType,
            int itemIndex
    ) {
        Future<JsonNode> future = requestExecutor.submit(request::execute);
        String requestKey = resultType + "#" + itemIndex + "/" + requestId;
        runningRequests.computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>()).put(requestKey, future);
        try {
            return future.get(HARD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            LOG.warn(
                    "生图请求超时：请求ID={}，任务ID={}，图片类型={}，序号={}，超时秒数={}",
                    requestId,
                    taskId,
                    resultType,
                    itemIndex,
                    HARD_TIMEOUT.toSeconds()
            );
            throw new IllegalStateException("生图接口超过 " + HARD_TIMEOUT.toMinutes() + " 分钟仍未返回，已自动终止本次生成，请稍后重试。", ex);
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("生图请求被中断，请稍后重试。", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("生图请求失败：" + cause.getMessage(), cause);
        } finally {
            Map<String, Future<?>> taskRequests = runningRequests.get(taskId);
            if (taskRequests != null) {
                taskRequests.remove(requestKey, future);
                if (taskRequests.isEmpty()) {
                    runningRequests.remove(taskId, taskRequests);
                }
            }
        }
    }

    private ThreadFactory imageRequestThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "image-generation-request-" + REQUEST_THREAD_INDEX.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
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

    private String imageEditsUrl() {
        String baseUrl = gptProperties.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = gptProperties.resolvedBaseUrl();
        }
        baseUrl = baseUrl.trim().replaceAll("/+$", "");
        if (baseUrl.endsWith("/v1")) {
            return baseUrl + "/images/edits";
        }
        return baseUrl + "/v1/images/edits";
    }

    private JsonNode generateFromText(Map<String, Object> request) {
        return restClient.post()
                .uri(imageGenerationUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + uploadImageAnalysisService.resolveApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange((httpRequest, response) -> readImageResponse(response.getStatusCode().value(), response.getHeaders().getContentType(), response.getBody().readAllBytes()));
    }

    private JsonNode generateWithReferences(List<StoredUploadImage> referenceImages, String model, String prompt, String size) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("model", model);
        builder.part("prompt", prompt);
        builder.part("n", "1");
        builder.part("size", size);
        for (StoredUploadImage image : referenceImages) {
            if (image == null || image.bytes() == null || image.bytes().length == 0) {
                continue;
            }
            builder.part("image", imageResource(image))
                    .filename(safeFileName(image.fileName()))
                    .contentType(safeMediaType(image.contentType()));
        }
        MultiValueMap<String, org.springframework.http.HttpEntity<?>> body = builder.build();
        return restClient.post()
                .uri(imageEditsUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + uploadImageAnalysisService.resolveApiKey())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .exchange((httpRequest, response) -> readImageResponse(response.getStatusCode().value(), response.getHeaders().getContentType(), response.getBody().readAllBytes()));
    }

    private JsonNode readImageResponse(int statusCode, MediaType contentType, byte[] body) throws IOException {
        byte[] payload = body == null ? new byte[0] : body;
        if (statusCode >= 400) {
            throw new IllegalStateException("生图接口返回 HTTP " + statusCode + "：" + responseSnippet(payload));
        }
        if (payload.length == 0) {
            throw new IllegalStateException("生图接口返回空响应");
        }
        try {
            return objectMapper.readTree(payload);
        } catch (Exception parseError) {
            if (isImageContentType(contentType) || isOctetStream(contentType)) {
                LOG.info("生图接口返回二进制图片：内容类型={}，字节数={}", contentType, payload.length);
                return binaryImageResponse(payload);
            }
            throw new IllegalStateException("生图接口返回内容不是 JSON：" + responseSnippet(payload), parseError);
        }
    }

    private boolean isImageContentType(MediaType contentType) {
        return contentType != null && "image".equalsIgnoreCase(contentType.getType());
    }

    private boolean isOctetStream(MediaType contentType) {
        return contentType != null && MediaType.APPLICATION_OCTET_STREAM.includes(contentType);
    }

    private JsonNode binaryImageResponse(byte[] imageBytes) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode first = root.putArray("data").addObject();
        first.put("b64_json", Base64.getEncoder().encodeToString(imageBytes));
        return root;
    }

    private String responseSnippet(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8).trim();
        return abbreviate(text, 1000);
    }

    private List<StoredUploadImage> prepareReferenceImages(List<StoredUploadImage> referenceImages) {
        List<StoredUploadImage> usableImages = usableReferenceImages(referenceImages);
        if (usableImages.isEmpty()) {
            return List.of();
        }
        List<StoredUploadImage> prepared = new ArrayList<>(usableImages.size());
        for (StoredUploadImage image : usableImages) {
            prepared.add(compressReferenceImage(image));
        }
        return prepared;
    }

    private List<StoredUploadImage> usableReferenceImages(List<StoredUploadImage> referenceImages) {
        if (referenceImages == null || referenceImages.isEmpty()) {
            return List.of();
        }
        return referenceImages.stream()
                .filter(image -> image != null && image.bytes() != null && image.bytes().length > 0)
                .toList();
    }

    private StoredUploadImage compressReferenceImage(StoredUploadImage image) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(image.bytes()));
            if (source == null) {
                return image;
            }
            int width = source.getWidth();
            int height = source.getHeight();
            double scale = Math.min(1.0, REFERENCE_MAX_EDGE / (double) Math.max(width, height));
            int targetWidth = Math.max(1, (int) Math.round(width * scale));
            int targetHeight = Math.max(1, (int) Math.round(height * scale));
            BufferedImage rgbImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rgbImage.createGraphics();
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
            byte[] compressed = writeJpeg(rgbImage);
            if (compressed.length >= image.bytes().length && width <= REFERENCE_MAX_EDGE && height <= REFERENCE_MAX_EDGE) {
                return image;
            }
            return new StoredUploadImage(jpegFileName(image.fileName()), MediaType.IMAGE_JPEG_VALUE, compressed);
        } catch (IOException | RuntimeException ex) {
            LOG.warn("参考图压缩失败：文件名={}，字节数={}，错误={}",
                    image.fileName(),
                    image.bytes().length,
                    ex.getMessage()
            );
            return image;
        }
    }

    private byte[] writeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            ByteArrayOutputStream fallback = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", fallback);
            return fallback.toByteArray();
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(REFERENCE_JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), param);
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private String jpegFileName(String fileName) {
        String normalized = fileName == null || fileName.isBlank() ? "reference-image" : fileName.trim();
        return normalized.replaceFirst("(?i)\\.(png|jpe?g|webp|bmp|gif|heic|heif)$", "") + ".jpg";
    }

    private ByteArrayResource imageResource(StoredUploadImage image) {
        return new ByteArrayResource(image.bytes()) {
            @Override
            public String getFilename() {
                return safeFileName(image.fileName());
            }
        };
    }

    private MediaType safeMediaType(String contentType) {
        try {
            return contentType == null || contentType.isBlank()
                    ? MediaType.IMAGE_JPEG
                    : MediaType.parseMediaType(contentType);
        } catch (Exception ex) {
            return MediaType.IMAGE_JPEG;
        }
    }

    private String safeFileName(String fileName) {
        String normalized = fileName == null || fileName.isBlank() ? "reference-image.jpg" : fileName.trim();
        return normalized.replaceAll("[\\\\/:*?\"<>|]", "_");
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

    private String sanitizedRawResponse(JsonNode response) {
        if (response == null) {
            return "";
        }
        JsonNode sanitized = response.deepCopy();
        JsonNode data = sanitized.path("data");
        if (data.isArray()) {
            for (JsonNode item : data) {
                if (item instanceof ObjectNode objectNode && objectNode.has("b64_json")) {
                    objectNode.put("b64_json", "[image saved to local file]");
                }
            }
        }
        return toJson(sanitized);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    @FunctionalInterface
    private interface ImageRequest {
        JsonNode execute();
    }

    public record GeneratedImage(
            String imageUrl,
            String imageBase64,
            String revisedPrompt,
            String rawResponse
    ) {
    }
}
