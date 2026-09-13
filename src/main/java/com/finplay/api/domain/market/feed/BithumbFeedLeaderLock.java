package com.finplay.api.domain.market.feed;

import com.finplay.api.global.lock.RedisLock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
		redisLock.unlock(LOCK_KEY, token);
	}
}
