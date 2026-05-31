package xin.students.imageaioriginal.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class ApiLoggingFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger("imageai.http");
    private static final int MAX_BODY_LOG_LENGTH = 2000;
    private static final int LARGE_BODY_BYTES = 8 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        long startNanos = System.nanoTime();
        boolean multipart = isMultipart(request);
        HttpServletRequest requestToUse = multipart ? request : new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper responseToUse = new ContentCachingResponseWrapper(response);
        LOG.info(
                "api.request id={} method={} uri={} query={} remote={} contentType={} payload={}",
                requestId,
                request.getMethod(),
                request.getRequestURI(),
                safeValue(request.getQueryString()),
                request.getRemoteAddr(),
                safeValue(request.getContentType()),
                requestPayloadSummary(request)
        );

        try {
            filterChain.doFilter(requestToUse, responseToUse);
        } finally {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            LOG.info(
                    "api.response id={} method={} uri={} status={} elapsedMs={} contentType={} requestBody={} responseBody={}",
                    requestId,
                    request.getMethod(),
                    request.getRequestURI(),
                    responseToUse.getStatus(),
                    elapsedMs,
                    safeValue(responseToUse.getContentType()),
                    multipart ? "[multipart skipped]" : bodySummary((ContentCachingRequestWrapper) requestToUse),
                    bodySummary(responseToUse)
            );
            responseToUse.copyBodyToResponse();
        }
    }

    private String requestPayloadSummary(HttpServletRequest request) {
        if (request instanceof MultipartHttpServletRequest multipartRequest) {
            String files = multipartRequest.getFileMap().values().stream()
                    .map(file -> "%s(%s,%dB)".formatted(
                            safeValue(file.getOriginalFilename()),
                            safeValue(file.getContentType()),
                            file.getSize()
                    ))
                    .collect(Collectors.joining(","));
            return "params=%s files=[%s]".formatted(parameterSummary(request), files);
        }
        return "params=" + parameterSummary(request);
    }

    private boolean isMultipart(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.toLowerCase().startsWith("multipart/");
    }

    private String bodySummary(ContentCachingRequestWrapper request) {
        byte[] body = request.getContentAsByteArray();
        if (body.length == 0) {
            return "-";
        }
        String sanitizedBody = sanitizeBody(new String(body, charset(request.getCharacterEncoding())));
        return largeBodySummary(body.length, sanitizedBody, "request");
    }

    private String bodySummary(ContentCachingResponseWrapper response) {
        byte[] body = response.getContentAsByteArray();
        if (body.length == 0) {
            return "-";
        }
        String sanitizedBody = sanitizeBody(new String(body, responseCharset(response)));
        return largeBodySummary(body.length, sanitizedBody, "response");
    }

    private Charset charset(String encoding) {
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (Exception ex) {
            return StandardCharsets.UTF_8;
        }
    }

    private Charset responseCharset(ContentCachingResponseWrapper response) {
        String contentType = response.getContentType();
        if (contentType != null) {
            String lowerContentType = contentType.toLowerCase();
            int charsetIndex = lowerContentType.indexOf("charset=");
            if (charsetIndex >= 0) {
                return charset(contentType.substring(charsetIndex + "charset=".length()).trim());
            }
            if (lowerContentType.contains("json") || lowerContentType.startsWith("text/")) {
                return StandardCharsets.UTF_8;
            }
        }
        return charset(response.getCharacterEncoding());
    }

    private String sanitizeBody(String value) {
        String sanitized = value.replaceAll(
                "(\"(?:thumbnail|preview|imageBase64|image_base64)\"\\s*:\\s*\")([^\"]*)(\")",
                "$1[image omitted]$3"
        );
        return sanitized.replaceAll(
                "data:image/[^;\"']+;base64,[A-Za-z0-9+/=\\r\\n]+",
                "data:image;base64,[omitted]"
        );
    }

    private String largeBodySummary(int byteLength, String sanitizedBody, String bodyType) {
        if (byteLength > LARGE_BODY_BYTES && sanitizedBody.length() > MAX_BODY_LOG_LENGTH) {
            return "[%s omitted: %dB, too large for log]".formatted(bodyType, byteLength);
        }
        return abbreviate(sanitizedBody, MAX_BODY_LOG_LENGTH);
    }

    private String parameterSummary(HttpServletRequest request) {
        return request.getParameterMap().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> summarizeValues(entry.getValue())
                ))
                .toString();
    }

    private String summarizeValues(String[] values) {
        if (values == null || values.length == 0) {
            return "";
        }
        return java.util.Arrays.stream(values)
                .map(value -> value == null ? "" : abbreviate(value, 500))
                .collect(Collectors.joining("|"));
    }

    private String abbreviate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private String safeValue(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
