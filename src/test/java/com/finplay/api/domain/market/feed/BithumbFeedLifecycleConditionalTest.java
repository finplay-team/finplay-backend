package com.finplay.api.domain.market.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BithumbFeedLifecycleConditionalTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withBean(BithumbFeedClient.class, () -> mock(BithumbFeedClient.class))
		.withBean(BithumbFeedLeaderLock.class, BithumbFeedLifecycleConditionalTest::mockLeaderLockWithValidTtl)
		.withUserConfiguration(BithumbFeedLifecycle.class, BithumbFeedImmediateLifecycle.class);

	private static BithumbFeedLeaderLock mockLeaderLockWithValidTtl() {
		BithumbFeedLeaderLock leaderLock = mock(BithumbFeedLeaderLock.class);
		when(leaderLock.lockTtlSeconds()).thenReturn(30L);
		return leaderLock;
	}

	@Test
	@DisplayName("prod 프로필이면 리더 선출 클래스만 뜨고 즉시시작 클래스는 뜨지 않는다")
	void onlyLeaderElectionLifecycleExistsOnProdProfile() {
		contextRunner.withSystemProperties("spring.profiles.active=prod").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(BithumbFeedLifecycle.class);
			assertThat(context).doesNotHaveBean(BithumbFeedImmediateLifecycle.class);
		});
	}

	@Test
	@DisplayName("프로필을 지정하지 않으면(기본) 즉시시작 클래스만 뜨고 리더 선출 클래스는 뜨지 않는다")
	void onlyImmediateLifecycleExistsOnDefaultProfile() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(BithumbFeedImmediateLifecycle.class);
			assertThat(context).doesNotHaveBean(BithumbFeedLifecycle.class);
		});
	}

	@Test
	@DisplayName("crypto-real처럼 prod가 아닌 다른 프로필에서도 즉시시작 클래스만 뜬다")
	void onlyImmediateLifecycleExistsOnNonProdProfile() {
		contextRunner.withSystemProperties("spring.profiles.active=crypto-real").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(BithumbFeedImmediateLifecycle.class);
			assertThat(context).doesNotHaveBean(BithumbFeedLifecycle.class);
		});
	}
}
