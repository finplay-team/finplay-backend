package com.finplay.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.account.controller.AccountController;
import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.feedback.collector.DartCorpCodeRegistry;
import com.finplay.api.domain.feedback.service.CryptoPriceMoveWatcher;
import com.finplay.api.domain.feedback.service.FeedbackBatchService;
import com.finplay.api.domain.feedback.service.NewsSearchQueryBuilder;
import com.finplay.api.domain.feedback.service.NewsTitleFilter;
import com.finplay.api.domain.market.config.BithumbFeedConfig;
import com.finplay.api.domain.market.config.KisProperties;
import com.finplay.api.domain.market.config.KisRestClientConfig;
import com.finplay.api.domain.market.controller.StockPriceSseController;
import com.finplay.api.domain.market.feed.BithumbFeedLeaderLock;
import com.finplay.api.domain.market.feed.BithumbFeedLifecycle;
import com.finplay.api.domain.market.feed.BithumbFeedStatusReconciler;
import com.finplay.api.domain.market.feed.BithumbRestTickerPoller;
import com.finplay.api.domain.market.feed.BithumbWebSocketFeedClient;
import com.finplay.api.domain.market.service.CryptoPriceSnapshotService;
import com.finplay.api.domain.market.service.KisDailyCandleClientImpl;
import com.finplay.api.domain.market.service.KisHistoricalCandleClientImpl;
import com.finplay.api.domain.market.service.KisHistoricalCandleCollector;
import com.finplay.api.domain.market.service.StockCollectionLock;
import com.finplay.api.domain.market.service.StockDailyCandleCollector;
import com.finplay.api.domain.market.service.StockPriceScheduler;
import com.finplay.api.domain.market.service.StockPriceStreamService;
import com.finplay.api.domain.market.service.StockReplaySessionLock;
import com.finplay.api.domain.market.service.StockReplaySessionScheduler;
import com.finplay.api.domain.market.sse.SseEmitterRegistry;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.market.transport.StockMarketEventPublisher;
import com.finplay.api.domain.market.transport.StockMarketEventSubscriber;
import com.finplay.api.domain.order.config.LimitOrderFillExecutorConfig;
import com.finplay.api.domain.order.listener.ExitPlanTriggerListener;
import com.finplay.api.domain.order.listener.LimitOrderTriggerListener;
import com.finplay.api.domain.order.service.ExitPlanFillService;
import com.finplay.api.domain.order.service.LimitOrderFillExecutorRouter;
import com.finplay.api.domain.order.service.LimitOrderFillService;
import com.finplay.api.domain.order.service.OrderRecoveryScanLock;
import com.finplay.api.domain.order.service.OrderRecoveryScanScheduler;
import com.finplay.api.domain.ranking.service.RankingRebuildService;
import com.finplay.api.global.config.SchedulingConfig;
import com.finplay.api.global.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.ApplicationListenerMethodAdapter;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
	"spring.data.redis.port=6379",
	"aws.region=us-east-1",
	"resend.api-key=test-resend-api-key",
	"email.from=no-reply@finplay.test",
	"finplay.community.image-storage.s3.bucket=test-bucket",
	"finplay.cors.allowed-origins=https://finplay.test",
	"oauth.kakao.client-id=test-kakao-client-id",
	"oauth.kakao.client-secret=test-kakao-client-secret",
	"oauth.kakao.redirect-uri=https://finplay.test/oauth/kakao/callback",
	"oauth.naver.client-id=test-naver-client-id",
	"oauth.naver.client-secret=test-naver-client-secret",
	"oauth.naver.redirect-uri=https://finplay.test/oauth/naver/callback"
})
@ActiveProfiles({"prod", "web"})
@Import(TestcontainersConfiguration.class)
class ProdWebProfileContextIntegrationTest {

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	@DisplayName("prod,web에서는 Web 전용 Bean과 공통 보류 Bean이 생성된다")
	void webRoleCreatesWebAndCommonBeans() {
		assertThat(applicationContext.getBeanNamesForType(AccountController.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(StockPriceSseController.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(SecurityConfig.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(SecurityFilterChain.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(GlobalExceptionHandler.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(PriceStore.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(LimitOrderFillService.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(ExitPlanFillService.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(StockPriceStreamService.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(SseEmitterRegistry.class)).hasSize(1);
	}

	@Test
	@DisplayName("prod,web에서는 Scheduler 업무는 등록되지 않고 Web heartbeat scheduling만 유지된다")
	void webRoleDoesNotCreateSchedulerWorkAndKeepsHeartbeatScheduling() {
		assertThat(applicationContext.getBeanNamesForType(SchedulingConfig.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(ScheduledTaskHolder.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(StockMarketEventSubscriber.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(StockMarketEventPublisher.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(StockPriceScheduler.class)).isEmpty();
		assertThat(registeredScheduledMethodNames())
			.contains(scheduledMethodName(SseEmitterRegistry.class, "sendHeartbeat"))
			.noneMatch(name -> name.contains(StockPriceScheduler.class.getName()));
		assertThat(applicationContext.getBeanNamesForType(FeedbackBatchService.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(BithumbFeedConfig.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(BithumbFeedLeaderLock.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(BithumbFeedLifecycle.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(BithumbFeedStatusReconciler.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(BithumbRestTickerPoller.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(BithumbWebSocketFeedClient.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(CryptoPriceSnapshotService.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(CryptoPriceMoveWatcher.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(RankingRebuildService.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(LimitOrderTriggerListener.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(ExitPlanTriggerListener.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(OrderRecoveryScanLock.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(OrderRecoveryScanScheduler.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(LimitOrderFillExecutorConfig.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(LimitOrderFillExecutorRouter.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(KisProperties.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(KisRestClientConfig.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(KisHistoricalCandleClientImpl.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(KisDailyCandleClientImpl.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(StockCollectionLock.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(StockReplaySessionLock.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(StockReplaySessionScheduler.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(DartCorpCodeRegistry.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(NewsSearchQueryBuilder.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(NewsTitleFilter.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(KisHistoricalCandleCollector.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(StockDailyCandleCollector.class)).isEmpty();
		assertThat(applicationContext.containsBean("kisHistoricalCandleImportWriter")).isFalse();
		assertThat(applicationContext.containsBean("stockDailyCandleImportWriter")).isFalse();
		assertThat(registeredEventListenerMethods())
			.doesNotContain(
				"com.finplay.api.domain.order.listener.LimitOrderTriggerListener.onPriceUpdated",
				"com.finplay.api.domain.order.listener.ExitPlanTriggerListener.onPriceUpdated",
				"com.finplay.api.domain.order.service.OrderRecoveryScanScheduler.scanOnStartup");
	}

	private List<String> registeredScheduledMethodNames() {
		return applicationContext.getBean(ScheduledTaskHolder.class).getScheduledTasks().stream()
			.map(ScheduledTask::toString)
			.toList();
	}

	private static String scheduledMethodName(Class<?> type, String methodName) {
		try {
			type.getMethod(methodName);
		} catch (NoSuchMethodException ex) {
			throw new IllegalStateException(ex);
		}
		return type.getName() + "." + methodName;
	}

	private List<String> registeredEventListenerMethods() {
		return ((AbstractApplicationContext)applicationContext).getApplicationListeners().stream()
			.filter(ApplicationListenerMethodAdapter.class::isInstance)
			.map(ApplicationListenerMethodAdapter.class::cast)
			.map(ApplicationListenerMethodAdapter::getTargetMethod)
			.map(method -> method.getDeclaringClass().getName() + "." + method.getName())
			.toList();
	}
}
