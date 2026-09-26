package com.finplay.api.domain.market.dto.transport;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finplay.api.domain.market.dto.sse.MarketPriceEvent;
import com.finplay.api.domain.market.dto.sse.MarketStatusEvent;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.PriceStatus;
import com.finplay.api.domain.market.service.StockMarketStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StockMarketTransportEvent(
	int version,
	EventType eventType,
	String eventId,
	Market market,
	String symbol,
	BigDecimal price,
	LocalDateTime sourceTime,
	LocalDate sourceTradingDate,
	StockMarketStatus marketStatus,
	PriceStatus status,
	String reason,
	LocalDateTime emittedAt) {

	public static StockMarketTransportEvent price(String eventId, MarketPriceEvent event) {
		return new StockMarketTransportEvent(1, EventType.PRICE, eventId, event.market(), event.symbol(), event.price(),
			event.sourceTime(), event.sourceTradingDate(), event.marketStatus(), null, null, event.emittedAt());
	}

	public static StockMarketTransportEvent status(String eventId, MarketStatusEvent event) {
		return new StockMarketTransportEvent(1, EventType.STATUS, eventId, event.market(), event.symbol(), null, null,
			null, event.marketStatus(), event.status(), event.reason(), event.emittedAt());
	}

	public enum EventType {

		PRICE,

		STATUS
	}
}
