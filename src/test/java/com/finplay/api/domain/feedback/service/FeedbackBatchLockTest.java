package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.feedback.config.FeedbackBatchProperties;
import com.finplay.api.global.lock.RedisLock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FeedbackBatchLockTest {

	private static final FeedbackBatchProperties PROPERTIES = new FeedbackBatchProperties(
		"cron", "crypto", "peer", "watch", "crypto-peer", 37);

	private final RedisLock redisLock = org.mockito.Mockito.mock(RedisLock.class);

	private final FeedbackBatchLock lock = new FeedbackBatchLock(redisLock, PROPERTIES);

	@Test
	void triesLockWithBatchAndScopeKeyAndConfiguredTtl() {
		when(redisLock.tryLock(
			"feedback:batch:lock:pre-market:2026-08-05", Duration.ofSeconds(37)))
			.thenReturn(Optional.of("token"));

		assertThat(lock.tryLock(FeedbackBatchLock.Batch.PRE_MARKET, "2026-08-05"))
			.contains("token");

		verify(redisLock).tryLock(
			"feedback:batch:lock:pre-market:2026-08-05", Duration.ofSeconds(37));
	}

	@Test
	void unlocksWithTheSameBatchScopeAndToken() {
		when(redisLock.unlock(
			"feedback:batch:lock:crypto-feedback:2026-09-14T10:00", "token"))
			.thenReturn(RedisLock.UnlockResult.RELEASED);

		lock.unlock(FeedbackBatchLock.Batch.CRYPTO_FEEDBACK, "2026-09-14T10:00", "token");

		verify(redisLock).unlock(
			"feedback:batch:lock:crypto-feedback:2026-09-14T10:00", "token");
	}

	@Test
	void rejectsNonPositiveLockTtl() {
		assertThatThrownBy(() -> new FeedbackBatchProperties(
			"cron", "crypto", "peer", "watch", "crypto-peer", 0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("feedback.batch.lock-ttl-seconds는 1 이상이어야 합니다.");
	}
}
