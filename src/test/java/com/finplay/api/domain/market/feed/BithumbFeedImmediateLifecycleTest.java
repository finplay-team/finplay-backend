package com.finplay.api.domain.market.feed;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

@ExtendWith(MockitoExtension.class)
class BithumbFeedImmediateLifecycleTest {

	@Mock
	private BithumbFeedClient bithumbFeedClient;

	@Test
	void startFeedCallsClientStartOnce() {
		BithumbFeedImmediateLifecycle lifecycle = new BithumbFeedImmediateLifecycle(bithumbFeedClient);

		lifecycle.startFeed();

		verify(bithumbFeedClient, times(1)).start();
		verifyNoMoreInteractions(bithumbFeedClient);
	}

	@Test
	void startFeedDoesNotPropagateWhenClientStartFailsSoApplicationStartupIsNotBlocked() {
		BithumbFeedImmediateLifecycle lifecycle = new BithumbFeedImmediateLifecycle(bithumbFeedClient);
		doThrow(new RedisConnectionFailureException("Unable to connect to Redis"))
			.when(bithumbFeedClient)
			.start();

		assertThatCode(lifecycle::startFeed).doesNotThrowAnyException();

		verify(bithumbFeedClient, times(1)).start();
	}

	@Test
	void stopFeedCallsClientStopOnceOnPreDestroy() {
		BithumbFeedImmediateLifecycle lifecycle = new BithumbFeedImmediateLifecycle(bithumbFeedClient);

		lifecycle.stopFeed();

		verify(bithumbFeedClient, times(1)).stop();
		verifyNoMoreInteractions(bithumbFeedClient);
	}
}
