package com.finplay.api.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class SchedulingConfigProfileTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(SchedulingConfig.class);

	@Test
	@DisplayName("prod,web에서는 SchedulingConfig와 ScheduledAnnotationBeanPostProcessor가 생성되지 않는다")
	void webRoleDoesNotEnableSchedulingInfrastructure() {
		contextRunner
			.withSystemProperties("spring.profiles.active=prod,web")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(SchedulingConfig.class);
				assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
			});
	}

	@Test
	@DisplayName("prod,scheduler에서는 SchedulingConfig와 ScheduledAnnotationBeanPostProcessor가 생성된다")
	void schedulerRoleEnablesSchedulingInfrastructure() {
		contextRunner
			.withSystemProperties("spring.profiles.active=prod,scheduler")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(SchedulingConfig.class);
				assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
			});
	}

	@ParameterizedTest(name = "{0} 프로필")
	@MethodSource("nonOperatingProfiles")
	@DisplayName("비운영 프로필에서는 기존 scheduling infrastructure가 유지된다")
	void nonOperatingProfileKeepsSchedulingInfrastructure(String profile) {
		contextRunner
			.withSystemProperties("spring.profiles.active=" + profile)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(SchedulingConfig.class);
				assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
			});
	}

	private static Stream<Arguments> nonOperatingProfiles() {
		return Stream.of(Arguments.of("local"), Arguments.of("test"));
	}
}
