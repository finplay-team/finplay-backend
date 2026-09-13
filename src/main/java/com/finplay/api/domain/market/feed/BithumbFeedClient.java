package com.finplay.api.domain.market.feed;

public interface BithumbFeedClient {

	void start();

	void stop();

	void stepDown();

	boolean isConnected();
}
