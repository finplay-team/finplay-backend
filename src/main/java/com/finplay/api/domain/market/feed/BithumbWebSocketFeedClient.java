package com.finplay.api.domain.market.feed;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.CryptoCandleStore;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@Profile("prod & scheduler")
@RequiredArgsConstructor
public class BithumbWebSocketFeedClient implements BithumbFeedClient {

	private static final URI BITHUMB_WS_URI = URI.create("wss://pubwss.bithumb.com/pub/ws");
	private static final String KRW_SUFFIX = "_KRW";
	private static final String SUBSCRIBE_TYPE_TICKER = "ticker";
	private static final String SUBSCRIBE_TYPE_TRANSACTION = "transaction";
	private static final List<String> SUBSCRIBE_TICK_TYPES = List.of("30M");
	private static final long RECONNECT_DELAY_MIN_SECONDS = 5;
	private static final long RECONNECT_DELAY_MAX_SECONDS = 60;

	private final InstrumentRepository instrumentRepository;
	private final PriceStore priceStore;
	private final CryptoCandleStore candleStore;
	private final BithumbFeedLeaderLock bithumbFeedLeaderLock;
	private final ObjectMapper objectMapper;
	private final StandardWebSocketClient webSocketClient;
	private final Supplier<ScheduledExecutorService> reconnectExecutorFactory;
	private final Clock clock;

	private final Object lifecycleLock = new Object();

	private volatile boolean running;
	private volatile ScheduledExecutorService reconnectExecutor;
	private volatile ConnectionSession activeConnection;
	private volatile String currentLeaderToken;

	@Override
	public void start(String leaderToken) {
		ConnectionSession newConnection;
		synchronized (lifecycleLock) {
			running = true;
			currentLeaderToken = leaderToken;
			reconnectExecutor = reconnectExecutorFactory.get();
			newConnection = new ConnectionSession();
			activeConnection = newConnection;
		}
		newConnection.connect();
	}

	@Override
	public void stop() {
		stopInternal();
	}

	@Override
	public void stepDown() {
		stopInternal();
	}

	private void stopInternal() {
		ConnectionSession current;
		ScheduledExecutorService executor;
		String token;
		synchronized (lifecycleLock) {
			running = false;
			current = activeConnection;
			activeConnection = null;
			executor = reconnectExecutor;
			token = currentLeaderToken;
			currentLeaderToken = null;
		}
		if (executor != null) {
			executor.shutdownNow();
		}
		if (current != null) {
			current.closeSessionQuietly();
		}
		if (token != null) {
			writeStatusUnlessSuperseded(token, FeedConnectionStatus.DISCONNECTED);
		}
	}

	@Override
	public boolean isConnected() {
		ConnectionSession current = activeConnection;
		return current != null && current.isConnected();
	}

	ConnectionSession currentHandler() {
		return activeConnection;
	}

	private void writeStatusUnlessSuperseded(String token, FeedConnectionStatus status) {
		try {
			boolean written = bithumbFeedLeaderLock.writeUnlessSuperseded(
				token, priceStore.connectionStatusKey(), status.name());
			if (!written) {
				log.info("빗썸 시세 피드 연결상태 기록을 건너뛴다 — 다른 인스턴스가 이미 리더를 넘겨받았다.");
			}
		} catch (Exception e) {
			log.warn("연결상태 기록 실패(Redis 장애로 추정) — 계속 진행합니다.", e);
		}
	}

	private void closeQuietly(WebSocketSession target, CloseStatus closeStatus) {
		if (target != null && target.isOpen()) {
			try {
				target.close(closeStatus);
			} catch (IOException ex) {
				log.warn("빗썸 WebSocket 종료 중 오류가 발생했습니다.", ex);
			}
		}
	}

	private record TickerSubscribeRequest(String type, List<String> symbols, List<String> tickTypes) {

		private TickerSubscribeRequest {
			symbols = List.copyOf(symbols);
			tickTypes = List.copyOf(tickTypes);
		}
	}

	private record TransactionSubscribeRequest(String type, List<String> symbols) {

		private TransactionSubscribeRequest {
			symbols = List.copyOf(symbols);
		}
	}

	final class ConnectionSession extends TextWebSocketHandler {

		private volatile WebSocketSession session;
		private volatile long reconnectDelaySeconds = RECONNECT_DELAY_MIN_SECONDS;

		private boolean isCurrent() {
			return activeConnection == this;
		}

		private boolean isConnected() {
			WebSocketSession currentSession = session;
			return currentSession != null && currentSession.isOpen();
		}

		private void closeSessionQuietly() {
			closeQuietly(session, CloseStatus.NORMAL);
			session = null;
		}

