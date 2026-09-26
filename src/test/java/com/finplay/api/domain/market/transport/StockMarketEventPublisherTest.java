package com.finplay.api.domain.market.transport;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.market.dto.sse.MarketPriceEvent;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.StockMarketStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

class StockMarketEventPublisherTest {

	@Test
	void publishesStockTransportEventToDedicatedChannel() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		StockMarketEventPublisher publisher = new StockMarketEventPublisher(redisTemplate, new ObjectMapper());
		MarketPriceEvent event = new MarketPriceEvent(Market.STOCK, "TEST", new BigDecimal("71000"),
			LocalDateTime.of(2026, 8, 10, 9, 0), LocalDateTime.of(2026, 8, 10, 9, 1),
			LocalDate.of(2026, 8, 10), StockMarketStatus.OPEN);

		publisher.publishPrice("STOCK:TEST:202608100900", event);

		verify(redisTemplate).convertAndSend(eq(StockMarketEventPublisher.CHANNEL), anyString());
	}

	@Test
	void redisPublishFailureDoesNotPropagateToPriceProcessing() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		when(redisTemplate.convertAndSend(anyString(), anyString())).thenThrow(new IllegalStateException("redis down"));
		StockMarketEventPublisher publisher = new StockMarketEventPublisher(redisTemplate, new ObjectMapper());
		MarketPriceEvent event = new MarketPriceEvent(Market.STOCK, "TEST", new BigDecimal("71000"),
			LocalDateTime.of(2026, 8, 10, 9, 0), LocalDateTime.of(2026, 8, 10, 9, 1),
			LocalDate.of(2026, 8, 10), StockMarketStatus.OPEN);

		assertThatCode(() -> publisher.publishPrice("STOCK:TEST:202608100900", event)).doesNotThrowAnyException();
	}
}
