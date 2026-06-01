package xin.students.imageaioriginal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Duration;
import java.util.ArrayList;
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
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration HARD_TIMEOUT = Duration.ofMinutes(10);
    private static final int REFERENCE_MAX_EDGE = 1280;
    private static final float REFERENCE_JPEG_QUALITY = 0.86f;
    private static final AtomicInteger REQUEST_THREAD_INDEX = new AtomicInteger();

    private final GptProperties gptProperties;
    private final ImageGenerationProperties imageGenerationProperties;
    private final UploadImageAnalysisService uploadImageAnalysisService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService requestExecutor = Executors.newCachedThreadPool(imageRequestThreadFactory());
    private final Map<String, Future<?>> runningRequests = new ConcurrentHashMap<>();

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
        Future<?> future = runningRequests.remove(taskId);
        if (future != null) {
            future.cancel(true);
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
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        String model = imageGenerationProperties.resolvedModel();
        String size = Math.max(1, width) + "x" + Math.max(1, height);
        List<StoredUploadImage> preparedReferenceImages = prepareReferenceImages(referenceImages);
        int referenceCount = preparedReferenceImages.size();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("prompt", prompt);
        request.put("n", 1);
        request.put("size", size);

        LOG.info(
                "gpt.image.start id={} taskId={} type={} index={} model={} size={} references={} prompt={}",
                requestId,
                taskId,
                resultType,
                itemIndex,
                model,
                size,
                referenceCount,
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
        runningRequests.put(taskId, future);
        try {
            return future.get(HARD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            LOG.warn(
                    "gpt.image.timeout id={} taskId={} type={} index={} timeoutSeconds={}",
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
            runningRequests.remove(taskId, future);
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
                .retrieve()
                .body(JsonNode.class);
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
                .retrieve()
                .body(JsonNode.class);
    }

    private List<StoredUploadImage> prepareReferenceImages(List<StoredUploadImage> referenceImages) {
        if (referenceImages == null || referenceImages.isEmpty()) {
            return List.of();
        }
        List<StoredUploadImage> prepared = new ArrayList<>(referenceImages.size());
        for (StoredUploadImage image : referenceImages) {
            if (image == null || image.bytes() == null || image.bytes().length == 0) {
                continue;
            }
            prepared.add(compressReferenceImage(image));
        }
        return prepared;
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
            LOG.warn("gpt.image.reference.compress.failed fileName={} bytes={} message={}",
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
