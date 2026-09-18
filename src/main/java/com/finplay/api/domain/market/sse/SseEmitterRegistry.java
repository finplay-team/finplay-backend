package com.finplay.api.domain.market.sse;

import com.finplay.api.domain.market.entity.Market;
import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Component
@Profile("!prod | web")
public class SseEmitterRegistry {

	private static final long RETRY_MILLIS = 3000L;
	private static final long HEARTBEAT_INTERVAL_MILLIS = 20_000L;
	private static final String HEARTBEAT_COMMENT = "heartbeat";

	private final Map<Market, CopyOnWriteArrayList<SseEmitter>> emittersByMarket;

	public SseEmitterRegistry() {
		Map<Market, CopyOnWriteArrayList<SseEmitter>> initial = new EnumMap<>(Market.class);
		for (Market market : Market.values()) {
			initial.put(market, new CopyOnWriteArrayList<>());
		}
		this.emittersByMarket = initial;
	}

	public SseEmitter register(Market market) {
		SseEmitter emitter = createEmitter(market);
		activate(market, emitter);
		return emitter;
	}

	public SseEmitter createEmitter(Market market) {
		SseEmitter emitter = new SseEmitter();
		CopyOnWriteArrayList<SseEmitter> emitters = emittersByMarket.get(market);

		emitter.onCompletion(() -> emitters.remove(emitter));
		emitter.onTimeout(() -> emitters.remove(emitter));
		emitter.onError(throwable -> emitters.remove(emitter));

		sendRetryHint(emitter, emitters);
		return emitter;
	}

	public void activate(Market market, SseEmitter emitter) {
		emittersByMarket.get(market).add(emitter);
	}

	public List<SseEmitter> getEmitters(Market market) {
		return Collections.unmodifiableList(emittersByMarket.get(market));
	}

	public void broadcast(Market market, SseEmitter.SseEventBuilder eventBuilder) {
		for (SseEmitter emitter : getEmitters(market)) {
			send(emitter, eventBuilder, emittersByMarket.get(market));
		}
	}

	@Scheduled(fixedRate = HEARTBEAT_INTERVAL_MILLIS)
	public void sendHeartbeat() {
		for (Map.Entry<Market, CopyOnWriteArrayList<SseEmitter>> entry : emittersByMarket.entrySet()) {
			CopyOnWriteArrayList<SseEmitter> emitters = entry.getValue();
			for (SseEmitter emitter : emitters) {
				try {
					emitter.send(SseEmitter.event().comment(HEARTBEAT_COMMENT));
				} catch (IOException | RuntimeException e) {
					log.debug("heartbeat 전송 실패로 emitter 정리: market={}", entry.getKey(), e);
					removeAndComplete(emitter, emitters, e);
				}
			}
		}
	}

	private void sendRetryHint(SseEmitter emitter, CopyOnWriteArrayList<SseEmitter> emitters) {
		try {
			emitter.send(SseEmitter.event().reconnectTime(RETRY_MILLIS));
		} catch (IOException | RuntimeException e) {
			log.debug("retry 힌트 전송 실패로 emitter 정리", e);
			removeAndComplete(emitter, emitters, e);
		}
	}

	private void send(SseEmitter emitter, SseEmitter.SseEventBuilder eventBuilder,
		List<SseEmitter> emitters) {
		try {
			emitter.send(eventBuilder);
		} catch (IOException | RuntimeException e) {
			log.debug("SSE 이벤트 전송 실패로 emitter 종료", e);
			removeAndComplete(emitter, emitters, e);
		}
	}

	private void removeAndComplete(SseEmitter emitter, List<SseEmitter> emitters, Throwable cause) {
		emitters.remove(emitter);
		try {
			emitter.completeWithError(cause);
		} catch (RuntimeException completionFailure) {
			log.debug("emitter 종료 실패", completionFailure);
		}
	}
}
