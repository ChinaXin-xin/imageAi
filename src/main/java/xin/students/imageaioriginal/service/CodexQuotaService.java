package xin.students.imageaioriginal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import xin.students.imageaioriginal.config.CliProxyProperties;
import xin.students.imageaioriginal.model.CodexQuotaAccount;
import xin.students.imageaioriginal.model.QuotaWindowView;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CodexQuotaService {

    private static final String MANAGEMENT_SUFFIX = "/v0/management";
    private static final String CODEX_USAGE_URL = "https://chatgpt.com/backend-api/wham/usage";
    private static final int FIVE_HOUR_SECONDS = 18_000;
    private static final int WEEK_SECONDS = 604_800;
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CliProxyProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public CodexQuotaService(CliProxyProperties properties, RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public List<CodexQuotaAccount> getAccounts() {
        JsonNode authFiles = managementGet("/auth-files");
        JsonNode files = authFiles.path("files");
        List<CodexQuotaAccount> accounts = new ArrayList<>();

        if (!files.isArray()) {
            return accounts;
        }

        for (JsonNode file : files) {
            if (!isCodexFile(file)) {
                continue;
            }

            try {
                accounts.add(fetchCodexQuota(file));
            } catch (Exception ex) {
                accounts.add(buildErrorAccount(file, ex.getMessage()));
            }
        }

        accounts.sort(Comparator.comparing(CodexQuotaAccount::fileName));
        return accounts;
    }

    private CodexQuotaAccount fetchCodexQuota(JsonNode file) {
        String authIndex = textValue(file, "authIndex", "auth_index");
        if (authIndex == null) {
            throw new IllegalStateException("凭据缺少 auth_index，无法查询额度");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer $TOKEN$");
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", "codex_cli_rs/0.76.0 (Debian 13.0.0; x86_64) WindowsTerminal");

        String accountId = resolveAccountId(file);
        if (accountId != null) {
            headers.put("Chatgpt-Account-Id", accountId);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("authIndex", authIndex);
        payload.put("method", "GET");
        payload.put("url", CODEX_USAGE_URL);
        payload.put("header", headers);

        JsonNode response = managementPost("/api-call", payload);
        int statusCode = intValue(response, "status_code", "statusCode", 0);
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException(resolveApiCallMessage(response, statusCode));
        }

        JsonNode usagePayload = parseApiCallBody(response.path("body"));
        if (usagePayload == null || usagePayload.isMissingNode() || usagePayload.isNull()) {
            throw new IllegalStateException("Codex usage 响应为空");
        }

        JsonNode rateLimit = firstExisting(usagePayload, "rate_limit", "rateLimit");
        WindowPair pair = pickQuotaWindows(rateLimit);
        QuotaWindowView fiveHour = buildWindowView(pair.fiveHourWindow(), pair.limitReached(), pair.allowed());
        QuotaWindowView weekly = buildWindowView(pair.weeklyWindow(), pair.limitReached(), pair.allowed());

        String status = resolveStatus(file);
        return new CodexQuotaAccount(
                textValue(file, "name"),
                resolveAccountName(file),
                textValue(file, "name"),
                status,
                statusText(status),
                resolvePlanType(file, usagePayload),
                fiveHour,
                weekly,
                imageCount(fiveHour.remainingPercent(), 1),
                imageCount(weekly.remainingPercent(), 8),
                nowLabel(),
                null
        );
    }

    private JsonNode managementGet(String path) {
        return restClient.get()
                .uri(managementBaseUrl() + path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.managementKey())
                .retrieve()
                .body(JsonNode.class);
    }

    private JsonNode managementPost(String path, Object body) {
        return restClient.post()
                .uri(managementBaseUrl() + path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.managementKey())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    private String managementBaseUrl() {
        String baseUrl = properties.baseUrl() == null ? "" : properties.baseUrl().trim();
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException("未配置 image-ai.cli-proxy.base-url");
        }
        baseUrl = baseUrl.replaceAll("/+$", "");
        return baseUrl.endsWith(MANAGEMENT_SUFFIX) ? baseUrl : baseUrl + MANAGEMENT_SUFFIX;
    }

    private boolean isCodexFile(JsonNode file) {
        String provider = textValue(file, "provider", "type");
        if (provider == null) {
            return false;
        }
        return "codex".equals(provider.trim().toLowerCase().replace('_', '-'));
    }

    private String resolveStatus(JsonNode file) {
        if (booleanValue(file, "disabled")) {
            return "disabled";
        }
        if (booleanValue(file, "unavailable")) {
            return "unavailable";
        }
        return "active";
    }

    private String statusText(String status) {
        return switch (status) {
            case "disabled" -> "已禁用";
            case "unavailable" -> "不可用";
            case "error" -> "查询失败";
            default -> "正常";
        };
    }

    private CodexQuotaAccount buildErrorAccount(JsonNode file, String message) {
        return new CodexQuotaAccount(
                textValue(file, "name"),
                resolveAccountName(file),
                textValue(file, "name"),
                "error",
                statusText("error"),
                resolvePlanType(file, null),
                new QuotaWindowView(null, null, "-"),
                new QuotaWindowView(null, null, "-"),
                null,
                null,
                nowLabel(),
                message == null || message.isBlank() ? "查询失败" : message
        );
    }

    private String resolveAccountName(JsonNode file) {
        JsonNode metadata = objectValue(file.path("metadata"));
        JsonNode attributes = objectValue(file.path("attributes"));
        String[] candidates = {
                textValue(file, "account"),
                textValue(file, "email"),
                textValue(file, "username"),
                textValue(metadata, "account"),
                textValue(metadata, "email"),
                textValue(attributes, "account"),
                textValue(attributes, "email"),
                textValue(file, "name")
        };

        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "未知账号";
    }

    private String resolvePlanType(JsonNode file, JsonNode usagePayload) {
        JsonNode metadata = objectValue(file.path("metadata"));
        JsonNode attributes = objectValue(file.path("attributes"));
        JsonNode idToken = objectValue(file.path("id_token"));
        JsonNode metadataIdToken = objectValue(metadata.path("id_token"));

        String[] candidates = {
                textValue(usagePayload, "plan_type", "planType"),
                textValue(file, "plan_type", "planType"),
                textValue(idToken, "plan_type", "planType"),
                textValue(metadata, "plan_type", "planType"),
                textValue(metadataIdToken, "plan_type", "planType"),
                textValue(attributes, "plan_type", "planType")
        };

        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private String resolveAccountId(JsonNode file) {
        JsonNode metadata = objectValue(file.path("metadata"));
        JsonNode attributes = objectValue(file.path("attributes"));
        JsonNode[] candidates = {
                file.path("id_token"),
                metadata.path("id_token"),
                attributes.path("id_token")
        };

        for (JsonNode candidate : candidates) {
            String idToken = candidate.isTextual() ? candidate.asText() : null;
            String accountId = extractAccountIdFromJwt(idToken);
            if (accountId != null) {
                return accountId;
            }
        }
        return null;
    }

    private String extractAccountIdFromJwt(String jwt) {
        if (jwt == null || jwt.isBlank()) {
            return null;
        }
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = objectMapper.readTree(new String(decoded, StandardCharsets.UTF_8));
            return textValue(payload, "chatgpt_account_id", "chatgptAccountId");
        } catch (IllegalArgumentException | JsonProcessingException ex) {
            return null;
        }
    }

    private WindowPair pickQuotaWindows(JsonNode rateLimit) {
        JsonNode primary = firstExisting(rateLimit, "primary_window", "primaryWindow");
        JsonNode secondary = firstExisting(rateLimit, "secondary_window", "secondaryWindow");
        JsonNode fiveHourWindow = null;
        JsonNode weeklyWindow = null;

        for (JsonNode window : new JsonNode[]{primary, secondary}) {
            if (window == null || window.isMissingNode() || window.isNull()) {
                continue;
            }
            Integer seconds = integerValue(window, "limit_window_seconds", "limitWindowSeconds");
            if (Integer.valueOf(FIVE_HOUR_SECONDS).equals(seconds) && fiveHourWindow == null) {
                fiveHourWindow = window;
            }
            if (Integer.valueOf(WEEK_SECONDS).equals(seconds) && weeklyWindow == null) {
                weeklyWindow = window;
            }
        }

        if (fiveHourWindow == null && primary != null && primary != weeklyWindow) {
            fiveHourWindow = primary;
        }
        if (weeklyWindow == null && secondary != null && secondary != fiveHourWindow) {
            weeklyWindow = secondary;
        }

        boolean limitReached = booleanValue(rateLimit, "limit_reached", "limitReached");
        Boolean allowed = nullableBoolean(rateLimit, "allowed");
        return new WindowPair(fiveHourWindow, weeklyWindow, limitReached, allowed);
    }

    private QuotaWindowView buildWindowView(JsonNode window, boolean limitReached, Boolean allowed) {
        if (window == null || window.isMissingNode() || window.isNull()) {
            return new QuotaWindowView(null, null, "-");
        }

        Integer usedPercent = integerValue(window, "used_percent", "usedPercent");
        if (usedPercent == null && (limitReached || Boolean.FALSE.equals(allowed))) {
            usedPercent = 100;
        }
        Integer normalizedUsed = usedPercent == null ? null : clampPercent(usedPercent);
        Integer remainingPercent = normalizedUsed == null ? null : clampPercent(100 - normalizedUsed);

        return new QuotaWindowView(
                remainingPercent,
                normalizedUsed,
                formatResetLabel(window)
        );
    }

    private String formatResetLabel(JsonNode window) {
        JsonNode resetAt = firstExisting(window, "reset_at", "resetAt");
        Integer resetAfterSeconds = integerValue(window, "reset_after_seconds", "resetAfterSeconds");

        if (resetAt != null && resetAt.isTextual() && !resetAt.asText().isBlank()) {
            try {
                return formatInstant(Instant.parse(resetAt.asText()));
            } catch (Exception ignored) {
                return resetAt.asText();
            }
        }
        if (resetAt != null && resetAt.isNumber()) {
            long raw = resetAt.asLong();
            long epochMillis = raw < 1_000_000_000_000L ? raw * 1000 : raw;
            return formatInstant(Instant.ofEpochMilli(epochMillis));
        }
        if (resetAfterSeconds != null) {
            return formatInstant(Instant.now().plusSeconds(resetAfterSeconds));
        }
        return "-";
    }

    private String resolveApiCallMessage(JsonNode response, int statusCode) {
        JsonNode body = parseApiCallBody(response.path("body"));
        String message = textValue(body, "message");
        if (message == null && body != null) {
            JsonNode error = body.path("error");
            message = error.isTextual() ? error.asText() : textValue(error, "message");
        }
        if (message == null) {
            message = response.path("body").isTextual() ? response.path("body").asText() : null;
        }
        return message == null || message.isBlank() ? "HTTP " + statusCode : statusCode + " " + message;
    }

    private JsonNode parseApiCallBody(JsonNode bodyNode) {
        if (bodyNode == null || bodyNode.isMissingNode() || bodyNode.isNull()) {
            return null;
        }
        if (!bodyNode.isTextual()) {
            return bodyNode;
        }
        String text = bodyNode.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (JsonProcessingException ex) {
            return objectMapper.getNodeFactory().textNode(text);
        }
    }

    private JsonNode objectValue(JsonNode node) {
        return node != null && node.isObject() ? node : objectMapper.createObjectNode();
    }

    private JsonNode firstExisting(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private String textValue(JsonNode node, String... names) {
        JsonNode value = firstExisting(node, names);
        if (value == null) {
            return null;
        }
        if (value.isTextual()) {
            String text = value.asText().trim();
            return text.isEmpty() ? null : text;
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        return null;
    }

    private int intValue(JsonNode node, String firstName, String secondName, int fallback) {
        Integer value = integerValue(node, firstName, secondName);
        return value == null ? fallback : value;
    }

    private Integer integerValue(JsonNode node, String... names) {
        JsonNode value = firstExisting(node, names);
        if (value == null) {
            return null;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        if (value.isTextual()) {
            try {
                return (int) Math.round(Double.parseDouble(value.asText().trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean booleanValue(JsonNode node, String... names) {
        Boolean value = nullableBoolean(node, names);
        return Boolean.TRUE.equals(value);
    }

    private Boolean nullableBoolean(JsonNode node, String... names) {
        JsonNode value = firstExisting(node, names);
        if (value == null) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            return value.asInt() != 0;
        }
        if (value.isTextual()) {
            return "true".equalsIgnoreCase(value.asText().trim());
        }
        return null;
    }

    private Integer clampPercent(Integer value) {
        return Math.max(0, Math.min(100, value));
    }

    private Integer imageCount(Integer percent, int ratio) {
        return percent == null ? null : percent * ratio;
    }

    private String nowLabel() {
        return LocalDateTime.now().format(DISPLAY_TIME);
    }

    private String formatInstant(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(DISPLAY_TIME);
    }

    private record WindowPair(
            JsonNode fiveHourWindow,
            JsonNode weeklyWindow,
            boolean limitReached,
            Boolean allowed
    ) {
    }
}
