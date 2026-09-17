package com.finplay.api.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

class OperationalProfilePropertiesTest {

	private static final String MAXIMUM_POOL_SIZE = "spring.datasource.hikari.maximum-pool-size";
	private static final String MINIMUM_IDLE = "spring.datasource.hikari.minimum-idle";
	private static final String SCHEDULING_POOL_SIZE = "spring.task.scheduling.pool.size";

	private static final int WEB_MAXIMUM_POOL_SIZE = 20;
	private static final int WEB_MINIMUM_IDLE = 4;
	private static final int WEB_SCHEDULING_POOL_SIZE = 2;
	private static final int SCHEDULER_MAXIMUM_POOL_SIZE = 10;
	private static final int SCHEDULER_MINIMUM_IDLE = 2;
	private static final int SCHEDULER_SCHEDULING_POOL_SIZE = 18;

	@Test
	@DisplayName("prod,web 프로필의 effective pool 설정이 Web 운영값으로 적용된다")
	void prodWebProfileUsesWebPoolSettings() {
		runWithProfile("prod,web", environment -> assertPoolSettings(
			environment,
			WEB_MAXIMUM_POOL_SIZE,
			WEB_MINIMUM_IDLE,
			WEB_SCHEDULING_POOL_SIZE));
	}

	@Test
	@DisplayName("prod,scheduler 프로필의 effective pool 설정이 Scheduler 운영값으로 적용된다")
	void prodSchedulerProfileUsesSchedulerPoolSettings() {
		runWithProfile("prod,scheduler", environment -> assertPoolSettings(
			environment,
			SCHEDULER_MAXIMUM_POOL_SIZE,
			SCHEDULER_MINIMUM_IDLE,
			SCHEDULER_SCHEDULING_POOL_SIZE));
	}

	private void runWithProfile(String activeProfiles, Consumer<Environment> assertions) {
		String previousMinimumIdle = System.getProperty(MINIMUM_IDLE);
		System.clearProperty(MINIMUM_IDLE);

		try {
			new ApplicationContextRunner()
				.withSystemProperties(
					"spring.profiles.active=" + activeProfiles,
					"spring.config.additional-location=")
				.withInitializer(new ConfigDataApplicationContextInitializer())
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertions.accept(context.getEnvironment());
				});
		} finally {
			if (previousMinimumIdle == null) {
				System.clearProperty(MINIMUM_IDLE);
			} else {
				System.setProperty(MINIMUM_IDLE, previousMinimumIdle);
			}
		}
	}

	private void assertPoolSettings(
		Environment environment,
		int maximumPoolSize,
		int minimumIdle,
		int schedulingPoolSize) {
		assertThat(environment.getProperty(MAXIMUM_POOL_SIZE, Integer.class)).isEqualTo(maximumPoolSize);
		assertThat(environment.getProperty(MINIMUM_IDLE, Integer.class)).isEqualTo(minimumIdle);
		assertThat(environment.getProperty(SCHEDULING_POOL_SIZE, Integer.class)).isEqualTo(schedulingPoolSize);
	}
}
