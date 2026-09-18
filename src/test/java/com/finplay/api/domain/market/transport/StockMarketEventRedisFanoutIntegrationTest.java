package com.finplay.api.domain.market.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.market.dto.sse.MarketPriceEvent;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.StockMarketStatus;
import com.finplay.api.domain.market.sse.SseEmitterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitterTestHandler;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StockMarketEventRedisFanoutIntegrationTest {

	private static final String EVENT_ID = "STOCK:TEST:202608100900";
	private static final LocalDateTime SOURCE_TIME = LocalDateTime.of(2026, 8, 10, 9, 0);
	private static final LocalDateTime EMITTED_AT = LocalDateTime.of(2026, 8, 10, 9, 1);
	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 8, 10);

	@Autowired
	private RedisConnectionFactory redisConnectionFactory;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	private final List<RedisMessageListenerContainer> listenerContainers = new ArrayList<>();

	@AfterEach
	void stopListenerContainers() throws Exception {
		for (RedisMessageListenerContainer container : listenerContainers) {
			container.stop();
			container.destroy();
		}
		listenerContainers.clear();
	}

	@Test
	void publishedPriceEventFansOutThroughRedisToTwoIndependentWebSubscribers() throws IOException {
		SseEmitterRegistry firstRegistry = new SseEmitterRegistry();
		SseEmitterRegistry secondRegistry = new SseEmitterRegistry();
		SseEmitterTestHandler firstHandler = attachHandler(firstRegistry);
		SseEmitterTestHandler secondHandler = attachHandler(secondRegistry);
		StockMarketEventSubscriber firstSubscriber = new StockMarketEventSubscriber(objectMapper, firstRegistry);
		StockMarketEventSubscriber secondSubscriber = new StockMarketEventSubscriber(objectMapper, secondRegistry);

		startListenerContainer(firstSubscriber);
		startListenerContainer(secondSubscriber);

		StockMarketEventPublisher publisher = new StockMarketEventPublisher(redisTemplate, objectMapper);
		publisher.publishPrice(EVENT_ID, priceEvent());

		awaitUntil(() -> containsPriceEvent(firstHandler) && containsPriceEvent(secondHandler));

		assertThat(sentText(firstHandler)).contains("event:price", "id:" + EVENT_ID);
		assertThat(sentText(secondHandler)).contains("event:price", "id:" + EVENT_ID);
		assertThat(countOccurrences(sentText(firstHandler), "event:price")).isEqualTo(1);
		assertThat(countOccurrences(sentText(secondHandler), "event:price")).isEqualTo(1);
	}

	private void startListenerContainer(StockMarketEventSubscriber subscriber) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(redisConnectionFactory);
		container.addMessageListener(subscriber, new ChannelTopic(StockMarketEventPublisher.CHANNEL));
		container.afterPropertiesSet();
		listenerContainers.add(container);
		container.start();
		awaitUntil(() -> container.isRunning() && container.isListening());
	}

	private static SseEmitterTestHandler attachHandler(SseEmitterRegistry registry) throws IOException {
		SseEmitter emitter = registry.register(Market.STOCK);
		SseEmitterTestHandler handler = new SseEmitterTestHandler();
		handler.attachTo(emitter);
		return handler;
	}

	private static MarketPriceEvent priceEvent() {
		return new MarketPriceEvent(Market.STOCK, "TEST", new BigDecimal("71000"), SOURCE_TIME, EMITTED_AT,
			TRADING_DATE, StockMarketStatus.OPEN);
	}

	private static boolean containsPriceEvent(SseEmitterTestHandler handler) {
		return sentText(handler).contains("event:price");
	}

	private static String sentText(SseEmitterTestHandler handler) {
		return handler.getSentEvents().stream()
			.map(event -> event instanceof ResponseBodyEmitter.DataWithMediaType data ? data.getData() : event)
			.map(String::valueOf)
			.reduce(String::concat)
			.orElse("");
	}

	private static int countOccurrences(String value, String token) {
		return (value.length() - value.replace(token, "").length()) / token.length();
	}

	private static void awaitUntil(BooleanSupplier condition) {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() >= deadline) {
				throw new AssertionError("조건이 제한 시간 안에 충족되지 않았다");
			}
			try {
				Thread.sleep(20);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new AssertionError("대기 중 인터럽트가 발생했다", ex);
			}
		}
	}
}
