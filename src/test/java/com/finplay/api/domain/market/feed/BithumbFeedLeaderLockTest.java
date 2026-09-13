package com.finplay.api.domain.market.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.global.lock.RedisLock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class BithumbFeedLeaderLockTest {

	private static final String LOCK_KEY = "market:bithumb-feed:leader";

	private static final long LOCK_TTL_SECONDS = 30;

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

	private BithumbFeedLeaderLock leaderLock(long ttlSeconds) {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		return new BithumbFeedLeaderLock(new RedisLock(redisTemplate), ttlSeconds);
	}

	@Test
	void tryLockReturnsTokenWhenSetIfAbsentSucceeds() {
		BithumbFeedLeaderLock lock = leaderLock(LOCK_TTL_SECONDS);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), any(Duration.class))).thenReturn(true);

		Optional<String> token = lock.tryLock();

		assertThat(token).isPresent();
	}

	@Test
	void tryLockPassesConfiguredLockTtlSecondsAsTheExpirationDuration() {
		BithumbFeedLeaderLock lock = leaderLock(5);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), eq(Duration.ofSeconds(5)))).thenReturn(true);

		lock.tryLock();

		verify(valueOperations).setIfAbsent(eq(LOCK_KEY), any(), eq(Duration.ofSeconds(5)));
	}

	@Test
	void tryLockReturnsEmptyWhenSetIfAbsentFailsBecauseKeyIsAlreadyLocked() {
		BithumbFeedLeaderLock lock = leaderLock(LOCK_TTL_SECONDS);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), any(Duration.class))).thenReturn(false);

		Optional<String> token = lock.tryLock();

		assertThat(token).isEmpty();
	}

	@Test
	void tryLockGeneratesADifferentTokenOnEachSuccessfulCall() {
		BithumbFeedLeaderLock lock = leaderLock(LOCK_TTL_SECONDS);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), any(Duration.class))).thenReturn(true);

		Optional<String> first = lock.tryLock();
		Optional<String> second = lock.tryLock();

		assertThat(first).isPresent();
		assertThat(second).isPresent();
		assertThat(first.get()).isNotEqualTo(second.get());
	}

	@Test
	@SuppressWarnings("unchecked")
	void renewReturnsTrueWhenTheRenewScriptExtendsTheKey() {
		BithumbFeedLeaderLock lock = leaderLock(LOCK_TTL_SECONDS);
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any(), any()))
			.thenReturn(1L);

		boolean renewed = lock.renew("some-token");

		assertThat(renewed).isTrue();
		verify(redisTemplate).execute(
			(RedisScript<Long>)any(RedisScript.class), eq(List.of(LOCK_KEY)), eq("some-token"), eq("30000"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void renewReturnsFalseWhenTheTokenNoLongerMatches() {
		BithumbFeedLeaderLock lock = leaderLock(LOCK_TTL_SECONDS);
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any(), any()))
			.thenReturn(0L);

		boolean renewed = lock.renew("stale-token");

		assertThat(renewed).isFalse();
	}

	@Test
	@SuppressWarnings("unchecked")
	void renewReturnsFalseWhenRedisThrows() {
		BithumbFeedLeaderLock lock = leaderLock(LOCK_TTL_SECONDS);
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any(), any()))
			.thenThrow(new RuntimeException("Redis 장애"));

		boolean renewed = lock.renew("some-token");

		assertThat(renewed).isFalse();
	}

	@Test
	@SuppressWarnings("unchecked")
	void unlockExecutesTheCheckThenDeleteScriptWithTheLockKeyAndGivenToken() {
		BithumbFeedLeaderLock lock = leaderLock(LOCK_TTL_SECONDS);
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any())).thenReturn(1L);

		lock.unlock("some-token");

		verify(redisTemplate)
			.execute((RedisScript<Long>)any(RedisScript.class), eq(List.of(LOCK_KEY)), eq("some-token"));
	}
}
