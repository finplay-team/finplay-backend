# Plan: 스케줄러 재시작·시세 이벤트 누락 보완 재검사

## 관련 문서

- Spec: `./spec.md`
- PRD: `../../prd.md` §3 구현 현황, §4 `LMT-002`
- 지정가 계약: [`../015-limit-order/spec.md`](../015-limit-order/spec.md), [`../015-limit-order/plan.md`](../015-limit-order/plan.md)
- OCO 계약: [`../021-general-risk-management-oco/spec.md`](../021-general-risk-management-oco/spec.md), [`../021-general-risk-management-oco/plan.md`](../021-general-risk-management-oco/plan.md)
- 지정가 비동기 실행·실패 격리: [`../037-limit-order-async-fill/plan.md`](../037-limit-order-async-fill/plan.md), [`../../adr/0024-limit-order-fill-executor.md`](../../adr/0024-limit-order-fill-executor.md), [`../../adr/0025-limit-order-fill-batch-commit.md`](../../adr/0025-limit-order-fill-batch-commit.md)
- 지정가 청크 벌크 락: [`../054-limit-order-fill-bulk-lock/plan.md`](../054-limit-order-fill-bulk-lock/plan.md), [`../../adr/0028-holdings-insert-deadlock-mitigation.md`](../../adr/0028-holdings-insert-deadlock-mitigation.md)
- Web/Scheduler 경계: [`../055-web-scheduler-role-separation/spec.md`](../055-web-scheduler-role-separation/spec.md), [`../055-web-scheduler-role-separation/plan.md`](../055-web-scheduler-role-separation/plan.md), [`../056-market-autofill-scheduler-separation/spec.md`](../056-market-autofill-scheduler-separation/spec.md), [`../056-market-autofill-scheduler-separation/plan.md`](../056-market-autofill-scheduler-separation/plan.md), [`../059-web-scheduler-runtime-separation/spec.md`](../059-web-scheduler-runtime-separation/spec.md), [`../059-web-scheduler-runtime-separation/plan.md`](../059-web-scheduler-runtime-separation/plan.md)
- 아키텍처·테스트: [`../../adr/0002-architecture.md`](../../adr/0002-architecture.md), [`../../adr/0003-testing-strategy.md`](../../adr/0003-testing-strategy.md)
- Redis 락 구현 선례: [`../../adr/0014-crypto-watch-redis-lock.md`](../../adr/0014-crypto-watch-redis-lock.md)

## 현재 코드 분석

