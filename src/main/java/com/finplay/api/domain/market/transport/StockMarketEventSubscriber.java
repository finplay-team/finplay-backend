package com.finplay.api.domain.market.transport;

import com.finplay.api.domain.market.dto.sse.MarketPriceEvent;
import com.finplay.api.domain.market.dto.sse.MarketStatusEvent;
import com.finplay.api.domain.market.dto.transport.StockMarketTransportEvent;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.sse.SseEmitterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@Profile("prod & web")
@RequiredArgsConstructor
public class StockMarketEventSubscriber implements MessageListener {

	private static final int SUPPORTED_VERSION = 1;
	private static final int MAX_SEEN_EVENT_IDS = 4096;
	private static final int MAX_TRACKED_SYMBOLS = 1024;

	private final ObjectMapper objectMapper;
	private final SseEmitterRegistry sseEmitterRegistry;
	private final Object orderingMonitor = new Object();
	private final Map<String, Boolean> seenEventIds = new LinkedHashMap<>(MAX_SEEN_EVENT_IDS, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
			return size() > MAX_SEEN_EVENT_IDS;
		}
	};
	private final Map<String, LocalDateTime> latestPriceSourceTimes = new LinkedHashMap<>(MAX_TRACKED_SYMBOLS, 0.75f,
		true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, LocalDateTime> eldest) {
			return size() > MAX_TRACKED_SYMBOLS;
		}
	};
	private LocalDateTime latestStatusEmittedAt;

	@Override
	public void onMessage(Message message, byte[] pattern) {
		try {
			if (message == null || message.getBody() == null || message.getBody().length == 0) {
				return;
			}
			StockMarketTransportEvent event = objectMapper.readValue(
				new String(message.getBody(), StandardCharsets.UTF_8), StockMarketTransportEvent.class);
			if (!isSupported(event)) {
				return;
			}
			if (!shouldForward(event)) {
				return;
			}
			if (event.eventType() == StockMarketTransportEvent.EventType.PRICE) {
				forwardPrice(event);
				return;
			}
			if (event.eventType() == StockMarketTransportEvent.EventType.STATUS) {
				forwardStatus(event);
			}
		} catch (RuntimeException ex) {
			log.warn("주식 시세 transport 수신 실패: type={}", ex.getClass().getSimpleName());
		}
	}

	private boolean shouldForward(StockMarketTransportEvent event) {
		synchronized (orderingMonitor) {
			if (seenEventIds.putIfAbsent(event.eventId(), Boolean.TRUE) != null) {
				return false;
			}
			if (event.eventType() == StockMarketTransportEvent.EventType.PRICE) {
				LocalDateTime latestSourceTime = latestPriceSourceTimes.get(event.symbol());
				if (latestSourceTime != null && !event.sourceTime().isAfter(latestSourceTime)) {
					return false;
				}
				latestPriceSourceTimes.put(event.symbol(), event.sourceTime());
				return true;
			}
			if (latestStatusEmittedAt != null && !event.emittedAt().isAfter(latestStatusEmittedAt)) {
				return false;
			}
			latestStatusEmittedAt = event.emittedAt();
			return true;
		}
	}

	private boolean isSupported(StockMarketTransportEvent event) {
		if (event == null || event.version() != SUPPORTED_VERSION || event.eventType() == null
			|| event.eventId() == null || event.eventId().isBlank() || event.market() != Market.STOCK
			|| event.emittedAt() == null) {
			return false;
		}
		if (event.eventType() == StockMarketTransportEvent.EventType.PRICE) {
			return event.symbol() != null && !event.symbol().isBlank() && event.price() != null
				&& event.sourceTime() != null && event.sourceTradingDate() != null && event.marketStatus() != null;
		}
		return event.eventType() == StockMarketTransportEvent.EventType.STATUS
			&& event.marketStatus() != null;
	}

	private void forwardPrice(StockMarketTransportEvent event) {
		MarketPriceEvent payload = new MarketPriceEvent(event.market(), event.symbol(), event.price(),
			event.sourceTime(),
			event.emittedAt(), event.sourceTradingDate(), event.marketStatus());
		sseEmitterRegistry.broadcast(Market.STOCK,
			SseEmitter.event().name("price").id(event.eventId()).data(payload));
	}

	private void forwardStatus(StockMarketTransportEvent event) {
		MarketStatusEvent payload = new MarketStatusEvent(event.market(), event.symbol(), event.marketStatus(),
			event.status(), event.reason(), event.emittedAt());
		sseEmitterRegistry.broadcast(Market.STOCK, SseEmitter.event().name("status").data(payload));
	}
}
