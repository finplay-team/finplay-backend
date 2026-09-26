package com.finplay.api.domain.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Profile;

@ConfigurationProperties(prefix = "kis")
@Profile("!prod | (prod & scheduler)")
public record KisProperties(
	String baseUrl,
	String appKey,
	String appSecret,
	@DefaultValue("0")
	long requestIntervalMs) {
}
