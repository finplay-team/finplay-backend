package com.finplay.api.domain.market.feed;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

@ExtendWith(MockitoExtension.class)
class BithumbFeedLifecycleTest {

	@Mock
	private BithumbFeedClient bithumbFeedClient;

	@Mock
	private BithumbFeedLeaderLock bithumbFeedLeaderLock;

	@Test
	void electLeaderStartsTheClientWhenNotLeaderAndLockIsAcquired() {
		BithumbFeedLifecycle lifecycle = new BithumbFeedLifecycle(bithumbFeedClient, bithumbFeedLeaderLock, 10_000L);
		when(bithumbFeedLeaderLock.tryLock()).thenReturn(Optional.of("token-1"));

		lifecycle.electLeader();

		verify(bithumbFeedClient, times(1)).start();
		verify(bithumbFeedLeaderLock, never()).renew(any());
	}

	@Test
	void electLeaderDoesNothingWhenNotLeaderAndLockIsNotAcquired() {
		BithumbFeedLifecycle lifecycle = new BithumbFeedLifecycle(bithumbFeedClient, bithumbFeedLeaderLock, 10_000L);
		when(bithumbFeedLeaderLock.tryLock()).thenReturn(Optional.empty());

		lifecycle.electLeader();

		verifyNoMoreInteractions(bithumbFeedClient);
	}

	@Test
	void electLeaderDoesNotPropagateWhenClientStartFailsSoTheScheduleIsNotBroken() {
		BithumbFeedLifecycle lifecycle = new BithumbFeedLifecycle(bithumbFeedClient, bithumbFeedLeaderLock, 10_000L);
		when(bithumbFeedLeaderLock.tryLock()).thenReturn(Optional.of("token-1"));
		doThrow(new RedisConnectionFailureException("Unable to connect to Redis")).when(bithumbFeedClient).start();

		assertThatCode(lifecycle::electLeader).doesNotThrowAnyException();

		verify(bithumbFeedClient, times(1)).start();
	}

	@Test
	void electLeaderUnlocksAndStaysFollowerWhenClientStartFailsSoTheLockIsNotHeldByADeadLeader() {
		BithumbFeedLifecycle lifecycle = new BithumbFeedLifecycle(bithumbFeedClient, bithumbFeedLeaderLock, 10_000L);
		when(bithumbFeedLeaderLock.tryLock()).thenReturn(Optional.of("token-1"));
		doThrow(new RedisConnectionFailureException("Unable to connect to Redis")).when(bithumbFeedClient).start();

		lifecycle.electLeader();

		verify(bithumbFeedLeaderLock, times(1)).unlock("token-1");
		verify(bithumbFeedLeaderLock, never()).renew(any());
	}

	@Test
	void electLeaderTriesToBecomeLeaderAgainAfterClientStartFails() {
		BithumbFeedLifecycle lifecycle = new BithumbFeedLifecycle(bithumbFeedClient, bithumbFeedLeaderLock, 10_000L);
		when(bithumbFeedLeaderLock.tryLock()).thenReturn(Optional.of("token-1"), Optional.of("token-2"));
		doThrow(new RedisConnectionFailureException("Unable to connect to Redis")).doNothing()
			.when(bithumbFeedClient)
			.start();

		lifecycle.electLeader();
		lifecycle.electLeader();

		verify(bithumbFeedClient, times(2)).start();
		verify(bithumbFeedLeaderLock, times(1)).unlock("token-1");
	}

	@Test
	void electLeaderRenewsInsteadOfRestartingWhenAlreadyLeaderAndRenewSucceeds() {
		BithumbFeedLifecycle lifecycle = new BithumbFeedLifecycle(bithumbFeedClient, bithumbFeedLeaderLock, 10_000L);
		when(bithumbFeedLeaderLock.tryLock()).thenReturn(Optional.of("token-1"));
		when(bithumbFeedLeaderLock.renew("token-1")).thenReturn(true);

		lifecycle.electLeader();
		lifecycle.electLeader();

		verify(bithumbFeedClient, times(1)).start();
		verify(bithumbFeedLeaderLock, times(1)).renew("token-1");
		verify(bithumbFeedClient, never()).stepDown();
	}

