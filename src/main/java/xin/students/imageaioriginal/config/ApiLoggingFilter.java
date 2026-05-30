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
        return abbreviate(new String(body, charset(request.getCharacterEncoding())), 2000);
    }

    private String bodySummary(ContentCachingResponseWrapper response) {
        byte[] body = response.getContentAsByteArray();
        if (body.length == 0) {
            return "-";
        }
        return abbreviate(new String(body, charset(response.getCharacterEncoding())), 2000);
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
