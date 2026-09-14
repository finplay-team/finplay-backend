package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.collector.CollectedNewsDto;
import com.finplay.api.domain.feedback.collector.DisclosureCollector;
import com.finplay.api.domain.feedback.collector.NewsCollector;
import com.finplay.api.domain.feedback.config.FeedbackBatchProperties;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.service.StockReplayService;
import com.finplay.api.domain.market.service.StockReplaySessionDto;
import com.finplay.api.domain.portfolio.service.HolderPopulationQueryService;
import com.finplay.api.global.lock.RedisLock;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FeedbackBatchConcurrencyIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-06T06:45:00Z"), KST);

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);

	private static final String LOCK_KEY_PREFIX = "feedback:batch:lock:";

	@Autowired
	private RedisLock redisLock;

	@Autowired
	private FeedbackBatchProperties batchProperties;

	@Autowired
	private StringRedisTemplate redisTemplate;

	private FeedbackBatchLock feedbackBatchLock;

	private NewsCollector newsCollector;

	private DisclosureCollector disclosureCollector;

	private InstrumentService instrumentService;

	private NewsCollectionService newsCollectionService;

	private MarketNewsItemRepository marketNewsItemRepository;

	private StockReplayService stockReplayService;

	private PriceMoveDetector priceMoveDetector;

	private PriceMoveCardService priceMoveCardService;

	private MarketBriefingService marketBriefingService;

	private InstrumentNewsSummaryService instrumentNewsSummaryService;

	private FeedbackBatchService feedbackBatchService;

	private PriceMoveEventRepository priceMoveEventRepository;

	private PriceMovePeerStatRepository priceMovePeerStatRepository;

	private HolderPopulationQueryService holderPopulationQueryService;

	private PeerStatsBatchService peerStatsBatchService;

	private CryptoFeedbackBatchService cryptoFeedbackBatchService;

	@BeforeEach
	void setUp() {
		feedbackBatchLock = new FeedbackBatchLock(redisLock, batchProperties);
		newsCollector = mock(NewsCollector.class);
		disclosureCollector = mock(DisclosureCollector.class);
		instrumentService = mock(InstrumentService.class);
		marketNewsItemRepository = mock(MarketNewsItemRepository.class);
		newsCollectionService = new NewsCollectionService(
			newsCollector, disclosureCollector, instrumentService, marketNewsItemRepository,
			CLOCK, feedbackBatchLock);

		stockReplayService = mock(StockReplayService.class);
		priceMoveDetector = mock(PriceMoveDetector.class);
		priceMoveCardService = mock(PriceMoveCardService.class);
		marketBriefingService = mock(MarketBriefingService.class);
		instrumentNewsSummaryService = mock(InstrumentNewsSummaryService.class);
		feedbackBatchService = new FeedbackBatchService(
			stockReplayService, instrumentService, priceMoveDetector, priceMoveCardService,
			marketBriefingService, instrumentNewsSummaryService, new LlmCallStats(), feedbackBatchLock);

		priceMoveEventRepository = mock(PriceMoveEventRepository.class);
		priceMovePeerStatRepository = mock(PriceMovePeerStatRepository.class);
		holderPopulationQueryService = mock(HolderPopulationQueryService.class);
		peerStatsBatchService = new PeerStatsBatchService(
			stockReplayService, priceMoveEventRepository, priceMovePeerStatRepository,
			holderPopulationQueryService, CLOCK, feedbackBatchLock);

		cryptoFeedbackBatchService = new CryptoFeedbackBatchService(
			instrumentService, instrumentNewsSummaryService, marketBriefingService, feedbackBatchLock);
	}

	@AfterEach
	void cleanUpRedis() {
		for (FeedbackBatchLock.Batch batch : FeedbackBatchLock.Batch.values()) {
			redisTemplate.delete(LOCK_KEY_PREFIX + batchKey(batch) + ":" + FeedbackBatchLock.SCHEDULED_SCOPE);
		}
	}

	@Test
	void onlyOneNewsCollectionBodyRunsForConcurrentInvocations() throws Exception {
		Instrument instrument = mockInstrument("삼성전자");
		when(instrumentService.getRealInstrumentEntities(any())).thenReturn(List.of(instrument));
		ExecutionGate gate = new ExecutionGate();
		when(newsCollector.collect(any(), any())).thenAnswer(invocation -> {
			gate.blockFirst();
			return List.of(new CollectedNewsDto(
				"테스트 뉴스", "테스트 언론사", "https://example.com/test", null));
		});
		when(marketNewsItemRepository.findExistingUrls(anyLong(), any())).thenReturn(List.of());
		when(marketNewsItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		runConcurrently(newsCollectionService::collectNews, gate);

		verify(newsCollector, times(Market.values().length)).collect(any(), any());
		verify(marketNewsItemRepository, times(Market.values().length)).save(any());
	}

	@Test
	void onlyOnePreMarketBodyRunsForConcurrentInvocations() throws Exception {
		Instrument instrument = mockInstrument("삼성전자");
		when(stockReplayService.getCurrentReplaySession())
			.thenReturn(new StockReplaySessionDto(true, ORIGIN_TRADE_DATE));
		when(instrumentService.getRealInstrumentEntities(Market.STOCK)).thenReturn(List.of(instrument));
		ExecutionGate gate = new ExecutionGate();
		when(marketBriefingService.generateStockBriefing(any())).thenAnswer(invocation -> {
			gate.blockFirst();
			return Optional.empty();
		});
		when(instrumentNewsSummaryService.generateStockSummary(any(), any(), any()))
			.thenReturn(Optional.empty());
		when(stockReplayService.getFullDayCandles(anyLong(), any())).thenReturn(List.of());
		when(stockReplayService.getPreviousTradingDayClose(anyLong(), any())).thenReturn(Optional.empty());
		when(priceMoveDetector.detect(any(), any())).thenReturn(List.of());

		runConcurrently(feedbackBatchService::runPreMarketBatch, gate);

		verify(marketBriefingService).generateStockBriefing(ORIGIN_TRADE_DATE);
	}

	@Test
	void onlyOnePeerStatsBodyRunsForConcurrentInvocations() throws Exception {
		when(stockReplayService.getCurrentReplaySession())
			.thenReturn(new StockReplaySessionDto(true, ORIGIN_TRADE_DATE));
		Instrument instrument = mockInstrument("삼성전자");
		PriceMoveEvent card = mock(PriceMoveEvent.class);
		when(card.getId()).thenReturn(1L);
		when(card.getInstrument()).thenReturn(instrument);
		when(card.getWindowEnd()).thenReturn(LocalTime.of(9, 10));
		when(priceMovePeerStatRepository.existsByPriceMoveEventIdAndServiceDate(anyLong(), any()))
			.thenReturn(false);
		when(holderPopulationQueryService.populationSnapshotAtTime(anyLong(), any()))
			.thenReturn(new HolderPopulationQueryService.PopulationSnapshot(1, List.of(10)));
		ExecutionGate gate = new ExecutionGate();
		when(priceMoveEventRepository.findByMarketAndOriginTradeDate(Market.STOCK, ORIGIN_TRADE_DATE))
			.thenAnswer(invocation -> {
				gate.blockFirst();
				return List.of(card);
			});

		runConcurrently(peerStatsBatchService::runPeerStatsBatch, gate);

		verify(priceMoveEventRepository).findByMarketAndOriginTradeDate(Market.STOCK, ORIGIN_TRADE_DATE);
		verify(priceMovePeerStatRepository).save(any());
	}

	@Test
	void onlyOneCryptoFeedbackBodyRunsForConcurrentInvocations() throws Exception {
		Instrument instrument = mockInstrument("비트코인");
		when(instrumentService.getRealInstrumentEntities(Market.CRYPTO)).thenReturn(List.of(instrument));
		ExecutionGate gate = new ExecutionGate();
		when(instrumentNewsSummaryService.refreshCryptoSummary(any())).thenAnswer(invocation -> {
			gate.blockFirst();
			return Optional.empty();
		});
		when(marketBriefingService.refreshCryptoBriefing()).thenReturn(Optional.empty());

		runConcurrently(cryptoFeedbackBatchService::refreshCryptoFeedback, gate);

		verify(instrumentNewsSummaryService).refreshCryptoSummary(instrument);
		verify(marketBriefingService).refreshCryptoBriefing();
	}

	@Test
	void heldRedisLockMakesServiceSkipWithoutCallingItsBody() {
		Instrument instrument = mockInstrument("비트코인");
		when(instrumentService.getRealInstrumentEntities(Market.CRYPTO)).thenReturn(List.of(instrument));
		Optional<String> holderToken = feedbackBatchLock.tryLock(
			FeedbackBatchLock.Batch.CRYPTO_FEEDBACK, FeedbackBatchLock.SCHEDULED_SCOPE);
		assertThat(holderToken).isPresent();

		try {
			cryptoFeedbackBatchService.refreshCryptoFeedback();

			verifyNoInteractions(instrumentService, instrumentNewsSummaryService, marketBriefingService);
		} finally {
			feedbackBatchLock.unlock(
				FeedbackBatchLock.Batch.CRYPTO_FEEDBACK, FeedbackBatchLock.SCHEDULED_SCOPE, holderToken.orElseThrow());
		}
	}

	private Instrument mockInstrument(String name) {
		Instrument instrument = mock(Instrument.class);
		when(instrument.getName()).thenReturn(name);
		when(instrument.getId()).thenReturn(1L);
		return instrument;
	}

	private void runConcurrently(Runnable action, ExecutionGate gate) throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		List<Future<?>> futures = List.of(
			submit(executor, action, ready, start), submit(executor, action, ready, start));
		try {
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
		} finally {
			gate.release.countDown();
		}
		try {
			for (Future<?> future : futures) {
				future.get(10, TimeUnit.SECONDS);
			}
		} catch (ExecutionException ex) {
			throw new AssertionError("동시 실행 스레드에서 배치가 실패했다", ex.getCause());
		} finally {
			executor.shutdownNow();
		}
	}

	private Future<?> submit(
		ExecutorService executor, Runnable action, CountDownLatch ready, CountDownLatch start) {
		return executor.submit(() -> {
			ready.countDown();
			if (!start.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("동시 실행 시작 신호를 받지 못했다");
			}
			action.run();
			return null;
		});
	}

	private static String batchKey(FeedbackBatchLock.Batch batch) {
		return switch (batch) {
			case NEWS_COLLECTION -> "news-collection";
			case DISCLOSURE_COLLECTION -> "disclosure-collection";
			case PRE_MARKET -> "pre-market";
			case PEER_STATS -> "peer-stats";
			case CRYPTO_FEEDBACK -> "crypto-feedback";
			case CRYPTO_PEER_STATS -> "crypto-peer-stats";
		};
	}

	private static final class ExecutionGate {

		private final CountDownLatch entered = new CountDownLatch(1);

		private final CountDownLatch release = new CountDownLatch(1);

		private final AtomicBoolean first = new AtomicBoolean();

		private void blockFirst() throws InterruptedException {
			if (first.compareAndSet(false, true)) {
				entered.countDown();
				if (!release.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("첫 번째 배치 본문이 해제 신호를 받지 못했다");
				}
			}
		}
	}
}
