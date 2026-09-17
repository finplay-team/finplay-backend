package com.finplay.api.domain.market.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod | (prod & scheduler)")
@RequiredArgsConstructor
public class StockPriceScheduler {

	private final StockPriceStreamService stockPriceStreamService;

	@Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
	public void publishScheduledUpdates() {
		stockPriceStreamService.publishScheduledUpdates();
	}
}
