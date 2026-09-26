package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.global.lock.RedisLock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderRecoveryScanLockTest {

	private static final String LOCK_KEY = "order:recovery-scan:lock";
	private static final Duration LOCK_TTL = Duration.ofSeconds(30);

	private final RedisLock redisLock = mock(RedisLock.class);
	private final OrderRecoveryScanLock lock = new OrderRecoveryScanLock(redisLock);

	@Test
	@DisplayName("재검사 락은 고정 키와 30초 TTL로 Redis 락 획득을 위임한다")
	void tryLockDelegatesToRedisWithRecoveryScanKeyAndTtl() {
		when(redisLock.tryLock(LOCK_KEY, LOCK_TTL)).thenReturn(Optional.of("token"));

		assertThat(lock.tryLock()).contains("token");

		verify(redisLock).tryLock(LOCK_KEY, LOCK_TTL);
	}

	@Test
	@DisplayName("재검사 락 해제는 같은 키와 전달받은 토큰으로 Redis에 위임한다")
	void unlockDelegatesToRedisWithRecoveryScanKeyAndToken() {
		when(redisLock.unlock(LOCK_KEY, "token")).thenReturn(RedisLock.UnlockResult.RELEASED);

		lock.unlock("token");

		verify(redisLock).unlock(eq(LOCK_KEY), eq("token"));
	}
}
