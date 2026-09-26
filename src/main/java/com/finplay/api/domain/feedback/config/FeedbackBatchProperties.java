package com.finplay.api.domain.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "feedback.batch")
public record FeedbackBatchProperties(
	@DefaultValue("0 45 8 * * MON-FRI")
	String cron,
	@DefaultValue("0 5 * * * *")
	String cryptoCron,
	@DefaultValue("0 32 15 * * MON-FRI")
	String peerStatsCron,
	@DefaultValue("30 * * * * *")
	String cryptoWatchCron,
	@DefaultValue("0 5 0 * * *")
	String cryptoPeerStatsCron,
	@DefaultValue("3600")
	int lockTtlSeconds) {

	public FeedbackBatchProperties {
		if (lockTtlSeconds < 1) {
			throw new IllegalArgumentException("feedback.batch.lock-ttl-seconds는 1 이상이어야 합니다.");
		}
	}
}
