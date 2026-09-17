package com.finplay.api.domain.market.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.market.dto.sse.MarketPriceEvent;
import com.finplay.api.domain.market.dto.sse.MarketStatusEvent;
import com.finplay.api.domain.market.dto.transport.StockMarketTransportEvent;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.PriceStatus;
import com.finplay.api.domain.market.service.StockMarketStatus;
import com.finplay.api.domain.market.sse.SseEmitterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitterTestHandler;
import tools.jackson.databind.ObjectMapper;

class StockMarketEventSubscriberTest {

	private static final LocalDateTime SOURCE_TIME = LocalDateTime.of(2026, 8, 10, 9, 0);
	private static final LocalDateTime EMITTED_AT = LocalDateTime.of(2026, 8, 10, 9, 1);
	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 8, 10);

	@Test
	void independentWebSubscribersEachForwardTheSamePriceEventToLocalSse() throws Exception {
		SseEmitterRegistry firstRegistry = new SseEmitterRegistry();
		SseEmitterRegistry secondRegistry = new SseEmitterRegistry();
		SseEmitter firstEmitter = firstRegistry.register(Market.STOCK);
		SseEmitter secondEmitter = secondRegistry.register(Market.STOCK);
		SseEmitterTestHandler firstHandler = new SseEmitterTestHandler();
		SseEmitterTestHandler secondHandler = new SseEmitterTestHandler();
		firstHandler.attachTo(firstEmitter);
		secondHandler.attachTo(secondEmitter);
		String message = message(StockMarketTransportEvent.price("STOCK:TEST:202608100900", priceEvent()));

		new StockMarketEventSubscriber(new ObjectMapper(), firstRegistry).onMessage(new DefaultMessage(
			"channel".getBytes(), message.getBytes()), null);
		new StockMarketEventSubscriber(new ObjectMapper(), secondRegistry).onMessage(new DefaultMessage(
			"channel".getBytes(), message.getBytes()), null);

		assertThat(sentText(firstHandler)).contains("event:price", "id:STOCK:TEST:202608100900");
		assertThat(sentText(secondHandler)).contains("event:price", "id:STOCK:TEST:202608100900");
	}

	@Test
	void statusEventKeepsExistingSseEventNameWithoutAddingAnId() throws Exception {
		SseEmitterRegistry registry = new SseEmitterRegistry();
		SseEmitter emitter = registry.register(Market.STOCK);
		SseEmitterTestHandler handler = new SseEmitterTestHandler();
		handler.attachTo(emitter);
		MarketStatusEvent status = new MarketStatusEvent(Market.STOCK, null, StockMarketStatus.CLOSED,
			PriceStatus.UNAVAILABLE, "market closed", EMITTED_AT);
		String message = message(StockMarketTransportEvent.status("STOCK:STATUS:CLOSED:2026-08-10T09:01", status));

		new StockMarketEventSubscriber(new ObjectMapper(), registry).onMessage(new DefaultMessage(
			"channel".getBytes(), message.getBytes()), null);

		String sent = sentText(handler);
		assertThat(sent).contains("event:status");
		assertThat(sent).doesNotContain("id:STOCK:STATUS");
	}

	@Test
	void malformedMessageIsIgnoredWithoutStoppingSubscriber() {
		SseEmitterRegistry registry = new SseEmitterRegistry();

		new StockMarketEventSubscriber(new ObjectMapper(), registry).onMessage(new DefaultMessage(
			"channel".getBytes(), "not-json".getBytes()), null);

		assertThat(registry.getEmitters(Market.STOCK)).isEmpty();
	}

	@Test
	void failedEmitterDoesNotPreventHealthyEmitterFromReceivingEvent() throws Exception {
		SseEmitterRegistry registry = new SseEmitterRegistry();
		SseEmitter failingEmitter = registry.register(Market.STOCK);
		SseEmitter healthyEmitter = registry.register(Market.STOCK);
		SseEmitterTestHandler failingHandler = new SseEmitterTestHandler();
		SseEmitterTestHandler healthyHandler = new SseEmitterTestHandler();
		failingHandler.attachTo(failingEmitter);
		healthyHandler.attachTo(healthyEmitter);
		failingHandler.failOnNextSend();

		new StockMarketEventSubscriber(new ObjectMapper(), registry).onMessage(new DefaultMessage(
			"channel".getBytes(),
			message(StockMarketTransportEvent.price("STOCK:TEST:202608100900", priceEvent())).getBytes()), null);

		assertThat(registry.getEmitters(Market.STOCK)).containsExactly(healthyEmitter);
		assertThat(sentText(healthyHandler)).contains("event:price");
	}

	private static MarketPriceEvent priceEvent() {
		return new MarketPriceEvent(Market.STOCK, "TEST", new BigDecimal("71000"), SOURCE_TIME, EMITTED_AT,
			TRADING_DATE, StockMarketStatus.OPEN);
	}

	private static String message(StockMarketTransportEvent event) throws Exception {
		return new ObjectMapper().writeValueAsString(event);
	}

	private static String sentText(SseEmitterTestHandler handler) {
		return handler.getSentEvents().stream()
			.map(event -> event instanceof ResponseBodyEmitter.DataWithMediaType data ? data.getData() : event)
			.map(String::valueOf)
			.collect(Collectors.joining());
	}
}
