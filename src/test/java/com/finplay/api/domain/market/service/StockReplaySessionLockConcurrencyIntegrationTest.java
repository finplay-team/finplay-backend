package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import java.time.LocalDate;
import java.util.Optional;
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
	void secondTryLockFailsWhileLockedThenSucceedsAfterUnlock() {
		Optional<String> firstToken = stockReplaySessionLock.tryLock(SERVICE_DATE);
		assertThat(firstToken).isPresent();

		Optional<String> whileLocked = stockReplaySessionLock.tryLock(SERVICE_DATE);
		assertThat(whileLocked).isEmpty();

		stockReplaySessionLock.unlock(SERVICE_DATE, firstToken.orElseThrow());

		Optional<String> afterUnlock = stockReplaySessionLock.tryLock(SERVICE_DATE);
		assertThat(afterUnlock).isPresent();
	}
}
