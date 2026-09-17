package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.config.FeedbackBatchProperties;
import com.finplay.api.global.lock.RedisLock;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!prod | (prod & scheduler)")
@RequiredArgsConstructor
public class FeedbackBatchLock {

	private static final String KEY_PREFIX = "feedback:batch:lock:";

	static final String SCHEDULED_SCOPE = "scheduled";

	private final RedisLock redisLock;

	private final FeedbackBatchProperties batchProperties;

	public Optional<String> tryLock(Batch batch, String scope) {
		return redisLock.tryLock(
			lockKey(batch, scope), Duration.ofSeconds(batchProperties.lockTtlSeconds()));
	}

	public void unlock(Batch batch, String scope, String token) {
		if (redisLock.unlock(lockKey(batch, scope), token) == RedisLock.UnlockResult.NOT_HELD) {
			log.warn(
				"피드백 배치 락 해제가 아무 것도 지우지 못했다(토큰 불일치 - TTL이 이미 만료돼 다른 인스턴스가 락을 새로 잡았을 수 있다) - batch={} scope={}",
				batch, scope);
		}
	}

	private String lockKey(Batch batch, String scope) {
		return KEY_PREFIX + batch.key() + ":" + scope;
	}

	public enum Batch {
		NEWS_COLLECTION("news-collection"),
		DISCLOSURE_COLLECTION("disclosure-collection"),
		PRE_MARKET("pre-market"),
		PEER_STATS("peer-stats"),
		CRYPTO_FEEDBACK("crypto-feedback"),
		CRYPTO_PEER_STATS("crypto-peer-stats");

		private final String key;

		Batch(String key) {
			this.key = key;
		}

		private String key() {
			return key;
		}
	}
}
