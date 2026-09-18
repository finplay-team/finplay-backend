package com.finplay.api.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

class SchedulerReadinessMarkerTest {

	@Test
	@DisplayName("ApplicationReadyEvent 후 readiness marker를 생성하도록 등록된다")
	void listensToApplicationReadyEvent() throws NoSuchMethodException {
		EventListener listener = SchedulerReadinessMarker.class.getMethod("markReady")
			.getAnnotation(EventListener.class);

		assertThat(listener).isNotNull();
		assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
	}

	@Test
	void removesStaleMarkerOnInitializationAndOnShutdown() throws IOException {
		Path tempDirectory = Files.createTempDirectory("scheduler-readiness-test");
		Path markerPath = tempDirectory.resolve("ready");
		Files.createFile(markerPath);
		SchedulerReadinessMarker marker = new SchedulerReadinessMarker(markerPath);

		marker.removeStaleMarker();
		assertThat(markerPath).doesNotExist();

		marker.markReady();
		assertThat(markerPath).isRegularFile();

		marker.removeMarkerOnShutdown();
		assertThat(markerPath).doesNotExist();
	}

	@Test
	void isolatesMarkerCreationFailure() throws IOException {
		Path markerPath = Files.createTempDirectory("scheduler-readiness-failure").resolve("missing").resolve("ready");
		SchedulerReadinessMarker marker = new SchedulerReadinessMarker(markerPath);

		marker.removeStaleMarker();
		marker.markReady();
		marker.removeMarkerOnShutdown();

		assertThat(markerPath).doesNotExist();
	}
}
