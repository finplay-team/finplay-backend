package com.finplay.api.domain.market.feed;

import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("prod")
public class BithumbFeedLifecycle {

	private final BithumbFeedClient bithumbFeedClient;

	private final BithumbFeedLeaderLock bithumbFeedLeaderLock;

	private final PriceStore priceStore;

	private final long electionIntervalMs;

	private String leaderToken;

	public BithumbFeedLifecycle(
		BithumbFeedClient bithumbFeedClient,
		BithumbFeedLeaderLock bithumbFeedLeaderLock,
		PriceStore priceStore,
		@Value("${bithumb.feed.leader.election-interval-ms:10000}")
		long electionIntervalMs) {
		this.bithumbFeedClient = bithumbFeedClient;
		this.bithumbFeedLeaderLock = bithumbFeedLeaderLock;
		this.priceStore = priceStore;
		this.electionIntervalMs = electionIntervalMs;
	}

	@PostConstruct
	public void validateLeaderScheduleConfiguration() {
		long lockTtlMs = bithumbFeedLeaderLock.lockTtlSeconds() * 1000;
		if (lockTtlMs <= 0 || electionIntervalMs <= 0) {
			throw new IllegalStateException(
				"bithumb.feed.leader.lock-ttl-seconds·election-interval-ms는 모두 양수여야 한다.");
		}
		if (lockTtlMs < electionIntervalMs * 2) {
			throw new IllegalStateException(
				"bithumb.feed.leader.lock-ttl-seconds(" + lockTtlMs + "ms)는 election-interval-ms("
					+ electionIntervalMs + "ms)의 2배 이상이어야 한다 — 그렇지 않으면 한 번의 갱신 지연만으로도 "
					+ "리더 자리가 TTL 만료로 넘어갈 수 있다.");
		}
	}

	@Scheduled(fixedRateString = "${bithumb.feed.leader.election-interval-ms:10000}")
	public synchronized void electLeader() {
		if (leaderToken == null) {
			tryBecomeLeader();
		} else {
			renewOrStepDown();
		}
	}

	@PreDestroy
	public synchronized void stopFeed() {
		String token = leaderToken;
		if (token == null) {
			return;
		}
		leaderToken = null;
		log.info("빗썸 시세 피드 종료 — 리더 락을 즉시 해제한다.");
		bithumbFeedClient.stop();
		bithumbFeedLeaderLock.unlock(token);
	}

	private void tryBecomeLeader() {
		Optional<String> token = bithumbFeedLeaderLock.tryLock();
		if (token.isEmpty()) {
			return;
		}
		String acquiredToken = token.get();
		try {
			bithumbFeedClient.start();
			leaderToken = acquiredToken;
			log.info("빗썸 시세 피드 리더로 선출됐다 — 연결을 시작했다.");
		} catch (Exception e) {
			log.error("빗썸 시세 피드 시작 실패 — 리더 자리를 내려놓는다.", e);
			bithumbFeedLeaderLock.unlock(acquiredToken);
		}
	}

	private void renewOrStepDown() {
		if (bithumbFeedLeaderLock.renew(leaderToken)) {
			return;
		}
		log.warn("빗썸 시세 피드 리더 갱신 실패 — 다른 인스턴스로 넘어가 팔로워로 전환한다.");
		bithumbFeedClient.stepDown();
		markDisconnectedUnlessSupersededBy(leaderToken);
		leaderToken = null;
	}

	private void markDisconnectedUnlessSupersededBy(String token) {
		boolean written = bithumbFeedLeaderLock.writeUnlessSuperseded(
			token, priceStore.connectionStatusKey(), FeedConnectionStatus.DISCONNECTED.name());
		if (!written) {
			log.info("빗썸 시세 피드 연결상태 기록을 건너뛴다 — 다른 인스턴스가 이미 리더를 넘겨받았다.");
		}
	}
}
