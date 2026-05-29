package xin.students.imageaioriginal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "image-ai.cli-proxy")
public record CliProxyProperties(
        String baseUrl,
        String managementKey
) {
}