	@Test
	void electLeaderStepsDownWithoutClaimingSharedStatusWhenAlreadyLeaderAndRenewFails() {
		BithumbFeedLifecycle lifecycle = new BithumbFeedLifecycle(bithumbFeedClient, bithumbFeedLeaderLock, 10_000L);
		when(bithumbFeedLeaderLock.tryLock()).thenReturn(Optional.of("token-1"));
		when(bithumbFeedLeaderLock.renew("token-1")).thenReturn(false);

		lifecycle.electLeader();
		lifecycle.electLeader();

		verify(bithumbFeedClient, times(1)).stepDown();
		verify(bithumbFeedClient, never()).stop();
	}

	@Test
	void electLeaderTriesToBecomeLeaderAgainAfterSteppingDown() {
		BithumbFeedLifecycle lifecycle = new BithumbFeedLifecycle(bithumbFeedClient, bithumbFeedLeaderLock, 10_000L);
		when(bithumbFeedLeaderLock.tryLock()).thenReturn(Optional.of("token-1"), Optional.of("token-2"));
		when(bithumbFeedLeaderLock.renew("token-1")).thenReturn(false);

		lifecycle.electLeader();
		lifecycle.electLeader();
		lifecycle.electLeader();

		verify(bithumbFeedClient, times(2)).start();
	}

	@Test
	void stopFeedStopsTheClientAndUnlocksTheLockWhenCurrentlyLeader() {
		BithumbFeedLifecycle lifecycle = new BithumbFeedLifecycle(bithumbFeedClient, bithumbFeedLeaderLock, 10_000L);
		when(bithumbFeedLeaderLock.tryLock()).thenReturn(Optional.of("token-1"));
		lifecycle.electLeader();

		lifecycle.stopFeed();

		verify(bithumbFeedClient, times(1)).stop();
		verify(bithumbFeedLeaderLock, times(1)).unlock("token-1");
	}

	@Test
	void validateLeaderScheduleConfigurationDoesNotThrowWhenTtlIsAtLeastTwiceTheElectionInterval() {
		when(bithumbFeedLeaderLock.lockTtlSeconds()).thenReturn(30L);
		BithumbFeedLifecycle lifecycle = new BithumbFeedLifecycle(bithumbFeedClient, bithumbFeedLeaderLock, 10_000L);

		assertThatCode(lifecycle::validateLeaderScheduleConfiguration).doesNotThrowAnyException();
	}

	@Test
	void validateLeaderScheduleConfigurationThrowsWhenTtlIsLessThanTwiceTheElectionInterval() {
		when(bithumbFeedLeaderLock.lockTtlSeconds()).thenReturn(15L);
		BithumbFeedLifecycle lifecycle = new BithumbFeedLifecycle(bithumbFeedClient, bithumbFeedLeaderLock, 10_000L);

		assertThatIllegalStateException().isThrownBy(lifecycle::validateLeaderScheduleConfiguration);
	}

	@Test
	void validateLeaderScheduleConfigurationThrowsWhenElectionIntervalIsNotPositive() {
		BithumbFeedLifecycle lifecycle = new BithumbFeedLifecycle(bithumbFeedClient, bithumbFeedLeaderLock, 0L);

		assertThatIllegalStateException().isThrownBy(lifecycle::validateLeaderScheduleConfiguration);
	}

	@Test
	void validateLeaderScheduleConfigurationThrowsWhenLockTtlIsNotPositive() {
		when(bithumbFeedLeaderLock.lockTtlSeconds()).thenReturn(0L);
		BithumbFeedLifecycle lifecycle = new BithumbFeedLifecycle(bithumbFeedClient, bithumbFeedLeaderLock, 10_000L);

		assertThatIllegalStateException().isThrownBy(lifecycle::validateLeaderScheduleConfiguration);
	}

	@Test
	void stopFeedDoesNothingWhenNeverBecameLeader() {
		BithumbFeedLifecycle lifecycle = new BithumbFeedLifecycle(bithumbFeedClient, bithumbFeedLeaderLock, 10_000L);

		lifecycle.stopFeed();

		verifyNoMoreInteractions(bithumbFeedClient);
		verifyNoMoreInteractions(bithumbFeedLeaderLock);
	}
}
