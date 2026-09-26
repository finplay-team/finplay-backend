package com.finplay.api.global.lock;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RedisLockIntegrationTest {

	private static final String MUTUAL_EXCLUSION_KEY = "test:redis-lock:mutual-exclusion";
	private static final String WRONG_TOKEN_KEY = "test:redis-lock:wrong-token";
	private static final String TTL_EXPIRY_KEY = "test:redis-lock:ttl-expiry";
	private static final String RENEW_KEY = "test:redis-lock:renew";
	private static final String WRITE_UNLESS_SUPERSEDED_LOCK_KEY = "test:redis-lock:write-unless-superseded";
	private static final String WRITE_UNLESS_SUPERSEDED_TARGET_KEY = "test:redis-lock:write-unless-superseded:target";

	private static final Duration LONG_ENOUGH_TTL = Duration.ofSeconds(30);

	@Autowired
	private RedisLock redisLock;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@AfterEach
	void cleanUpRedis() {
		redisTemplate.delete(MUTUAL_EXCLUSION_KEY);
		redisTemplate.delete(WRONG_TOKEN_KEY);
		redisTemplate.delete(TTL_EXPIRY_KEY);
		redisTemplate.delete(RENEW_KEY);
		redisTemplate.delete(WRITE_UNLESS_SUPERSEDED_LOCK_KEY);
		redisTemplate.delete(WRITE_UNLESS_SUPERSEDED_TARGET_KEY);
	}

	@Test
	@DisplayName("같은 키의 두 번째 tryLock은 실패하고, 해제한 뒤에는 다시 획득된다")
	void secondTryLockOnTheSameKeyFailsWhileHeldAndSucceedsAgainAfterUnlock() {
		Optional<String> firstToken = redisLock.tryLock(MUTUAL_EXCLUSION_KEY, LONG_ENOUGH_TTL);
		assertThat(firstToken).isPresent();

		Optional<String> whileHeld = redisLock.tryLock(MUTUAL_EXCLUSION_KEY, LONG_ENOUGH_TTL);
		assertThat(whileHeld).isEmpty();

		assertThat(redisLock.unlock(MUTUAL_EXCLUSION_KEY, firstToken.get()))
			.isEqualTo(RedisLock.UnlockResult.RELEASED);

		Optional<String> afterUnlock = redisLock.tryLock(MUTUAL_EXCLUSION_KEY, LONG_ENOUGH_TTL);
		assertThat(afterUnlock).isPresent();
		assertThat(afterUnlock.get()).isNotEqualTo(firstToken.get());
	}

	@Test
	@DisplayName("토큰이 다르면 unlock이 아무것도 지우지 않고 NOT_HELD를 반환하며 락은 그대로 유지된다")
	void unlockWithADifferentTokenDeletesNothingAndLeavesTheLockHeld() {
		Optional<String> token = redisLock.tryLock(WRONG_TOKEN_KEY, LONG_ENOUGH_TTL);
		assertThat(token).isPresent();

		assertThat(redisLock.unlock(WRONG_TOKEN_KEY, "token-that-does-not-match"))
			.isEqualTo(RedisLock.UnlockResult.NOT_HELD);

		assertThat(redisTemplate.opsForValue().get(WRONG_TOKEN_KEY)).isEqualTo(token.get());
		assertThat(redisLock.tryLock(WRONG_TOKEN_KEY, LONG_ENOUGH_TTL)).isEmpty();

		assertThat(redisLock.unlock(WRONG_TOKEN_KEY, token.get())).isEqualTo(RedisLock.UnlockResult.RELEASED);
	}

	@Test
	@DisplayName("TTL이 지나면 락이 스스로 풀려 다시 획득된다")
	void lockExpiresOnItsOwnAndCanBeReacquiredAfterTheTtlElapses() throws InterruptedException {
		Optional<String> token = redisLock.tryLock(TTL_EXPIRY_KEY, Duration.ofSeconds(1));
		assertThat(token).isPresent();

		Thread.sleep(1500);

		Optional<String> afterTtl = redisLock.tryLock(TTL_EXPIRY_KEY, Duration.ofSeconds(1));
		assertThat(afterTtl).isPresent();

		assertThat(redisLock.unlock(TTL_EXPIRY_KEY, token.get())).isEqualTo(RedisLock.UnlockResult.NOT_HELD);
	}

	@Test
	@DisplayName("보유자 토큰으로 renew하면 TTL이 실제로 연장돼 원래 TTL이 지나도 락이 유지된다")
	void renewWithTheHolderTokenExtendsTheTtlSoTheLockSurvivesPastTheOriginalTtl() throws InterruptedException {
		Optional<String> token = redisLock.tryLock(RENEW_KEY, Duration.ofSeconds(1));
		assertThat(token).isPresent();

		Thread.sleep(600);
		assertThat(redisLock.renew(RENEW_KEY, token.get(), Duration.ofSeconds(2))).isTrue();
		Thread.sleep(900);

		assertThat(redisTemplate.opsForValue().get(RENEW_KEY)).isEqualTo(token.get());
		assertThat(redisLock.unlock(RENEW_KEY, token.get())).isEqualTo(RedisLock.UnlockResult.RELEASED);
	}

	@Test
	@DisplayName("다른 토큰으로 renew하면 실패하고 원래 보유자의 락은 그대로 유지된다")
	void renewWithAWrongTokenFailsAndLeavesTheOriginalHoldersLockUntouched() {
		Optional<String> token = redisLock.tryLock(RENEW_KEY, LONG_ENOUGH_TTL);
		assertThat(token).isPresent();

		assertThat(redisLock.renew(RENEW_KEY, "token-that-does-not-match", LONG_ENOUGH_TTL)).isFalse();

		assertThat(redisTemplate.opsForValue().get(RENEW_KEY)).isEqualTo(token.get());
		assertThat(redisLock.unlock(RENEW_KEY, token.get())).isEqualTo(RedisLock.UnlockResult.RELEASED);
	}

	@Test
	@DisplayName("TTL이 이미 지나 키가 사라진 뒤에는 이전 보유자의 renew도 실패한다")
	void renewFailsAfterTheKeyHasAlreadyExpiredOnItsOwn() throws InterruptedException {
		Optional<String> token = redisLock.tryLock(RENEW_KEY, Duration.ofSeconds(1));
		assertThat(token).isPresent();

		Thread.sleep(1500);

		assertThat(redisLock.renew(RENEW_KEY, token.get(), LONG_ENOUGH_TTL)).isFalse();
	}

	@Test
	@DisplayName("내 토큰이 락을 여전히 들고 있으면 조건부 쓰기가 성공하고 타깃 키가 갱신된다")
	void writeUnlessSupersededWritesWhenMyTokenStillHoldsTheLock() {
		Optional<String> token = redisLock.tryLock(WRITE_UNLESS_SUPERSEDED_LOCK_KEY, LONG_ENOUGH_TTL);
		assertThat(token).isPresent();

		assertThat(redisLock.writeUnlessSuperseded(
			WRITE_UNLESS_SUPERSEDED_LOCK_KEY, token.get(), WRITE_UNLESS_SUPERSEDED_TARGET_KEY, "DISCONNECTED"))
			.isTrue();

		assertThat(redisTemplate.opsForValue().get(WRITE_UNLESS_SUPERSEDED_TARGET_KEY)).isEqualTo("DISCONNECTED");
	}

	@Test
	@DisplayName("락 키가 존재하지 않으면(아무도 넘겨받지 않음) 조건부 쓰기가 성공한다")
	void writeUnlessSupersededWritesWhenNobodyCurrentlyHoldsTheLock() {
		assertThat(redisTemplate.hasKey(WRITE_UNLESS_SUPERSEDED_LOCK_KEY)).isFalse();

		assertThat(redisLock.writeUnlessSuperseded(
			WRITE_UNLESS_SUPERSEDED_LOCK_KEY, "stale-token", WRITE_UNLESS_SUPERSEDED_TARGET_KEY, "DISCONNECTED"))
			.isTrue();

		assertThat(redisTemplate.opsForValue().get(WRITE_UNLESS_SUPERSEDED_TARGET_KEY)).isEqualTo("DISCONNECTED");
	}

	@Test
	@DisplayName("다른 토큰이 이미 락을 넘겨받았으면 조건부 쓰기를 건너뛰고 타깃 키를 건드리지 않는다")
	void writeUnlessSupersededSkipsWhenAnotherTokenAlreadyTookOverTheLock() {
		Optional<String> newLeaderToken = redisLock.tryLock(WRITE_UNLESS_SUPERSEDED_LOCK_KEY, LONG_ENOUGH_TTL);
		assertThat(newLeaderToken).isPresent();
		redisTemplate.opsForValue().set(WRITE_UNLESS_SUPERSEDED_TARGET_KEY, "CONNECTED");

		assertThat(redisLock.writeUnlessSuperseded(
			WRITE_UNLESS_SUPERSEDED_LOCK_KEY, "stale-previous-leader-token", WRITE_UNLESS_SUPERSEDED_TARGET_KEY,
			"DISCONNECTED"))
			.isFalse();

		assertThat(redisTemplate.opsForValue().get(WRITE_UNLESS_SUPERSEDED_TARGET_KEY)).isEqualTo("CONNECTED");
	}
}
