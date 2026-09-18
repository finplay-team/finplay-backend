package com.finplay.api.domain.order.service;

import com.finplay.api.global.lock.RedisLock;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("prod & scheduler")
@RequiredArgsConstructor
public class OrderRecoveryScanLock {

	private static final String LOCK_KEY = "order:recovery-scan:lock";
	private static final Duration LOCK_TTL = Duration.ofSeconds(30);

	private final RedisLock redisLock;

	public Optional<String> tryLock() {
		return redisLock.tryLock(LOCK_KEY, LOCK_TTL);
	}

	public void unlock(String token) {
		if (redisLock.unlock(LOCK_KEY, token) == RedisLock.UnlockResult.NOT_HELD) {
			log.warn("주문 재검사 락 해제가 아무 것도 지우지 못했습니다(토큰 불일치 또는 TTL 만료)");
		}
	}
}
