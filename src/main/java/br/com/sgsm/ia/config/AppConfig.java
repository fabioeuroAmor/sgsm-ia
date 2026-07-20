package br.com.sgsm.ia.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, IaProperties.class, MilvusProperties.class})
public class AppConfig {}
