package com.finplay.api.domain.market.feed;

public interface BithumbFeedClient {

	void start(String leaderToken);

	void stop();

	void stepDown();

	boolean isConnected();
}