| 경계 | 현재 구현 | 계획에서 유지할 것 |
|---|---|---|
| 가격 유입 | `PriceStore.saveTick()`이 Redis 최신 가격을 저장한 뒤 `CryptoPriceUpdatedEvent`를 발행한다. `getLatestPrice()`는 `price`, `receivedAt`, `observedAt`을 읽고, `isPriceAvailable()`는 `CONNECTED`와 10초 stale 기준을 확인한다. | 재검사는 `PriceStore` 최신 가격을 사용하고, stale/미연결 가격은 건너뛴다. 외부 Feed·이벤트 발행 구조는 바꾸지 않는다. |
| 지정가 Listener | `LimitOrderTriggerListener`는 `prod & scheduler`에서 이벤트를 받아 `OrderRepository.findPendingLimitOrdersToFill(instrumentId, event.price())`를 조회하고, enabled 시 `LimitOrderFillExecutorRouter`에 종목별 청크를 제출한다. 후보·작업 예외는 현재 Listener 경계에서 격리한다. | 재검사는 같은 Listener 진입 경계를 재사용한다. 별도 후보 조건·재시도 큐를 만들지 않는다. |
| OCO Listener | `ExitPlanTriggerListener`는 같은 Profile에서 `ExitPlanRepository.findPendingExitPlansToFill(instrumentId, event.price())`를 조회하고 후보별 `ExitPlanFillService.fillIfPending(id, currentPrice)`를 호출한다. | 최신 가격을 담은 동일한 이벤트 형태로 호출하고, 후보별 예외 격리를 유지한다. |
| 지정가 Repository | 지정가 후보 쿼리는 `PENDING`, `LIMIT`, 매수/매도 가격 조건, `practicePriceSessionId is null`, `practiceAttemptId is null`을 이미 포함하며 `requestedAt asc, id asc` 순이다. | 이 조건과 교육 주문 제외를 유지한다. 필요한 종목 순회에는 기존 `InstrumentService.getRealInstrumentEntities(Market.CRYPTO)`를 사용한다. |
| OCO Repository | OCO 후보 쿼리는 `PENDING`, `practiceAttemptId is null`, 익절·손절 가격 조건, `reservedAt asc, id asc` 순이다. | `PENDING` allow-list와 기존 가격 조건을 유지한다. |
| 지정가 FillService | `fillIfPending`·`fillBatch`는 `@Transactional(isolation = READ_COMMITTED)`이다. 단건은 order row를 먼저 잠그고 상태 확인 후 account, SELL이면 holding을 잠근다. 배치는 order→account→holding 벌크 `FOR UPDATE`, 청크 원자성을 사용한다. 일반 주문은 현재 후보 조회 가격을 FillService에서 다시 확인하지 않는다. | 기존 원장 처리·잠금 순서·청크 원자성은 유지하고, 이벤트/재검사 가격을 전달해 order lock 후 일반 지정가 조건을 재확인한다. 불충족이면 상태·예약·원장을 바꾸지 않는다. |
| OCO FillService | `fillIfPending`는 `@Transactional`이며 account→holding→plan 순서로 재조회·잠근다. 잠근 plan이 아직 `PENDING`인지 확인하고 현재 가격으로 조건을 다시 판정한 뒤 시장가 매도·예약 소비·OCO 반대 조건 취소·`FILLED_TAKE_PROFIT`/`FILLED_STOP_LOSS` 전이를 수행한다. | 이 서비스의 가격 재판정·상태 전이·트랜잭션·row lock을 그대로 재사용한다. Scheduler가 plan을 선잠그지 않는다. |
| Redis 분산락 | 공통 `RedisLock`은 token+TTL `SET NX`와 token 검증 Lua unlock을 제공한다. Feed leader·랭킹·배치가 도메인별 wrapper로 사용한다. 지정가 Fill 자체는 ADR-0024에 따라 Redis 락 대신 DB row lock을 사용한다. | 재검사 coordinator에만 별도 Scheduler 락 wrapper를 두고, 체결 원장의 Redis fail-open 우회는 만들지 않는다. 키는 `order:recovery-scan:lock`, TTL은 30초로 한다. |
| Runtime 경계 | `SchedulingConfig`, 자동체결 Listener/Executor는 `!prod | (prod & scheduler)`이고, #589의 `prod,web`에는 자동체결 실행부가 없다. Scheduler는 `web-application-type: none`, scheduling pool 18이다. | 새 재검사 실행부는 `prod & scheduler`로 한정하고, Web/HTTP/SSE/공통 FillService Bean을 Scheduler 전용으로 재분류하지 않는다. |

## 실행 흐름

```text
ApplicationReadyEvent 또는 @Scheduled
  → Redis scheduler recovery lock 획득 실패 시 이번 실행 skip
  → InstrumentService.getRealInstrumentEntities(CRYPTO)
  → PriceStore에서 symbol별 최신 유효 가격 snapshot 확보
  → 같은 snapshot으로 기존 LimitOrderTriggerListener와 ExitPlanTriggerListener 호출
  → 기존 Repository 후보 조건 재사용
  → 기존 LimitOrderFillService / ExitPlanFillService 호출
  → 기존 row lock·PENDING 재확인·원장/예약/상태 전이
  → symbol·phase별 예외 로깅 후 다음 작업 계속
```

재검사는 `ApplicationEventPublisher`로 새 가격 이벤트를 다시 발행하지 않는다. 전체 애플리케이션의 다른 이벤트 소비자를 중복 실행할 수 있으므로, 기존 두 Trigger Listener의 공개 진입 메서드에 동일한 가격 snapshot을 직접 전달하는 경계를 계획한다. 구현 시 Listener API를 확장하더라도 기존 `PriceStore.saveTick()`의 이벤트 경로와 체결 결과는 바꾸지 않는다.

## 재검사 실행 시점

1. `ApplicationReadyEvent` 수신 직후 한 번 실행한다. 애플리케이션 기동은 완료됐지만 Feed/Redis 최신 가격이 아직 없을 수 있으므로, 가격이 없는 종목은 정상 skip한다.
2. 이후 fixed-delay 5초인 `@Scheduled` 정기 실행으로 전체 코인 실물 종목을 반복한다. 기존 `PriceStore` stale 기준 10초보다 짧은 주기로 두어 시세 이벤트 유실 및 실행기 메모리 작업 유실의 보완 경로가 된다.
3. 한 번의 실행 안에서는 종목별 최신 가격 snapshot을 기준으로 두 Listener를 호출한다. Listener의 비동기 지정가 작업이 실제로 실행되는 시점에는 전달받은 가격으로 다시 조건을 검증한다.
4. Redis 락의 TTL보다 오래 걸리는 경우를 고려해 lock ownership을 잃은 뒤 다른 인스턴스와 동시에 계속 처리하지 않도록 TTL·작업량·갱신 여부를 구현 전에 확정한다. 락 만료 후 강제 unlock이 다른 인스턴스 락을 지우지 않도록 token 기반 해제를 유지한다.

