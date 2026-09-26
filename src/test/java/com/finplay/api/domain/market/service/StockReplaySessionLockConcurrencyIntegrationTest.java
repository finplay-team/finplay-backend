package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StockReplaySessionLockConcurrencyIntegrationTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 9, 13);
	private static final String LOCK_KEY = "market:stock-replay-session:lock:" + SERVICE_DATE;

	@Autowired
	private StockReplaySessionLock stockReplaySessionLock;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@BeforeEach
	void setUp() {
		redisTemplate.delete(LOCK_KEY);
	}

	@AfterEach
	void cleanUp() {
		redisTemplate.delete(LOCK_KEY);
	}

	@Test
	void concurrentTryLocksAllowOnlyOneWinnerAndUnlockAllowsReacquisition() throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Optional<String>> firstAttempt = executor.submit(() -> tryLockAfter(start, ready));
			Future<Optional<String>> secondAttempt = executor.submit(() -> tryLockAfter(start, ready));

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			Optional<String> firstToken = firstAttempt.get(15, TimeUnit.SECONDS);
			Optional<String> secondToken = secondAttempt.get(15, TimeUnit.SECONDS);
			List<Optional<String>> attempts = List.of(firstToken, secondToken);
			assertThat(attempts).filteredOn(Optional::isPresent).hasSize(1);

			String winnerToken = attempts.stream().flatMap(Optional::stream).findFirst().orElseThrow();
			stockReplaySessionLock.unlock(SERVICE_DATE, winnerToken);

			assertThat(stockReplaySessionLock.tryLock(SERVICE_DATE)).isPresent();
		} finally {
			start.countDown();
			executor.shutdownNow();
		}
		assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
	}

	private Optional<String> tryLockAfter(CountDownLatch start, CountDownLatch ready) throws InterruptedException {
		ready.countDown();
		start.await();
		return stockReplaySessionLock.tryLock(SERVICE_DATE);
	}
}
