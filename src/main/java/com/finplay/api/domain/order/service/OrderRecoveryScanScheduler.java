package com.finplay.api.domain.order.service;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.event.CryptoPriceUpdatedEvent;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.store.CryptoPriceDto;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.order.listener.ExitPlanTriggerListener;
import com.finplay.api.domain.order.listener.LimitOrderTriggerListener;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Profile("prod & scheduler")
@RequiredArgsConstructor
@Slf4j
public class OrderRecoveryScanScheduler {

	private final InstrumentService instrumentService;
	private final PriceStore priceStore;
	private final LimitOrderTriggerListener limitOrderTriggerListener;
	private final ExitPlanTriggerListener exitPlanTriggerListener;
	private final OrderRecoveryScanLock orderRecoveryScanLock;

	@EventListener(ApplicationReadyEvent.class)
	public void scanOnStartup() {
		scan("startup");
	}

	@Scheduled(fixedDelay = 5_000L)
	public void scanOnSchedule() {
		scan("schedule");
	}

	private void scan(String trigger) {
		Optional<String> lockToken = orderRecoveryScanLock.tryLock();
		if (lockToken.isEmpty()) {
			log.info("주문 재검사 락을 얻지 못해 이번 실행을 건너뜁니다. trigger={}", trigger);
			return;
		}
		try {
			List<Instrument> instruments = instrumentService.getRealInstrumentEntities(Market.CRYPTO);
			for (Instrument instrument : instruments) {
				try {
					Optional<CryptoPriceDto> price = getLatestValidPrice(instrument.getSymbol());
					scanInstrument(trigger, instrument, price);
				} catch (Exception e) {
					log.error("종목별 주문 재검사 중 예외가 발생했습니다. trigger={}, symbol={}", trigger,
						instrument.getSymbol(), e);
				}
			}
		} catch (Exception e) {
			log.error("주문 재검사 실행 중 예외가 발생했습니다. trigger={}", trigger, e);
		} finally {
			orderRecoveryScanLock.unlock(lockToken.get());
		}
	}

	private Optional<CryptoPriceDto> getLatestValidPrice(String symbol) {
		if (priceStore.getConnectionStatus() != FeedConnectionStatus.CONNECTED) {
			return Optional.empty();
		}
		return priceStore.getLatestPrice(symbol)
			.filter(price -> !priceStore.isStale(price.receivedAt()));
	}

	private void scanInstrument(String trigger, Instrument instrument, Optional<CryptoPriceDto> price) {
		if (price.isEmpty()) {
			log.debug("유효한 최신 가격이 없어 주문 재검사를 건너뜁니다. trigger={}, symbol={}", trigger,
				instrument.getSymbol());
			return;
		}

		CryptoPriceDto snapshot = price.get();
		CryptoPriceUpdatedEvent event = new CryptoPriceUpdatedEvent(
			instrument.getSymbol(), snapshot.price(), snapshot.receivedAt(), snapshot.observedAt());
		try {
			limitOrderTriggerListener.onPriceUpdated(event);
		} catch (Exception e) {
			log.error("지정가 주문 재검사 중 예외가 발생했습니다. trigger={}, symbol={}", trigger,
				instrument.getSymbol(), e);
		}
		try {
			exitPlanTriggerListener.onPriceUpdated(event);
		} catch (Exception e) {
			log.error("OCO 주문 재검사 중 예외가 발생했습니다. trigger={}, symbol={}", trigger,
				instrument.getSymbol(), e);
		}
	}
}
