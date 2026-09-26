package com.finplay.api.domain.feedback.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!prod | (prod & scheduler)")
@EnableConfigurationProperties({
	NaverSearchProperties.class,
	DartProperties.class
})
public class NewsApiPropertiesConfig {}
