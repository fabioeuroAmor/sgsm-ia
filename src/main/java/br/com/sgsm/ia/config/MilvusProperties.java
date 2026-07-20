package br.com.sgsm.ia.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "milvus")
public record MilvusProperties(String host, int port, String collection, int embeddingDimension) {}
