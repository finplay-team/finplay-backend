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
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
	"spring.data.redis.port=6379",
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
class ProdWebNewsBatchContextIntegrationTest {

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	@DisplayName("prod,web에서는 뉴스·공시 수집과 배치 실행 Bean이 생성되지 않는다")
	void webRoleDoesNotCreateNewsAndBatchBeans() {
		assertThat(applicationContext.getBeanNamesForType(NewsCollectionService.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(FeedbackBatchService.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(CryptoFeedbackBatchService.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(PeerStatsBatchService.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(RankingRebuildService.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(FeedbackBatchConfig.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(FeedbackBatchProperties.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(FeedbackBatchLock.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(RankingRebuildConfig.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(RankingRebuildProperties.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(RankingRebuildLock.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(NewsApiPropertiesConfig.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(NaverSearchProperties.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(DartProperties.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(DartDisclosureCollector.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(NaverNewsCollector.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(DartCorpCodeRegistry.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(NewsSearchQueryBuilder.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(NewsTitleFilter.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(SchedulingConfig.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(ScheduledTaskHolder.class)).hasSize(1);
	}

	@Test
	@DisplayName("prod,web에서는 뉴스 조회에 필요한 공통 Bean을 유지하고 랭킹 기동 listener를 등록하지 않는다")
	void webRoleKeepsCommonNewsBeansAndExcludesRankingLifecycle() {
		assertThat(applicationContext.getBeanNamesForType(NewsCollectionPropertiesConfig.class)).hasSize(1);
		assertThat(applicationContext.getBeanNamesForType(FeedbackNewsProperties.class)).hasSize(1);
		assertThat(registeredEventListenerMethods())
			.doesNotContain("com.finplay.api.domain.ranking.service.RankingRebuildService.rebuildOnStartup");
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
