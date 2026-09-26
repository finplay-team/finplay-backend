package com.finplay.api.domain.market.dto.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.market.dto.sse.MarketPriceEvent;
import com.finplay.api.domain.market.dto.sse.MarketStatusEvent;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.PriceStatus;
import com.finplay.api.domain.market.service.StockMarketStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class StockMarketTransportEventTest {

	private static final LocalDateTime SOURCE_TIME = LocalDateTime.of(2026, 8, 10, 9, 0);
	private static final LocalDateTime EMITTED_AT = LocalDateTime.of(2026, 8, 10, 9, 1);
	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 8, 10);

	@Test
	void priceEventKeepsSsePayloadFieldsAndRoundTripsThroughJson() throws Exception {
		MarketPriceEvent price = new MarketPriceEvent(Market.STOCK, "TEST", new BigDecimal("71000"), SOURCE_TIME,
			EMITTED_AT, TRADING_DATE, StockMarketStatus.OPEN);

		StockMarketTransportEvent event = StockMarketTransportEvent.price("STOCK:TEST:202608100900", price);
		ObjectMapper mapper = new ObjectMapper();
		StockMarketTransportEvent restored = mapper.readValue(mapper.writeValueAsString(event),
			StockMarketTransportEvent.class);

		assertThat(restored.version()).isEqualTo(1);
		assertThat(restored.eventType()).isEqualTo(StockMarketTransportEvent.EventType.PRICE);
		assertThat(restored.eventId()).isEqualTo("STOCK:TEST:202608100900");
		assertThat(restored.market()).isEqualTo(Market.STOCK);
		assertThat(restored.symbol()).isEqualTo("TEST");
		assertThat(restored.price()).isEqualByComparingTo("71000");
		assertThat(restored.sourceTime()).isEqualTo(SOURCE_TIME);
		assertThat(restored.sourceTradingDate()).isEqualTo(TRADING_DATE);
		assertThat(restored.marketStatus()).isEqualTo(StockMarketStatus.OPEN);
		assertThat(restored.emittedAt()).isEqualTo(EMITTED_AT);
		assertThat(restored.status()).isNull();
	}

	@Test
	void statusEventCarriesStatusFieldsAndOmitsPriceFields() throws Exception {
		MarketStatusEvent status = new MarketStatusEvent(Market.STOCK, null, StockMarketStatus.CLOSED,
			PriceStatus.UNAVAILABLE, "market closed", EMITTED_AT);

		String json = new ObjectMapper().writeValueAsString(StockMarketTransportEvent.status(
			"STOCK:STATUS:CLOSED:2026-08-10T09:01", status));

		assertThat(json).contains("\"eventType\":\"STATUS\"");
		assertThat(json).contains("\"marketStatus\":\"CLOSED\"");
		assertThat(json).contains("\"status\":\"UNAVAILABLE\"");
		assertThat(json).contains("\"reason\":\"market closed\"");
		assertThat(json).doesNotContain("\"price\"");
		assertThat(json).doesNotContain("\"symbol\"");
	}
}
