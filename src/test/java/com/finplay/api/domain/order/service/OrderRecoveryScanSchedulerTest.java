package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.event.CryptoPriceUpdatedEvent;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.store.CryptoPriceDto;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.order.listener.ExitPlanTriggerListener;
import com.finplay.api.domain.order.listener.LimitOrderTriggerListener;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class OrderRecoveryScanSchedulerTest {

	private static final LocalDateTime RECEIVED_AT = LocalDateTime.of(2026, 9, 18, 10, 0);
	private static final LocalDateTime OBSERVED_AT = LocalDateTime.of(2026, 9, 18, 10, 0, 1);

	private final InstrumentService instrumentService = mock(InstrumentService.class);
	private final PriceStore priceStore = mock(PriceStore.class);
	private final LimitOrderTriggerListener limitOrderTriggerListener = mock(LimitOrderTriggerListener.class);
	private final ExitPlanTriggerListener exitPlanTriggerListener = mock(ExitPlanTriggerListener.class);
	private final OrderRecoveryScanLock orderRecoveryScanLock = mock(OrderRecoveryScanLock.class);
	private final OrderRecoveryScanScheduler scheduler = new OrderRecoveryScanScheduler(
		instrumentService, priceStore, limitOrderTriggerListener, exitPlanTriggerListener, orderRecoveryScanLock);

	@Test
	@DisplayName("Redis 재검사 락을 얻지 못하면 이번 실행을 건너뛴다")
	void skipsScanWhenRecoveryLockIsNotAcquired() {
		when(orderRecoveryScanLock.tryLock()).thenReturn(Optional.empty());

		scheduler.scanOnSchedule();

		verifyNoInteractions(instrumentService, priceStore, limitOrderTriggerListener, exitPlanTriggerListener);
	}

	@Test
	@DisplayName("startup과 scheduled scan은 같은 최신 가격 snapshot event를 두 Listener에 전달한다")
	void startupAndScheduledScanShareTheLatestSnapshotEventAcrossBothListeners() {
		Instrument bitcoin = instrument("BTC");
		CryptoPriceDto latestPrice = new CryptoPriceDto(
			"BTC", new BigDecimal("101.25"), RECEIVED_AT, OBSERVED_AT);
		givenScanData(List.of(bitcoin), Map.of("BTC", latestPrice));
		when(orderRecoveryScanLock.tryLock()).thenReturn(Optional.of("token"));

		scheduler.scanOnStartup();
		scheduler.scanOnSchedule();

		var limitEvents = org.mockito.ArgumentCaptor.forClass(CryptoPriceUpdatedEvent.class);
		var exitPlanEvents = org.mockito.ArgumentCaptor.forClass(CryptoPriceUpdatedEvent.class);
		verify(limitOrderTriggerListener, org.mockito.Mockito.times(2)).onPriceUpdated(limitEvents.capture());
		verify(exitPlanTriggerListener, org.mockito.Mockito.times(2)).onPriceUpdated(exitPlanEvents.capture());

		assertThat(limitEvents.getAllValues()).allSatisfy(event -> {
			assertThat(event.symbol()).isEqualTo("BTC");
			assertThat(event.price()).isEqualByComparingTo("101.25");
			assertThat(event.receivedAt()).isEqualTo(RECEIVED_AT);
			assertThat(event.observedAt()).isEqualTo(OBSERVED_AT);
		});
		assertThat(limitEvents.getAllValues().get(0)).isSameAs(exitPlanEvents.getAllValues().get(0));
		assertThat(limitEvents.getAllValues().get(1)).isSameAs(exitPlanEvents.getAllValues().get(1));
		verify(orderRecoveryScanLock, org.mockito.Mockito.times(2)).unlock("token");
	}

	@Test
	@DisplayName("PriceStore가 부재한 최신 가격을 반환하지 않으면 해당 종목을 건너뛴다")
	void skipsInstrumentWithoutLatestPrice() {
		Instrument bitcoin = instrument("BTC");
		givenScanData(List.of(bitcoin), Map.of());
		when(orderRecoveryScanLock.tryLock()).thenReturn(Optional.of("token"));

		scheduler.scanOnSchedule();

		verify(limitOrderTriggerListener, never()).onPriceUpdated(org.mockito.ArgumentMatchers.any());
		verify(exitPlanTriggerListener, never()).onPriceUpdated(org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("PriceStore가 stale 가격을 유효 가격에서 제외하면 해당 종목을 건너뛴다")
	void skipsInstrumentWithStalePrice() {
		Instrument bitcoin = instrument("BTC");
		Instrument ethereum = instrument("ETH");
		CryptoPriceDto stalePrice = new CryptoPriceDto(
			"ETH", new BigDecimal("202.50"), RECEIVED_AT.minusSeconds(11), OBSERVED_AT);
		givenScanData(List.of(bitcoin, ethereum), Map.of(
			"BTC", price("BTC", "101.25"),
			"ETH", stalePrice));
		when(priceStore.isStale(RECEIVED_AT)).thenReturn(false);
		when(priceStore.isStale(RECEIVED_AT.minusSeconds(11))).thenReturn(true);
		when(orderRecoveryScanLock.tryLock()).thenReturn(Optional.of("token"));

		scheduler.scanOnStartup();

		verify(limitOrderTriggerListener).onPriceUpdated(argThat(event -> event.symbol().equals("BTC")));
		verify(exitPlanTriggerListener).onPriceUpdated(argThat(event -> event.symbol().equals("BTC")));
		verify(limitOrderTriggerListener, never()).onPriceUpdated(argThat(event -> event.symbol().equals("ETH")));
		verify(exitPlanTriggerListener, never()).onPriceUpdated(argThat(event -> event.symbol().equals("ETH")));
	}

	@Test
	@DisplayName("한 종목의 한 phase 예외가 다른 종목과 다른 phase의 재검사를 막지 않는다")
	void isolatesInstrumentAndPhaseFailures() {
		Instrument bitcoin = instrument("BTC");
		Instrument ethereum = instrument("ETH");
		givenScanData(List.of(bitcoin, ethereum), Map.of(
			"BTC", price("BTC", "101.25"),
			"ETH", price("ETH", "202.50")));
		when(orderRecoveryScanLock.tryLock()).thenReturn(Optional.of("token"));
		doThrow(new RuntimeException("limit failure"))
			.when(limitOrderTriggerListener)
			.onPriceUpdated(argThat(event -> event.symbol().equals("BTC")));
		doThrow(new RuntimeException("OCO failure"))
			.when(exitPlanTriggerListener)
			.onPriceUpdated(argThat(event -> event.symbol().equals("ETH")));

		scheduler.scanOnSchedule();

		verify(exitPlanTriggerListener).onPriceUpdated(argThat(event -> event.symbol().equals("BTC")));
		verify(limitOrderTriggerListener).onPriceUpdated(argThat(event -> event.symbol().equals("ETH")));
		verify(orderRecoveryScanLock).unlock("token");
	}

	@Test
	@DisplayName("종목 조회나 가격 조회가 실패해도 finally에서 재검사 락을 해제한다")
	void unlocksInFinallyWhenScanFailsBeforeInstrumentProcessing() {
		when(orderRecoveryScanLock.tryLock()).thenReturn(Optional.of("token"));
		when(instrumentService.getRealInstrumentEntities(Market.CRYPTO))
			.thenThrow(new RuntimeException("instrument failure"));

		scheduler.scanOnStartup();

		verify(orderRecoveryScanLock).unlock("token");
		verifyNoInteractions(priceStore, limitOrderTriggerListener, exitPlanTriggerListener);
	}

	@Test
	@DisplayName("정기 재검사는 5초 fixed delay로 등록된다")
	void scheduledScanUsesFiveSecondFixedDelay() throws NoSuchMethodException {
		Scheduled scheduled = OrderRecoveryScanScheduler.class
			.getMethod("scanOnSchedule")
			.getAnnotation(Scheduled.class);

		assertThat(scheduled).isNotNull();
		assertThat(scheduled.fixedDelay()).isEqualTo(5_000L);
	}

	private void givenScanData(List<Instrument> instruments, Map<String, CryptoPriceDto> prices) {
		when(instrumentService.getRealInstrumentEntities(Market.CRYPTO)).thenReturn(instruments);
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);
		for (Instrument instrument : instruments) {
			String symbol = instrument.getSymbol();
			when(priceStore.getLatestPrice(symbol)).thenReturn(Optional.ofNullable(prices.get(symbol)));
		}
	}

	private Instrument instrument(String symbol) {
		Instrument instrument = mock(Instrument.class);
		when(instrument.getSymbol()).thenReturn(symbol);
		return instrument;
	}

	private CryptoPriceDto price(String symbol, String price) {
		return new CryptoPriceDto(symbol, new BigDecimal(price), RECEIVED_AT, OBSERVED_AT);
	}
}
