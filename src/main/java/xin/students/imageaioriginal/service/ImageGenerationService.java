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

import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration HARD_TIMEOUT = Duration.ofMinutes(5);
    private static final AtomicInteger REQUEST_THREAD_INDEX = new AtomicInteger();

    private final GptProperties gptProperties;
    private final ImageGenerationProperties imageGenerationProperties;
    private final UploadImageAnalysisService uploadImageAnalysisService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService requestExecutor = Executors.newCachedThreadPool(imageRequestThreadFactory());

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
        int referenceCount = referenceImages == null ? 0 : referenceImages.size();

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
                        ? generateWithReferences(referenceImages, model, prompt, size)
                        : generateFromText(request),
                requestId,
                taskId,
                resultType,
                itemIndex
        );

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

    private JsonNode requestWithHardTimeout(
            ImageRequest request,
            String requestId,
            String taskId,
            String resultType,
            int itemIndex
    ) {
        Future<JsonNode> future = requestExecutor.submit(request::execute);
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
