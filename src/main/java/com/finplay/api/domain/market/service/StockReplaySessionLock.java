package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.config.MarketStockProperties;
import com.finplay.api.global.lock.RedisLock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockReplaySessionLock {

	private static final String KEY_PREFIX = "market:stock-replay-session:lock:";

	private final RedisLock redisLock;

	private final MarketStockProperties stockProperties;

	public Optional<String> tryLock(LocalDate serviceDate) {
		return redisLock.tryLock(
			lockKey(serviceDate), Duration.ofSeconds(stockProperties.replaySessionLockTtlSeconds()));
	}

	public void unlock(LocalDate serviceDate, String token) {
		if (redisLock.unlock(lockKey(serviceDate), token) == RedisLock.UnlockResult.NOT_HELD) {
			log.warn(
				"주식 재생세션 락 해제가 아무 것도 지우지 못했습니다(토큰 불일치 - TTL이 이미 만료돼 다른 "
					+ "인스턴스가 락을 새로 잡았을 수 있음) - serviceDate={}",
				serviceDate);
		}
	}

	private String lockKey(LocalDate serviceDate) {
		return KEY_PREFIX + serviceDate;
	}
}
