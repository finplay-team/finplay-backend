package com.finplay.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.account.controller.AccountController;
import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.feedback.collector.DartCorpCodeRegistry;
import com.finplay.api.domain.feedback.service.FeedbackBatchService;
import com.finplay.api.domain.feedback.service.NewsSearchQueryBuilder;
import com.finplay.api.domain.feedback.service.NewsTitleFilter;
import com.finplay.api.domain.market.config.KisRestClientConfig;
import com.finplay.api.domain.market.controller.StockPriceSseController;
import com.finplay.api.domain.market.feed.BithumbFeedLifecycle;
import com.finplay.api.domain.market.service.KisDailyCandleClientImpl;
import com.finplay.api.domain.market.service.KisHistoricalCandleClientImpl;
import com.finplay.api.domain.market.service.KisHistoricalCandleCollector;
import com.finplay.api.domain.market.service.StockDailyCandleCollector;
import com.finplay.api.domain.market.service.StockPriceStreamService;
import com.finplay.api.domain.market.sse.SseEmitterRegistry;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.order.listener.LimitOrderTriggerListener;
import com.finplay.api.domain.order.service.LimitOrderFillService;
import com.finplay.api.domain.ranking.service.RankingRebuildService;
import com.finplay.api.global.config.SchedulingConfig;
import com.finplay.api.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "spring.data.redis.port=6379")
@ActiveProfiles({"prod", "scheduler"})
@Import(TestcontainersConfiguration.class)
class ProdSchedulerProfileContextIntegrationTest {

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	@DisplayName("prod,scheduler에서는 scheduling infrastructure와 Scheduler·수집 전용 Bean이 생성된다")
	void schedulerRoleCreatesSchedulingInfrastructureAndSchedulerBeans() {
		assertThat(applicationContext.getBeanNamesForType(SchedulingConfig.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(FeedbackBatchService.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(BithumbFeedLifecycle.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(RankingRebuildService.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(LimitOrderTriggerListener.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(KisRestClientConfig.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(KisHistoricalCandleClientImpl.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(KisDailyCandleClientImpl.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(DartCorpCodeRegistry.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(NewsSearchQueryBuilder.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(NewsTitleFilter.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(KisHistoricalCandleCollector.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(StockDailyCandleCollector.class)).hasSize(1);
		assertThat(applicationContext.containsBean("kisHistoricalCandleImportWriter")).isTrue();
		assertThat(applicationContext.containsBean("stockDailyCandleImportWriter")).isTrue();
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
		assertThat(applicationContext.getBeanNamesForType(StockPriceStreamService.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(SseEmitterRegistry.class)).hasSize(1);
	}
}
