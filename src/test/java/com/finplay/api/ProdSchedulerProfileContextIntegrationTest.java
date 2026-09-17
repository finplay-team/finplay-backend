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
import com.finplay.api.domain.market.service.StockPriceStreamService;
import com.finplay.api.domain.market.service.StockReplaySessionLock;
import com.finplay.api.domain.market.service.StockReplaySessionScheduler;
import com.finplay.api.domain.market.sse.SseEmitterRegistry;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.order.config.LimitOrderFillExecutorConfig;
import com.finplay.api.domain.order.listener.ExitPlanTriggerListener;
import com.finplay.api.domain.order.listener.LimitOrderTriggerListener;
import com.finplay.api.domain.order.service.ExitPlanFillService;
import com.finplay.api.domain.order.service.LimitOrderFillExecutorRouter;
import com.finplay.api.domain.order.service.LimitOrderFillService;
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
	"bithumb.feed.reconciler.enabled=true",
	"bithumb.feed.ticker.enabled=true",
	"feedback.batch.crypto-watch-cron=0 0 0 31 12 *",
	"market.stock.retry-cron=0 0 0 31 12 *"
})
@ActiveProfiles({"prod", "scheduler"})
@Import(TestcontainersConfiguration.class)
class ProdSchedulerProfileContextIntegrationTest {

	private static final List<String> SCHEDULER_SCHEDULES = List.of(
		scheduledMethodName(BithumbFeedLifecycle.class, "electLeader"),
		scheduledMethodName(BithumbFeedStatusReconciler.class, "reconcileConnectionStatus"),
		scheduledMethodName(BithumbRestTickerPoller.class, "pollTickers"),
		scheduledMethodName(CryptoPriceSnapshotService.class, "recordSnapshots"),
		scheduledMethodName(CryptoPriceMoveWatcher.class, "watch"),
		scheduledMethodName(KisHistoricalCandleCollector.class, "collect"),
		scheduledMethodName(KisHistoricalCandleCollector.class, "retryPendingInstruments"),
		scheduledMethodName(StockDailyCandleCollector.class, "collect"),
		scheduledMethodName(StockReplaySessionScheduler.class, "resolveTodaySession"),
		scheduledMethodName(StockPriceStreamService.class, "publishScheduledUpdates"),
		scheduledMethodName(SseEmitterRegistry.class, "sendHeartbeat"));

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private ScheduledTaskHolder scheduledTaskHolder;

	@Test
	@DisplayName("prod,scheduler에서는 scheduling infrastructure와 Scheduler·수집 전용 Bean이 생성된다")
	void schedulerRoleCreatesSchedulingInfrastructureAndSchedulerBeans() {
		assertThat(applicationContext.getBeanNamesForType(SchedulingConfig.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(ScheduledTaskHolder.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(FeedbackBatchService.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(BithumbFeedConfig.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(BithumbFeedLeaderLock.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(BithumbFeedLifecycle.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(BithumbFeedStatusReconciler.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(BithumbRestTickerPoller.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(BithumbWebSocketFeedClient.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(CryptoPriceSnapshotService.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(CryptoPriceMoveWatcher.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(RankingRebuildService.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(LimitOrderTriggerListener.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(ExitPlanTriggerListener.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(LimitOrderFillExecutorConfig.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(LimitOrderFillExecutorRouter.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(KisProperties.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(KisRestClientConfig.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(KisHistoricalCandleClientImpl.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(KisDailyCandleClientImpl.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(StockCollectionLock.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(StockReplaySessionLock.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(StockReplaySessionScheduler.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(DartCorpCodeRegistry.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(NewsSearchQueryBuilder.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(NewsTitleFilter.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(KisHistoricalCandleCollector.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(StockDailyCandleCollector.class)).hasSize(1);
		assertThat(applicationContext.containsBean("kisHistoricalCandleImportWriter")).isTrue();
		assertThat(applicationContext.containsBean("stockDailyCandleImportWriter")).isTrue();
		assertThat(registeredScheduledMethodNames())
			.as("시세·KIS·공통 scheduled 메서드가 Scheduler 컨텍스트에 실제 등록되어야 한다")
			.containsAll(SCHEDULER_SCHEDULES);
		assertThat(registeredEventListenerMethods())
			.contains(
				"com.finplay.api.domain.order.listener.LimitOrderTriggerListener.onPriceUpdated",
				"com.finplay.api.domain.order.listener.ExitPlanTriggerListener.onPriceUpdated");
	}

	@Test
	@DisplayName("prod,scheduler에서는 Web 전용 Bean이 생성되지 않고 공통 보류 Bean은 생성된다")
	void schedulerRoleExcludesWebBeansAndKeepsCommonBeans() {
		assertThat(applicationContext.getBeanNamesForType(AccountController.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(StockPriceSseController.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(SecurityConfig.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(SecurityFilterChain.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(GlobalExceptionHandler.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(PriceStore.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(LimitOrderFillService.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(ExitPlanFillService.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(StockPriceStreamService.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(SseEmitterRegistry.class)).hasSize(1);
	}

	private List<String> registeredEventListenerMethods() {
		return ((AbstractApplicationContext)applicationContext).getApplicationListeners().stream()
			.filter(ApplicationListenerMethodAdapter.class::isInstance)
			.map(ApplicationListenerMethodAdapter.class::cast)
			.map(ApplicationListenerMethodAdapter::getTargetMethod)
			.map(method -> method.getDeclaringClass().getName() + "." + method.getName())
			.toList();
	}

	private List<String> registeredScheduledMethodNames() {
		return scheduledTaskHolder.getScheduledTasks().stream()
			.map(ScheduledTask::toString)
			.toList();
	}

	private static String scheduledMethodName(Class<?> type, String methodName) {
		try {
			type.getMethod(methodName);
		} catch (NoSuchMethodException ex) {
			throw new IllegalStateException(
				type.getSimpleName() + "." + methodName + "이 없다 — 스케줄 메서드 이름이 바뀌었다", ex);
		}
		return type.getName() + "." + methodName;
	}
}
