package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import java.util.ArrayList;
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
class FeedbackBatchLockIntegrationTest {

	private static final String SCOPE = "feedback-batch-lock-integration";

	@Autowired
	private FeedbackBatchLock feedbackBatchLock;

	@Autowired
	private StringRedisTemplate redisTemplate;

	private ExecutorService executor;

	@BeforeEach
	void setUp() {
		redisTemplate.delete("feedback:batch:lock:pre-market:" + SCOPE);
		executor = Executors.newFixedThreadPool(2);
	}

	@AfterEach
	void tearDown() {
		executor.shutdownNow();
		redisTemplate.delete("feedback:batch:lock:pre-market:" + SCOPE);
	}

	@Test
	void onlyOneConcurrentExecutionAcquiresTheSameBatchScope() throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Optional<String>>> futures = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			futures.add(executor.submit(() -> {
				ready.countDown();
				assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
				return feedbackBatchLock.tryLock(FeedbackBatchLock.Batch.PRE_MARKET, SCOPE);
			}));
		}

		assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
		start.countDown();
		List<Optional<String>> results = List.of(
			futures.get(0).get(5, TimeUnit.SECONDS), futures.get(1).get(5, TimeUnit.SECONDS));

		assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
		String token = results.stream().flatMap(Optional::stream).findFirst().orElseThrow();
		feedbackBatchLock.unlock(FeedbackBatchLock.Batch.PRE_MARKET, SCOPE, token);
		assertThat(feedbackBatchLock.tryLock(FeedbackBatchLock.Batch.PRE_MARKET, SCOPE)).isPresent();
	}
}
