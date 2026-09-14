package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.StockReplayService;
import com.finplay.api.domain.market.service.StockReplaySessionDto;
import com.finplay.api.domain.portfolio.service.HolderPopulationQueryService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PeerStatsBatchServiceTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);

	private final StockReplayService stockReplayService = mock(StockReplayService.class);

	private final PriceMoveEventRepository priceMoveEventRepository = mock(PriceMoveEventRepository.class);

	private final PriceMovePeerStatRepository priceMovePeerStatRepository = mock(PriceMovePeerStatRepository.class);

	private final HolderPopulationQueryService holderPopulationQueryService = mock(HolderPopulationQueryService.class);

	private final FeedbackBatchLock feedbackBatchLock = mock(FeedbackBatchLock.class);

	private PeerStatsBatchService service;

	@BeforeEach
	void setUp() {
		service = new PeerStatsBatchService(
			stockReplayService,
			priceMoveEventRepository,
			priceMovePeerStatRepository,
			holderPopulationQueryService,
			Clock.fixed(Instant.parse("2026-08-06T06:32:00Z"), ZoneId.of("Asia/Seoul")),
			feedbackBatchLock);
		when(feedbackBatchLock.tryLock(any(), eq("scheduled"))).thenReturn(Optional.of("token"));
	}

	@Test
	void skipsStockPeerStatsWhenLockIsNotAcquired() {
		givenReadySession();
		when(feedbackBatchLock.tryLock(FeedbackBatchLock.Batch.PEER_STATS, "scheduled"))
			.thenReturn(Optional.empty());

		service.runPeerStatsBatch();

		verify(priceMoveEventRepository, never()).findByMarketAndOriginTradeDate(any(), any());
	}

	@Test
	void unlocksStockPeerStatsWhenCardQueryFails() {
		givenReadySession();
		when(priceMoveEventRepository.findByMarketAndOriginTradeDate(Market.STOCK, ORIGIN_TRADE_DATE))
			.thenThrow(new IllegalStateException("card query failed"));

		assertThatThrownBy(() -> service.runPeerStatsBatch())
			.isInstanceOf(IllegalStateException.class);

		verify(feedbackBatchLock).unlock(
			FeedbackBatchLock.Batch.PEER_STATS, "scheduled", "token");
	}

	@Test
	void skipsCryptoPeerStatsWhenLockIsNotAcquired() {
		when(feedbackBatchLock.tryLock(FeedbackBatchLock.Batch.CRYPTO_PEER_STATS, "scheduled"))
			.thenReturn(Optional.empty());

		service.runCryptoPeerStatsBatch();

		verify(priceMoveEventRepository, never()).findByMarketAndOccurredAtBetween(any(), any(), any());
	}

	@Test
	void unlocksCryptoPeerStatsWhenCardQueryFails() {
		when(priceMoveEventRepository.findByMarketAndOccurredAtBetween(
			eq(Market.CRYPTO), any(), any()))
			.thenThrow(new IllegalStateException("card query failed"));

		assertThatThrownBy(() -> service.runCryptoPeerStatsBatch())
			.isInstanceOf(IllegalStateException.class);

		verify(feedbackBatchLock).unlock(
			FeedbackBatchLock.Batch.CRYPTO_PEER_STATS, "scheduled", "token");
	}

	private void givenReadySession() {
		when(stockReplayService.getCurrentReplaySession())
			.thenReturn(new StockReplaySessionDto(true, ORIGIN_TRADE_DATE));
	}
}
