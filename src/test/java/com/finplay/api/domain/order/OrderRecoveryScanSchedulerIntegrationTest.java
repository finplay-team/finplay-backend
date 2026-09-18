package com.finplay.api.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.order.config.LimitOrderFillExecutorProperties;
import com.finplay.api.domain.order.dto.request.ExitPlanCreateRequest;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.ExitPlanResponse;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.dto.response.OrderResponse;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanConditionStatus;
import com.finplay.api.domain.order.entity.ExitPlanConditionType;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.entity.ExitPriceType;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.listener.ExitPlanTriggerListener;
import com.finplay.api.domain.order.listener.LimitOrderTriggerListener;
import com.finplay.api.domain.order.repository.ExitPlanConditionRepository;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.order.service.ExitPlanFillService;
import com.finplay.api.domain.order.service.ExitPlanService;
import com.finplay.api.domain.order.service.LimitOrderFillExecutorRouter;
import com.finplay.api.domain.order.service.LimitOrderFillService;
import com.finplay.api.domain.order.service.LimitOrderService;
import com.finplay.api.domain.order.service.OrderRecoveryScanLock;
import com.finplay.api.domain.order.service.OrderRecoveryScanScheduler;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.global.lock.RedisLock;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
	"spring.data.redis.port=6379",
	"aws.region=us-east-1",
	"resend.api-key=test-resend-api-key",
	"email.from=no-reply@finplay.test",
	"finplay.community.image-storage.s3.bucket=test-bucket",
	"finplay.cors.allowed-origins=https://finplay.test",
	"oauth.kakao.client-id=test-kakao-client-id",
	"oauth.kakao.client-secret=test-kakao-client-secret",
	"oauth.kakao.redirect-uri=https://finplay.test/oauth/kakao/callback",
	"oauth.naver.client-id=test-naver-client-id",
	"oauth.naver.client-secret=test-naver-client-secret",
	"oauth.naver.redirect-uri=https://finplay.test/oauth/naver/callback",
	"order.limit-fill-executor.enabled=false"
})
@ActiveProfiles({"prod", "web"})
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class OrderRecoveryScanSchedulerIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 18, 10, 0);
	private static final BigDecimal BASE_PRICE = new BigDecimal("100000");
	private static final BigDecimal TRIGGER_PRICE = new BigDecimal("110000");
	private static final BigDecimal STOP_LOSS_PRICE = new BigDecimal("90000");
	private static final BigDecimal LIMIT_QUANTITY = new BigDecimal("0.1");
	private static final BigDecimal EXIT_QUANTITY = new BigDecimal("1");
	private static final String CONNECTION_STATUS_KEY = "feed:crypto:status";
	private static final String RECOVERY_LOCK_KEY = "order:recovery-scan:lock";

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private ExitPlanRepository exitPlanRepository;

	@Autowired
	private ExitPlanConditionRepository exitPlanConditionRepository;

	@Autowired
	private OrderService orderService;

	@Autowired
	private LimitOrderService limitOrderService;

	@Autowired
	private ExitPlanService exitPlanService;

	@Autowired
	private InstrumentService instrumentService;

	@Autowired
	private LimitOrderFillService limitOrderFillService;

	@Autowired
	private ExitPlanFillService exitPlanFillService;

	@Autowired
	private RedisLock redisLock;

	@Autowired
	private PriceStore priceStore;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TestClock clock;

	private OrderRecoveryScanScheduler scheduler;

	private final List<String> testEmails = new ArrayList<>();
	private final Set<String> priceKeys = new HashSet<>();

	@BeforeEach
	void setUp() {
		clock.set(NOW);
		LimitOrderFillExecutorProperties executorProperties = new LimitOrderFillExecutorProperties(false, 1, 1, 50);
		LimitOrderTriggerListener limitOrderTriggerListener = new LimitOrderTriggerListener(
			instrumentService,
			orderRepository,
			limitOrderFillService,
			mock(LimitOrderFillExecutorRouter.class),
			executorProperties);
		ExitPlanTriggerListener exitPlanTriggerListener = new ExitPlanTriggerListener(
			instrumentService,
			exitPlanRepository,
			exitPlanFillService);
		scheduler = new OrderRecoveryScanScheduler(
			instrumentService,
			priceStore,
			limitOrderTriggerListener,
			exitPlanTriggerListener,
			new OrderRecoveryScanLock(redisLock));
		redisTemplate.delete(CONNECTION_STATUS_KEY);
		redisTemplate.delete(RECOVERY_LOCK_KEY);
	}

	@AfterEach
	void tearDown() {
		for (String priceKey : priceKeys) {
			redisTemplate.delete(priceKey);
		}
		redisTemplate.delete(CONNECTION_STATUS_KEY);
		redisTemplate.delete(RECOVERY_LOCK_KEY);
		for (String email : testEmails) {
			deleteTestUser(email);
		}
		testEmails.clear();
		priceKeys.clear();
	}

	@Test
	@DisplayName("이벤트 없이 startup 재검사가 지정가와 OCO를 기존 체결 경계로 복구한다")
	void startupScanFillsPendingLimitAndOcoFromRedisSnapshotWithoutPriceEvent() {
		PreparedScenario scenario = preparePendingScenario();
		putConnectedPrice(scenario.instrument(), TRIGGER_PRICE, NOW);
		long tradesBeforeScan = countTrades(scenario.user().getId());

		scheduler.scanOnStartup();

		assertFilledScenario(scenario, tradesBeforeScan);
	}

	@Test
	@DisplayName("이벤트 없이 scheduled 재검사가 다음 실행에서 조건 충족 주문을 체결한다")
	void scheduledScanFillsPendingLimitAndOcoFromRedisSnapshotWithoutPriceEvent() {
		PreparedScenario scenario = preparePendingScenario();
		putConnectedPrice(scenario.instrument(), TRIGGER_PRICE, NOW);

		scheduler.scanOnSchedule();

		assertFilledScenario(scenario, 1);
	}

	@Test
	@DisplayName("stale 가격이면 지정가와 OCO의 PENDING 및 예약 원장을 유지한다")
	void scheduledScanKeepsPendingOrdersWhenRedisPriceIsStale() {
		PreparedScenario scenario = preparePendingScenario();
		putConnectedPrice(scenario.instrument(), TRIGGER_PRICE, NOW.minusSeconds(11));

		scheduler.scanOnSchedule();

		assertPendingScenario(scenario);
	}

	@Test
	@DisplayName("feed 연결이 끊기면 최신 Redis 가격이 있어도 지정가와 OCO를 건너뛴다")
	void scheduledScanKeepsPendingOrdersWhenFeedIsDisconnected() {
		PreparedScenario scenario = preparePendingScenario();
		putPrice(scenario.instrument(), TRIGGER_PRICE, NOW);
		priceStore.saveConnectionStatus(FeedConnectionStatus.DISCONNECTED);

		scheduler.scanOnSchedule();

		assertPendingScenario(scenario);
	}

	@Test
	@DisplayName("조건을 충족하지 않는 최신 가격이면 지정가와 OCO를 PENDING으로 유지한다")
	void scheduledScanKeepsPendingOrdersWhenPriceDoesNotTriggerEitherCondition() {
		PreparedScenario scenario = preparePendingScenario(new BigDecimal("90000"));
		putConnectedPrice(scenario.instrument(), BASE_PRICE, NOW);

		scheduler.scanOnSchedule();

		assertPendingScenario(scenario);
	}

	@Test
	@DisplayName("최신 가격이 없으면 startup 재검사가 해당 주문을 건너뛰고 예약을 유지한다")
	void startupScanKeepsPendingOrdersWhenRedisPriceIsAbsent() {
		PreparedScenario scenario = preparePendingScenario();
		redisTemplate.delete(priceKey(scenario.instrument()));

		scheduler.scanOnStartup();

		assertPendingScenario(scenario);
	}

	@Test
	@DisplayName("두 scheduled scan이 동시에 실행돼도 지정가와 OCO의 원장을 한 번만 변경한다")
	void concurrentScheduledScansCreateOneFillForLimitAndOco() throws Exception {
		PreparedScenario scenario = preparePendingScenario();
		putConnectedPrice(scenario.instrument(), TRIGGER_PRICE, NOW);
		long tradesBeforeScan = countTrades(scenario.user().getId());

		runConcurrently(scheduler::scanOnSchedule, scheduler::scanOnSchedule);

		assertFilledScenario(scenario, tradesBeforeScan);
		assertThat(countOrders(scenario.user().getId(), "MARKET")).isEqualTo(2L);
	}

	private PreparedScenario preparePendingScenario() {
		return preparePendingScenario(TRIGGER_PRICE);
	}

	private PreparedScenario preparePendingScenario(BigDecimal limitPrice) {
		User user = createUser("recovery-scan");
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.CRYPTO, NOW));
		Instrument instrument = firstCryptoInstrument();
		putConnectedPrice(instrument, BASE_PRICE, NOW);

		OrderResponse buy = orderService.createOrder(
			user.getId(), uniqueKey("market-buy"),
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", new BigDecimal("10")));
		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();

		LimitOrderResponse limitOrder = limitOrderService.createLimitOrder(
			user.getId(), uniqueKey("limit-buy"),
			new LimitOrderCreateRequest(
				Market.CRYPTO, instrument.getId(), OrderSide.BUY, LIMIT_QUANTITY, limitPrice));
		ExitPlanResponse exitPlan = exitPlanService.create(
			user.getId(), UUID.randomUUID().toString(),
			new ExitPlanCreateRequest(
				null, null, null, holding.getId(), EXIT_QUANTITY, ExitPriceType.PRICE,
				STOP_LOSS_PRICE, TRIGGER_PRICE, null, null));

		assertThat(buy.orderId()).isNotNull();
		assertThat(orderRepository.findById(limitOrder.orderId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.PENDING);
		assertThat(exitPlanRepository.findById(exitPlan.id()).orElseThrow().getStatus())
			.isEqualTo(ExitPlanStatus.PENDING);
		return new PreparedScenario(user, account, instrument, limitOrder.orderId(), exitPlan.id(), holding.getId());
	}

	private void assertFilledScenario(PreparedScenario scenario, long tradesBeforeScan) {
		assertThat(orderRepository.findById(scenario.limitOrderId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.FILLED);
		ExitPlan filledPlan = exitPlanRepository.findById(scenario.exitPlanId()).orElseThrow();
		assertThat(filledPlan.getStatus()).isEqualTo(ExitPlanStatus.FILLED_TAKE_PROFIT);
		assertThat(filledPlan.getTriggeredOrder()).isNotNull();
		assertThat(tradeRepository.findByOrderId(scenario.limitOrderId())).isPresent();
		assertThat(tradeRepository.findByOrderId(filledPlan.getTriggeredOrder().getId())).isPresent();
		assertThat(countTrades(scenario.user().getId())).isEqualTo(tradesBeforeScan + 2);

		Account account = accountRepository.findById(scenario.account().getId()).orElseThrow();
		Holding holding = holdingRepository.findById(scenario.holdingId()).orElseThrow();
		assertThat(account.getReservedCash()).isZero();
		assertThat(holding.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(exitPlanConditionRepository.findByExitPlanIdOrderByIdAsc(scenario.exitPlanId()))
			.extracting(condition -> condition.getConditionType(), condition -> condition.getStatus())
			.containsExactly(
				org.assertj.core.groups.Tuple.tuple(ExitPlanConditionType.STOP_LOSS,
					ExitPlanConditionStatus.CANCELLED_BY_OCO),
				org.assertj.core.groups.Tuple.tuple(ExitPlanConditionType.TAKE_PROFIT,
					ExitPlanConditionStatus.TRIGGERED));
	}

	private void assertPendingScenario(PreparedScenario scenario) {
		assertThat(orderRepository.findById(scenario.limitOrderId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.PENDING);
		assertThat(exitPlanRepository.findById(scenario.exitPlanId()).orElseThrow().getStatus())
			.isEqualTo(ExitPlanStatus.PENDING);
		assertThat(countTrades(scenario.user().getId())).isEqualTo(1L);
		assertThat(countOrders(scenario.user().getId(), "MARKET")).isEqualTo(1L);

		Account account = accountRepository.findById(scenario.account().getId()).orElseThrow();
		Holding holding = holdingRepository.findById(scenario.holdingId()).orElseThrow();
		assertThat(account.getReservedCash()).isPositive();
		assertThat(holding.getReservedQuantity()).isEqualByComparingTo(EXIT_QUANTITY);
	}

	private void putConnectedPrice(Instrument instrument, BigDecimal price, LocalDateTime receivedAt) {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		putPrice(instrument, price, receivedAt);
	}

	private void putPrice(Instrument instrument, BigDecimal price, LocalDateTime receivedAt) {
		String key = priceKey(instrument);
		priceKeys.add(key);
		redisTemplate.opsForHash().putAll(key, Map.of(
			"price", price.toPlainString(),
			"receivedAt", receivedAt.toString(),
			"observedAt", receivedAt.toString()));
	}

	private Instrument firstCryptoInstrument() {
		return instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO).stream()
			.filter(instrument -> !instrument.isTutorialSample())
			.findFirst()
			.orElseThrow();
	}

	private User createUser(String scenario) {
		String email = uniqueEmail(scenario);
		testEmails.add(email);
		return userRepository.saveAndFlush(User.create(email, "password-hash", uniqueNickname(scenario), NOW));
	}

	private long countTrades(Long userId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from trades t join orders o on o.id = t.order_id where o.user_id = ?", Long.class, userId);
	}

	private long countOrders(Long userId, String orderType) {
		return jdbcTemplate.queryForObject(
			"select count(*) from orders where user_id = ? and order_type = ?", Long.class, userId, orderType);
	}

	private void runConcurrently(ThrowingRunnable actionA, ThrowingRunnable actionB) throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Void> futureA = executor.submit(toCallable(actionA, ready, start));
			Future<Void> futureB = executor.submit(toCallable(actionB, ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			futureA.get(15, TimeUnit.SECONDS);
			futureB.get(15, TimeUnit.SECONDS);
		} finally {
			start.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private Callable<Void> toCallable(ThrowingRunnable action, CountDownLatch ready, CountDownLatch start) {
		return () -> {
			ready.countDown();
			assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
			action.run();
			return null;
		};
	}

	private void deleteTestUser(String email) {
		jdbcTemplate.update(
			"delete from exit_plan_conditions where exit_plan_id in "
				+ "(select id from exit_plans where user_id in (select id from users where email = ?))",
			email);
		jdbcTemplate.update(
			"delete from exit_plan_idempotency_keys where user_id in "
				+ "(select id from users where email = ?)",
			email);
		jdbcTemplate.update(
			"delete from exit_plans where user_id in (select id from users where email = ?)", email);
		jdbcTemplate.update(
			"delete from trade_allocations where holding_lot_id in "
				+ "(select id from holding_lots where holding_id in "
				+ "(select id from holdings where account_id in "
				+ "(select id from accounts where user_id in (select id from users where email = ?))))",
			email);
		jdbcTemplate.update(
			"delete from holding_lots where holding_id in "
				+ "(select id from holdings where account_id in "
				+ "(select id from accounts where user_id in (select id from users where email = ?)))",
			email);
		jdbcTemplate.update(
			"delete from trades where order_id in "
				+ "(select id from orders where user_id in (select id from users where email = ?))",
			email);
		jdbcTemplate.update("delete from orders where user_id in (select id from users where email = ?)", email);
		jdbcTemplate.update(
			"delete from holdings where account_id in "
				+ "(select id from accounts where user_id in (select id from users where email = ?))",
			email);
		jdbcTemplate.update("delete from accounts where user_id in (select id from users where email = ?)", email);
		jdbcTemplate.update("delete from users where email = ?", email);
	}

	private static String priceKey(Instrument instrument) {
		return "price:crypto:" + instrument.getSymbol();
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private static String uniqueKey(String scenario) {
		return scenario + "-" + UUID.randomUUID();
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	private record PreparedScenario(
		User user, Account account, Instrument instrument, Long limitOrderId, Long exitPlanId, Long holdingId) {
	}
}