## 가격·체결 설계

### 최신 가격 snapshot

- `PriceStore.getLatestPrice(symbol)`에서 읽은 가격과 `receivedAt`을 사용한다.
- `PriceStore.getConnectionStatus() == CONNECTED`이고 `PriceStore.isStale(receivedAt) == false`인 경우만 유효하다.
- `PriceQueryService`의 코인 표시 조회는 현재 연결 여부와 값 존재를 중심으로 하며 stale 판정을 직접 호출하지 않으므로, Scheduler 보완 경로는 기존 Redis 최신값·stale 의미를 명시적으로 소유하는 `PriceStore`를 사용한다.
- 가격이 유효하지 않으면 후보 Repository를 호출하지 않는다. 가격을 임의의 0, limit price, 마지막 메모리 값 또는 외부 REST 보충값으로 대체하지 않는다.

### 지정가

- Listener는 `findPendingLimitOrdersToFill`로 1차 후보를 찾고 기존 executor/router 및 `fillBatch`를 재사용한다.
- 이벤트 가격이 scheduler에서 FillService로 전달되도록 단건·배치 체결 호출을 확장한다. order row lock을 얻은 뒤 매수 `currentPrice <= limitPrice`, 매도 `currentPrice >= limitPrice`를 다시 확인한다.
- 재확인 실패 시 `PENDING` 상태, 예약 현금/수량, 원장 행을 그대로 유지한다. 조건이 다시 충족되는 다음 이벤트·정기 검사에서 재시도한다.
- 기존 `limitPrice` 고정 체결가, 수수료, FIFO, `OrderStatus.FILLED`, 청크 단위 원자성 및 실패 격리는 변경하지 않는다.

### OCO

- Listener는 `findPendingExitPlansToFill`로 1차 후보를 찾고 현재 가격을 `ExitPlanFillService.fillIfPending`에 전달한다.
- FillService가 account→holding→plan row lock을 잡은 뒤 `plan.isPending()`을 확인하고 익절/손절을 재판정한다.
- 조건 불충족이면 no-op이며, 조건 충족 시 기존 시장가 SELL·예약 수량 소비·실현손익·반대 조건 취소·terminal status 전이를 한 트랜잭션에서 수행한다.

## Redis·DB 동시성

- 재검사 전체에는 `RedisLock` 기반 Scheduler 전용 락을 둔다. 키는 `order:recovery-scan:lock`, TTL은 30초다. 락이 이미 다른 Scheduler에 있거나 Redis 장애로 획득되지 않으면 대기하지 않고 이번 scan을 건너뛴다.
- 분산락은 같은 scan을 여러 인스턴스가 중복 수행하는 비용을 줄이는 coordinator 역할만 한다. Redis 락이 체결 승인이나 원장 정합성의 유일한 방어선이 되지 않는다.
- 실제 지정가 중복 체결 방지는 기존 order row `PESSIMISTIC_WRITE`와 `PENDING` 재확인이 담당한다. OCO 중복 체결 방지는 기존 account·holding·plan row lock과 `isPending()` 재확인이 담당한다.
- Scheduler는 후보를 읽은 뒤 계좌·보유·주문·plan을 선잠그지 않는다. 이를 지키면 기존 Listener/FillService의 전역 lock 순서와 transaction 경계를 유지할 수 있다.
- 서로 다른 종목의 실패를 분리하되, 기존 `LimitOrderFillService.fillBatch`가 보장하는 청크 원자성은 보존한다. 개별 주문 실패를 억지로 같은 청크 안에서 삼켜 부분 커밋하지 않는다.

## API 설계

HTTP API, 요청 DTO, 응답 DTO, 오류 코드, `ai/api-routes.md`, `docs/api/` 변경은 없다.

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| 해당 없음 | 해당 없음 | 해당 없음 | 해당 없음 | 운영 Scheduler 내부 재검사 |

## 데이터 모델

