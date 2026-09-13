package com.finplay.api.domain.market.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.finplay.api.TestcontainersConfiguration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BithumbFeedLeaderLockConcurrencyIntegrationTest {

	private static final String LOCK_KEY = "market:bithumb-feed:leader";

	@Autowired
	private BithumbFeedLeaderLock bithumbFeedLeaderLock;

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
	@DisplayName("[방어 켠 상태] 두 인스턴스를 흉내낸 BithumbFeedLifecycle 두 개가 실제 Redis 락으로 동시에 리더 선출을 시도해도 "
		+ "하나만 리더가 되어 클라이언트를 시작한다")
	void onlyOneInstanceBecomesLeaderWhenTwoRaceConcurrently() throws Exception {
		AtomicInteger startCount = new AtomicInteger();
		BithumbFeedClient clientA = mock(BithumbFeedClient.class);
		BithumbFeedClient clientB = mock(BithumbFeedClient.class);
		doAnswer(invocation -> startCount.incrementAndGet()).when(clientA).start();
		doAnswer(invocation -> startCount.incrementAndGet()).when(clientB).start();
		BithumbFeedLifecycle instanceA = new BithumbFeedLifecycle(clientA, bithumbFeedLeaderLock);
		BithumbFeedLifecycle instanceB = new BithumbFeedLifecycle(clientB, bithumbFeedLeaderLock);

		runConcurrently(instanceA::electLeader, instanceB::electLeader);

		assertThat(startCount.get())
			.as("정확히 한 인스턴스만 리더가 되어 start()를 호출해야 한다")
			.isEqualTo(1);
	}

	@Test
	@DisplayName("[결정론적 방어 확인] 리더가 락을 명시적으로 해제(unlock)하면 팔로워가 다음 시도에서 곧바로 리더가 된다")
	void followerBecomesLeaderImmediatelyAfterTheLeaderUnlocks() {
		BithumbFeedClient clientA = mock(BithumbFeedClient.class);
		BithumbFeedClient clientB = mock(BithumbFeedClient.class);
		BithumbFeedLifecycle instanceA = new BithumbFeedLifecycle(clientA, bithumbFeedLeaderLock);
		BithumbFeedLifecycle instanceB = new BithumbFeedLifecycle(clientB, bithumbFeedLeaderLock);

		instanceA.electLeader();
		verify(clientA, times(1)).start();
		instanceB.electLeader();
		verify(clientB, never()).start();

		instanceA.stopFeed();
		verify(clientA, times(1)).stop();

		instanceB.electLeader();
		verify(clientB, times(1)).start();
	}

	private void runConcurrently(ThrowingRunnable actionA, ThrowingRunnable actionB) throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Void> futureA = executor.submit(toCallable(actionA, ready, start));
			Future<Void> futureB = executor.submit(toCallable(actionB, ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			futureA.get(15, TimeUnit.SECONDS);
			futureB.get(15, TimeUnit.SECONDS);
		} finally {
			start.countDown();
			executor.shutdownNow();
		}
		assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
	}

	private Callable<Void> toCallable(ThrowingRunnable action, CountDownLatch ready, CountDownLatch start) {
		return () -> {
			ready.countDown();
			start.await();
			action.run();
			return null;
		};
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