		private void connect() {
			if (!running || !isCurrent()) {
				return;
			}
			webSocketClient
				.execute(this, new WebSocketHttpHeaders(), BITHUMB_WS_URI)
				.exceptionally(ex -> {
					synchronized (lifecycleLock) {
						if (isCurrent()) {
							log.warn("빗썸 WebSocket 연결에 실패했습니다.", ex);
							onDisconnected();
							scheduleReconnect();
						}
					}
					return null;
				});
		}

		private void scheduleReconnect() {
			if (!isCurrent()) {
				return;
			}
			ScheduledExecutorService executor = reconnectExecutor;
			if (!running || executor == null || executor.isShutdown()) {
				return;
			}
			long delay = reconnectDelaySeconds;
			executor.schedule(this::connect, delay, TimeUnit.SECONDS);
			reconnectDelaySeconds = Math.min(delay * 2, RECONNECT_DELAY_MAX_SECONDS);
		}

		private void onDisconnected() {
			session = null;
			String token = currentLeaderToken;
			if (token != null) {
				writeStatusUnlessSuperseded(token, FeedConnectionStatus.DISCONNECTED);
			}
		}

		@Override
		public void afterConnectionEstablished(WebSocketSession newSession) {
			List<String> plainSymbols;
			try {
				plainSymbols = instrumentRepository
					.findByMarketAndTradableTrueAndTutorialSampleFalseOrderByIdAsc(Market.CRYPTO)
					.stream()
					.map(Instrument::getSymbol)
					.toList();
			} catch (Exception ex) {
				log.warn("빗썸 구독 대상 종목 조회에 실패해 연결을 재시도합니다.", ex);
				synchronized (lifecycleLock) {
					if (!isCurrent()) {
						closeQuietly(newSession, CloseStatus.NORMAL);
						return;
					}
					closeQuietly(newSession, CloseStatus.SERVER_ERROR);
					onDisconnected();
					scheduleReconnect();
				}
				return;
			}
			synchronized (lifecycleLock) {
				if (!isCurrent()) {
					closeQuietly(newSession, CloseStatus.NORMAL);
					return;
				}
				session = newSession;
				reconnectDelaySeconds = RECONNECT_DELAY_MIN_SECONDS;
				String token = currentLeaderToken;
				if (token != null) {
					writeStatusUnlessSuperseded(token, FeedConnectionStatus.CONNECTED);
				}
				log.info("빗썸 WebSocket 연결에 성공했습니다.");
				subscribe(newSession, plainSymbols);
			}
		}

		@Override
		public void handleTextMessage(WebSocketSession webSocketSession, TextMessage message) {
			synchronized (lifecycleLock) {
				if (!isCurrent()) {
					return;
				}
				String payload = message.getPayload();
				BithumbTickerMessageParser.parse(objectMapper, payload)
					.ifPresent(tick -> priceStore.saveTick(tick.symbol(), tick.price(), tick.receivedAt()));
				BithumbTransactionMessageParser.parse(objectMapper, payload).forEach(trade -> {
					candleStore.recordTrade(trade.symbol(), trade.tradedAt(), trade.price(), trade.quantity());
					priceStore.saveTick(trade.symbol(), trade.price(), trade.tradedAt());
				});
			}
		}

		@Override
		public void handleTransportError(WebSocketSession webSocketSession, Throwable exception) {
			log.warn("빗썸 WebSocket 전송 오류가 발생했습니다.", exception);
		}

		@Override
		public void afterConnectionClosed(WebSocketSession webSocketSession, CloseStatus closeStatus) {
			synchronized (lifecycleLock) {
				if (!isCurrent()) {
					return;
				}
				log.warn("빗썸 WebSocket 연결이 종료됐습니다 (status={}).", closeStatus);
				onDisconnected();
				scheduleReconnect();
			}
		}

		private void subscribe(WebSocketSession target, List<String> plainSymbols) {
			try {
				List<String> marketSymbols = plainSymbols.stream().map(symbol -> symbol + KRW_SUFFIX).toList();

				String tickerPayload = objectMapper.writeValueAsString(
					new TickerSubscribeRequest(SUBSCRIBE_TYPE_TICKER, marketSymbols, SUBSCRIBE_TICK_TYPES));
				target.sendMessage(new TextMessage(tickerPayload));

				String transactionPayload = objectMapper.writeValueAsString(
					new TransactionSubscribeRequest(SUBSCRIBE_TYPE_TRANSACTION, marketSymbols));
				target.sendMessage(new TextMessage(transactionPayload));

				LocalDateTime now = LocalDateTime.now(clock);
				plainSymbols.forEach(symbol -> candleStore.touchSince(symbol, now));
			} catch (Exception ex) {
				log.warn("빗썸 구독에 실패해 연결을 재시도합니다.", ex);
				closeQuietly(target, CloseStatus.SERVER_ERROR);
				onDisconnected();
				scheduleReconnect();
			}
		}
	}
}
