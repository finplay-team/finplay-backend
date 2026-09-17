package com.finplay.api.domain.order.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!prod | (prod & scheduler)")
@EnableConfigurationProperties(LimitOrderFillExecutorProperties.class)
public class LimitOrderFillExecutorConfig {}
