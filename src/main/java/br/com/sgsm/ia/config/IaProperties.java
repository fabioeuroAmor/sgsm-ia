package br.com.sgsm.ia.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ia")
public record IaProperties(
        String provider,
        int topK,
        RedisStreamProperties redis
) {
    public record RedisStreamProperties(String streamKey, String group, String consumer) {}
}
