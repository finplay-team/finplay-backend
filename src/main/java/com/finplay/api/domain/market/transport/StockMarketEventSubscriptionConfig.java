package com.finplay.api.domain.market.transport;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration(proxyBeanMethods = false)
@Profile("prod & web")
@RequiredArgsConstructor
public class StockMarketEventSubscriptionConfig {

	private final RedisConnectionFactory redisConnectionFactory;
	private final StockMarketEventSubscriber subscriber;

	@Bean
	RedisMessageListenerContainer stockMarketEventListenerContainer() {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(redisConnectionFactory);
		container.addMessageListener(subscriber, new ChannelTopic(StockMarketEventPublisher.CHANNEL));
		return container;
	}
}
