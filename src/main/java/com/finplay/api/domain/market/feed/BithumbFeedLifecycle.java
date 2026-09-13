package com.finplay.api.domain.market.feed;

import jakarta.annotation.PreDestroy;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("prod")
@RequiredArgsConstructor
public class BithumbFeedLifecycle {

	private final BithumbFeedClient bithumbFeedClient;

	private final BithumbFeedLeaderLock bithumbFeedLeaderLock;

	private volatile String leaderToken;

	@Scheduled(fixedRateString = "${bithumb.feed.leader.election-interval-ms:10000}")
	public void electLeader() {
		if (leaderToken == null) {
			tryBecomeLeader();
		} else {
			renewOrStepDown();
		}
	}

	@PreDestroy
	public void stopFeed() {
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
		leaderToken = token.get();
		log.info("빗썸 시세 피드 리더로 선출됐다 — 연결을 시작한다.");
		try {
			bithumbFeedClient.start();
		} catch (Exception e) {
			log.error("빗썸 시세 피드 시작 실패 — 시세 기능만 저하된 상태로 기동을 계속합니다.", e);
		}
	}

	private void renewOrStepDown() {
		if (bithumbFeedLeaderLock.renew(leaderToken)) {
			return;
		}
		log.warn("빗썸 시세 피드 리더 갱신 실패 — 다른 인스턴스로 넘어가 팔로워로 전환한다.");
		bithumbFeedClient.stop();
		leaderToken = null;
	}
}
