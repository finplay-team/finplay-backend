package com.finplay.api.global.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Profile("prod & scheduler")
@Slf4j
public class SchedulerReadinessMarker {

	private static final String MARKER_FILE_NAME = "finplay-scheduler-ready";

	private final Path markerPath;

	public SchedulerReadinessMarker() {
		this(Path.of(System.getProperty("java.io.tmpdir"), MARKER_FILE_NAME));
	}

	SchedulerReadinessMarker(Path markerPath) {
		this.markerPath = markerPath;
	}

	@PostConstruct
	void removeStaleMarker() {
		deleteMarker("stale readiness marker 삭제");
	}

	@EventListener(ApplicationReadyEvent.class)
	public void markReady() {
		try {
			Files.createFile(markerPath);
			log.info("Scheduler readiness marker 생성 완료");
		} catch (IOException | SecurityException e) {
			log.warn("Scheduler readiness marker 생성 실패. Scheduler 업무는 계속합니다.", e);
		}
	}

	@PreDestroy
	void removeMarkerOnShutdown() {
		deleteMarker("shutdown readiness marker 삭제");
	}

	private void deleteMarker(String operation) {
		try {
			Files.deleteIfExists(markerPath);
			log.debug("Scheduler {} 완료", operation);
		} catch (IOException | SecurityException e) {
			log.warn("Scheduler {} 실패. Scheduler 업무는 계속합니다.", operation, e);
		}
	}
}
