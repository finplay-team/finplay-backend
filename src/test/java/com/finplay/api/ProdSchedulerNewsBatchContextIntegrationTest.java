package com.finplay.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.feedback.collector.DartCorpCodeRegistry;
import com.finplay.api.domain.feedback.collector.DartDisclosureCollector;
import com.finplay.api.domain.feedback.collector.NaverNewsCollector;
import com.finplay.api.domain.feedback.config.DartProperties;
import com.finplay.api.domain.feedback.config.FeedbackBatchConfig;
import com.finplay.api.domain.feedback.config.FeedbackBatchProperties;
import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.config.NaverSearchProperties;
import com.finplay.api.domain.feedback.config.NewsApiPropertiesConfig;
import com.finplay.api.domain.feedback.config.NewsCollectionPropertiesConfig;
import com.finplay.api.domain.feedback.service.CryptoFeedbackBatchService;
import com.finplay.api.domain.feedback.service.FeedbackBatchLock;
import com.finplay.api.domain.feedback.service.FeedbackBatchService;
import com.finplay.api.domain.feedback.service.NewsCollectionService;
import com.finplay.api.domain.feedback.service.NewsSearchQueryBuilder;
import com.finplay.api.domain.feedback.service.NewsTitleFilter;
import com.finplay.api.domain.feedback.service.PeerStatsBatchService;
import com.finplay.api.domain.ranking.config.RankingRebuildConfig;
import com.finplay.api.domain.ranking.config.RankingRebuildProperties;
import com.finplay.api.domain.ranking.service.RankingRebuildLock;
import com.finplay.api.domain.ranking.service.RankingRebuildService;
import com.finplay.api.global.config.SchedulingConfig;
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
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
	"spring.data.redis.port=6379",
	"bithumb.feed.reconciler.enabled=true",
	"bithumb.feed.ticker.enabled=true",
	"feedback.news.collect-cron=0 0 0 31 12 *",
	"feedback.news.disclosure-cron=0 5 0 31 12 *",
	"feedback.batch.cron=0 10 0 31 12 *",
	"feedback.batch.crypto-cron=0 15 0 31 12 *",
	"feedback.batch.peer-stats-cron=0 20 0 31 12 *",
	"feedback.batch.crypto-peer-stats-cron=0 25 0 31 12 *",
	"feedback.batch.crypto-watch-cron=0 0 0 31 12 *",
	"ranking.rebuild.cron=0 30 0 31 12 *",
	"market.stock.retry-cron=0 0 0 31 12 *"
})
@ActiveProfiles({"prod", "scheduler"})
@Import(TestcontainersConfiguration.class)
class ProdSchedulerNewsBatchContextIntegrationTest {

	private static final List<String> NEWS_BATCH_SCHEDULES = List.of(
		scheduledMethodName(NewsCollectionService.class, "collectNews"),
		scheduledMethodName(NewsCollectionService.class, "collectDisclosures"),
		scheduledMethodName(FeedbackBatchService.class, "runPreMarketBatch"),
		scheduledMethodName(CryptoFeedbackBatchService.class, "refreshCryptoFeedback"),
		scheduledMethodName(PeerStatsBatchService.class, "runPeerStatsBatch"),
		scheduledMethodName(PeerStatsBatchService.class, "runCryptoPeerStatsBatch"),
		scheduledMethodName(RankingRebuildService.class, "rebuildOnSchedule"));

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private ScheduledTaskHolder scheduledTaskHolder;

	@Test
	@DisplayName("prod,scheduler에서는 뉴스·공시 수집과 배치 실행 Bean이 생성된다")
	void schedulerRoleCreatesNewsAndBatchBeans() {
		assertThat(applicationContext.getBeanNamesForType(SchedulingConfig.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(ScheduledTaskHolder.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(NewsCollectionService.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(FeedbackBatchService.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(CryptoFeedbackBatchService.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(PeerStatsBatchService.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(RankingRebuildService.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(FeedbackBatchConfig.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(FeedbackBatchProperties.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(FeedbackBatchLock.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(RankingRebuildConfig.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(RankingRebuildProperties.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(RankingRebuildLock.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(NewsApiPropertiesConfig.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(NaverSearchProperties.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(DartProperties.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(DartDisclosureCollector.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(NaverNewsCollector.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(DartCorpCodeRegistry.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(NewsSearchQueryBuilder.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(NewsTitleFilter.class)).hasSize(1);
	}

	@Test
	@DisplayName("prod,scheduler에서는 뉴스·공시·배치 scheduled task와 랭킹 기동 listener가 등록된다")
	void schedulerRoleRegistersNewsAndBatchLifecycle() {
		assertThat(registeredScheduledMethodNames())
			.as("뉴스·공시·피드백·집단 비교·랭킹 scheduled 메서드가 등록되어야 한다")
			.containsAll(NEWS_BATCH_SCHEDULES);
		assertThat(registeredEventListenerMethods())
			.contains("com.finplay.api.domain.ranking.service.RankingRebuildService.rebuildOnStartup");
	}

	@Test
	@DisplayName("prod,scheduler에서도 Web 조회에 필요한 뉴스 공통 Bean은 생성된다")
	void schedulerRoleKeepsCommonNewsBeans() {
		assertThat(applicationContext.getBeanNamesForType(NewsCollectionPropertiesConfig.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(FeedbackNewsProperties.class)).hasSize(1);
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
