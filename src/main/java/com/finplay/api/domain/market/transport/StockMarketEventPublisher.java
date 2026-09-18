package com.finplay.api.domain.market.transport;

import com.finplay.api.domain.market.dto.sse.MarketPriceEvent;
import com.finplay.api.domain.market.dto.sse.MarketStatusEvent;
import com.finplay.api.domain.market.dto.transport.StockMarketTransportEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@Profile("prod & scheduler")
@RequiredArgsConstructor
public class StockMarketEventPublisher {

	public static final String CHANNEL = "market:stock:events:v1";

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	public void publishPrice(String eventId, MarketPriceEvent event) {
		publish(StockMarketTransportEvent.price(eventId, event));
	}

	public void publishStatus(String eventId, MarketStatusEvent event) {
		publish(StockMarketTransportEvent.status(eventId, event));
	}

	private void publish(StockMarketTransportEvent event) {
		try {
			redisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(event));
		} catch (RuntimeException ex) {
			log.warn("주식 시세 transport 발행 실패: type={}", event.eventType(), ex);
		}
	}
}