새 테이블·컬럼·Flyway migration은 없다. 기존 `orders`, `exit_plans`, `exit_plan_conditions`, `accounts`, `holdings`, `trades` 원장을 그대로 사용한다. Repository 후보 쿼리를 수정하는 경우에도 `PENDING` allow-list, 교육 경로 제외, 기존 정렬·시장 범위를 유지한다.

## 테스트 계획

- 단위: 재검사 Scheduler의 시작/정기 호출, 최신 가격 유효성·stale skip, Redis 락 획득 실패 skip, 종목별/phase별 실패 격리, 동일 snapshot 전달을 검증한다. Listener와 FillService 단위 테스트에는 지정가 조건 재확인 및 OCO terminal no-op을 추가한다.
- 슬라이스: Repository 쿼리를 새로 만들거나 변경할 때만 `@DataJpaTest`로 `PENDING`·가격 방향·교육 주문 제외를 검증한다. 기존 쿼리를 그대로 재사용하면 별도 슬라이스 테스트를 중복하지 않는다.
- 통합(Testcontainers MySQL + Redis, ADR-0003):
  - 재시작: DB에 남긴 `PENDING` 지정가/OCO와 Redis 최신 가격을 준비한 뒤 새 Scheduler 인스턴스의 startup scan을 호출해 각각 정확히 한 번 체결되는지 검증한다.
  - 이벤트 누락: 가격 저장 이벤트/Listener 호출 없이 정기 scan만 호출해 조건 충족 주문·OCO가 체결되는지 검증한다.
  - 조건·상태: 가격이 조건을 충족하지 않거나 stale/미연결이면 미체결이고, `FILLED`·`CANCELLED` 및 존재하는 terminal 상태는 후보가 아니며, 현재 모델에 없는 `EXPIRED` 요구는 별도 결정 없이는 상태를 추가하지 않는지 확인한다.
  - 동시성: 두 scheduler scan 또는 scan과 가격 이벤트/취소 요청을 동시에 실행해 trade/order/plan이 한 번만 확정되고 예약 원장이 정확히 소비·반환되는지 검증한다. 기존 `LimitOrderFillBatch*`·`ExitPlanCancelFillConcurrencyIntegrationTest` 패턴을 재사용한다.
  - 실패 격리: 한 종목의 Repository/FillService 예외 뒤에도 다른 종목의 재검사와 지정가·OCO의 다른 phase가 계속 실행되는지 검증한다.
- Profile 회귀: 기존 `ProdWebProfileContextIntegrationTest`와 `ProdSchedulerProfileContextIntegrationTest`에 새 실행부 등록 여부를 추가하되, Web의 HTTP/SSE와 Scheduler의 `web-application-type: none` 계약은 바꾸지 않는다.

## 검증 명령

구현 단계에서 아래 범위의 테스트를 먼저 실행하고, 전체 build 여부와 외부 Bithumb/KIS 실연동 미검증을 구분해 보고한다.

```text
./gradlew test --tests "*Scheduler*Recovery*" --tests "*LimitOrder*" --tests "*ExitPlan*" --tests "*Prod*ProfileContextIntegrationTest"
./gradlew compileJava compileTestJava spotlessJavaCheck spotbugsMain spotbugsTest
```

## 확정한 운영 판단

- 정기 재검사는 fixed-delay 5초로 실행한다. `PriceStore`의 10초 stale 기준 안에서 누락 이벤트를 보완하면서 Scheduler 작업을 과도하게 증폭하지 않는 주기다.
- 재검사 coordinator Redis lock은 `order:recovery-scan:lock` 키와 30초 TTL을 사용한다. 한 번의 scan은 종목별 실패 격리로 종료되며, 원장 중복 방지는 DB row lock이 담당하므로 TTL 만료 시에도 token 검증 unlock으로 다른 인스턴스의 락을 지우지 않는다.
- `EXPIRED`가 현재 `OrderStatus`·`ExitPlanStatus`에 존재하지 않는다. 현재 계획은 `PENDING`만 허용해 terminal 상태를 배제하는 것이며, 실제 영속 `EXPIRED` 행을 추가하라는 요구라면 별도 상태·migration 결정이 필요하다.
- Issue #591을 기존 `LMT-002`·`RISK-OCO-009`의 신뢰성 보강으로만 추적할지, PRD §3에 별도 운영 요구사항 ID를 추가할지 PRD에 결정이 없다. 이 계획은 기존 ID를 새로 완료 처리하지 않으며, 구현 PR에서 §3 갱신 필요 여부를 최종 확인한다.
