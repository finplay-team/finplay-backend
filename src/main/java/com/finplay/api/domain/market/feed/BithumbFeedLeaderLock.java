package com.finplay.api.domain.market.feed;

import com.finplay.api.global.lock.RedisLock;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BithumbFeedLeaderLock {

	private static final String LOCK_KEY = "market:bithumb-feed:leader";

	private final RedisLock redisLock;

	private final Duration lockTtl;

	public BithumbFeedLeaderLock(
		RedisLock redisLock,
		@Value("${bithumb.feed.leader.lock-ttl-seconds:30}")
		long lockTtlSeconds) {
		this.redisLock = redisLock;
		this.lockTtl = Duration.ofSeconds(lockTtlSeconds);
	}

	public Optional<String> tryLock() {
		return redisLock.tryLock(LOCK_KEY, lockTtl);
	}

	public boolean renew(String token) {
		return redisLock.renew(LOCK_KEY, token, lockTtl);
	}

	public void unlock(String token) {
		if (redisLock.unlock(LOCK_KEY, token) == RedisLock.UnlockResult.NOT_HELD) {
			log.warn(
				"빗썸 피드 리더 락 해제가 아무 것도 지우지 못했다(토큰 불일치 - TTL이 이미 만료돼 다른 "
					+ "인스턴스가 리더를 넘겨받았을 수 있다)");
		}
	}
}
