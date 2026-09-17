# Plan: Issue #589 2단계 — 시세·수집·자동체결 Scheduler 역할 분리

## 관련 문서

- Spec: `./spec.md`
- 1단계 정본: `../055-web-scheduler-role-separation/spec.md`, `plan.md`, `tasks.md` at `471eb1a4`
- 1단계 분석: `../../../docs/issue-589-web-scheduler-code-analysis.md` — 읽기 전용 참고, 수정하지 않음
- PRD: `../../prd.md` — Issue #589 전용 ID 없음, PRD §3 갱신 대상 아님
- Spec 규칙: `../README.md`
- 코드 규칙: `../../../docs/conventions/code.md`
- 아키텍처: `../../adr/0002-architecture.md`
- 테스트 전략: `../../adr/0003-testing-strategy.md`

## 기준선과 최소 변경 원칙

현재 `dev` HEAD는 `471eb1a4`의 부모이므로 이 spec의 구현 기준선은 1단계 변경이 적용된 `471eb1a4` 이후다. 구현 착수 전 해당 커밋이 포함된 상태에서 아래 대상의 실제 Profile과 생성자 시그니처를 다시 확인한다.

1단계에서 이미 제한된 Bean은 동일 표현식을 다시 붙이지 않는다. 대상 클래스별로 `@Profile`, `@Scheduled`, `@EventListener`, `ApplicationReadyEvent`, `@PostConstruct`, `InitializingBean`/`DisposableBean`, `ObjectProvider`를 검색하고, 호출자·생성자·실행 lifecycle을 함께 대조한다. 대상이 이미 조건을 만족하면 문서·테스트의 검증만 추가하고 production 변경은 만들지 않는다. 대상이 조건을 만족하지 않는 경우에만 기존 `!prod`, `crypto-real` 등 비운영·외부 구현체 선택 조건을 보존한 가장 작은 역할 조건을 추가한다.

## 대상 실행부와 보존 경계

| 영역 | Scheduler 실행부로 점검할 대상 | Web에서 보존할 공통 경계 | 점검 기준 |
|---|---|---|---|
| Bithumb Feed | `BithumbFeedConfig`, `BithumbFeedLeaderLock`, `BithumbFeedLifecycle`, `BithumbFeedStatusReconciler`, `BithumbRestTickerPoller`, `BithumbWebSocketFeedClient` | `PriceStore`, `CryptoCandleStore` 및 가격 조회 경계 | Poller의 `ObjectProvider<BithumbFeedLifecycle>` fallback 때문에 Lifecycle만 제한하지 않고 Feed 묶음 전체를 확인한다. local/test Fake·시뮬레이터는 유지한다. |
| 가격 감시 | `CryptoPriceSnapshotService`, `CryptoPriceMoveWatcher`와 그 Scheduler 실행에만 필요한 직접 경계 | 가격 조회·카드 조회·공통 Redis Store | snapshot 기록·변동 감시의 `@Scheduled`와 수집 보조 호출만 Scheduler에서 등록되는지 확인한다. 조회 Service를 배치 전용으로 바꾸지 않는다. |
| KIS 수집/Import | `KisProperties`, `KisRestClientConfig`, `KisHistoricalCandleClientImpl`, `KisDailyCandleClientImpl`, `KisHistoricalCandleCollector`, `StockDailyCandleCollector`, `KisHistoricalCandleImportWriter`, `StockDailyCandleImportWriter`, `StockCollectionLock`, `StockReplaySessionLock`, `StockReplaySessionScheduler` | `StockCandleRepository`, `StockDailyCandleRepository`, `StockReplaySessionRepository`, `StockReplayService`, 공통 거래일 계산·조회 경계 | KIS 외부 Client와 수집/Import lifecycle은 Web에 없고 Scheduler에만 있으며, Web 조회가 같은 DB Repository를 계속 주입받는지 확인한다. |
| 자동체결 Trigger/Executor | `LimitOrderTriggerListener`, `ExitPlanTriggerListener`, `LimitOrderFillExecutorConfig`, `LimitOrderFillExecutorRouter` | `LimitOrderFillService`, `ExitPlanFillService`, `AccountService`, `PortfolioBuyService`, `PortfolioSellService`, 주문·보유·거래 원장 Repository | `CryptoPriceUpdatedEvent` listener와 executor lifecycle만 Scheduler에 둔다. Fill Service의 교육 가격 틱 정산 의존성과 트랜잭션·Lock·쿼리는 읽고 보존한다. |
| 1단계 기존 작업 | 뉴스·공시·피드백·랭킹 Scheduler | 해당 조회·공통 Service | 새 리팩터링 대상이 아니며 1단계 결과를 회귀 기준으로만 확인한다. |

`StockPriceStreamService`와 `SseEmitterRegistry`는 위 표의 Scheduler 실행부로 이동시키지 않는다. 두 Bean은 양쪽에 생성될 수 있지만 `prod,web`에 scheduling infrastructure가 없어 `publishScheduledUpdates()`와 heartbeat scheduled method가 등록되지 않는지 확인한다. `PriceStore`도 양쪽에서 읽기·공통 이벤트 경계로 유지하며, Web에서 Feed가 실행되지 않는 것과 Store Bean이 존재하는 것을 분리해서 검증한다.

## 호출·생성자 의존관계 점검

다음 실제 그래프를 기준으로 누락과 과잉 Profile을 판정한다.

```text
Bithumb Feed / REST ticker
  → PriceStore.saveTick() / recordObservation()
  → JVM 내부 CryptoPriceUpdatedEvent
  → LimitOrderTriggerListener / ExitPlanTriggerListener
  → LimitOrderFillExecutorRouter 또는 Fill Service
  → Account·Holding·Trade 원장 및 공통 Ranking 이벤트

KIS collector
  → KIS Client
  → Import writer
  → StockCandle·StockDailyCandle·MarketDataImport DB 저장
  → StockReplaySessionScheduler
  → Web의 StockReplayService 조회

CryptoPriceSnapshotService / CryptoPriceMoveWatcher
  → PriceStore·CryptoCandleStore·가격 감시/피드백 결과 저장소
  → Web의 가격·카드 조회 경계
```

- 각 시작점의 생성자 매개변수와 `ObjectProvider`/선택 Bean을 확인해 `prod,web`에서 Scheduler 전용 구현체를 요구하지 않는지 확인한다.
- `LimitOrderFillService`의 `PracticeOrderAttributionPort`와 교육 체결 그래프처럼, 실제 운영 자동 체결이 사용하더라도 Web 교육 경로 때문에 공통인 의존성은 Scheduler 전용으로 제한하지 않는다.
- `BithumbRestTickerPoller`처럼 선택적 Lifecycle 부재를 leader로 해석하는 fallback은 Web에 Bean이 남아 실행되는 경로가 없는지 함께 확인한다.
- `ApplicationEventPublisher`는 프로세스 간 전달 수단이 아니므로, 이번 계획에서는 Web으로 이벤트를 옮기지 않고 Scheduler 내부 listener 생성 여부만 검증한다.

## API 설계

API endpoint, 요청 DTO, 응답 DTO, 오류 코드의 추가·수정·삭제가 없다. `ai/api-routes.md`와 `docs/api/`는 갱신하지 않는다.

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| 해당 없음 | 해당 없음 | 해당 없음 | 해당 없음 | 운영 Bean 생성·실행 경계와 생성자 의존관계만 점검하는 spec |

## 입력 명세

HTTP 입력은 없다. 역할 선택은 기존 운영 Profile 조합인 `prod,web` 또는 `prod,scheduler`이며, 실제 배포 설정값·환경변수·credential은 이 spec에 기록하거나 변경하지 않는다.

## 데이터 모델

Entity, DB schema, Flyway migration, Repository query, Redis key/event payload, SSE payload 변경 없음. KIS/Bithumb 수집 데이터의 저장·정규화 로직도 변경하지 않는다.

## 구현 순서

1. `471eb1a4`의 대상 Profile과 1단계 context 테스트를 기준으로 Bithumb Feed, 가격 감시, KIS 수집/Import, 자동체결 Trigger/Executor의 클래스·method·lifecycle 매트릭스를 만든다.
2. 각 대상의 실제 생성자·호출자·선택 의존성을 검색해 `prod,web`에서 생성 또는 실행으로 이어지는 잔여 경로와 `prod,scheduler`에서 끊긴 경로를 구분한다. 이 단계에서는 코드 수정 없이 누락 목록만 확정한다.
3. 누락된 대상이 있을 때만 그 직접 Bean/config/lifecycle에 최소 Profile 조건을 적용하고, 기존 비운영·외부 구현체 조건을 보존한다. 이미 1단계 조건을 만족한 파일과 공통 경계 파일은 수정하지 않는다.
4. 역할별 ApplicationContext와 scheduled/event 등록 테스트를 보강한다. `prod,web`에서는 대상 실행부가 없고 공통 Bean만 남는지, `prod,scheduler`에서는 대상 실행부와 생성자 그래프가 기동하는지 확인한다.
5. local/test Fake·시뮬레이터 회귀와 1단계 Web/Scheduler 경계를 확인하고, API·DB·체결·수집·IPC·SSE 범위 밖 변경 및 민감정보 유입이 없는지 최종 diff를 점검한다.

## 테스트 계획

- 단위: 대상 Profile expression과 비운영 Profile 조합을 검증한다. 이미 1단계에 있는 조건 테스트는 재사용하고, 새로 발견된 잔여 대상만 추가한다.
- 슬라이스: API 계약·Repository query를 바꾸지 않으므로 새 `@WebMvcTest`·`@DataJpaTest`는 만들지 않는다.
- 통합: ADR-0003에 따라 `@SpringBootTest`와 기존 Testcontainers 설정을 사용한다.
  - `prod,web`: Bithumb Feed/REST ticker/가격 감시/KIS Client·collector·Import writer/자동체결 Trigger·Executor의 Bean이 없고 `SchedulingConfig`, `ScheduledAnnotationBeanPostProcessor`와 대상 scheduled/event 실행 등록이 없음을 확인한다.
  - `prod,scheduler`: 위 대상 Bean이 각각 1개로 생성되고, `@Scheduled`, `ApplicationReadyEvent`, `@PostConstruct`, Executor lifecycle·listener가 필요한 생성자 의존성을 모두 만족함을 확인한다.
  - 양쪽: `PriceStore`, `StockPriceStreamService`, `LimitOrderFillService`, `ExitPlanFillService`, `SseEmitterRegistry`와 공통 Repository/조회/원장 그래프가 생성됨을 확인한다. Web에서 common Bean의 scheduled method가 등록되지 않는 것과 Bean 자체가 없는 것을 구분한다.
- 회귀: local/test의 Fake Feed·시뮬레이터 조건과 1단계에서 확인한 Web/Scheduler context 테스트를 실행한다. 실제 Bithumb/KIS 연결, 운영 DB, 운영 Redis, 자동 체결 결과를 Fake/context 테스트의 통과로 주장하지 않는다.

## 완료 검증 명령

```text
./gradlew spotlessApply
./gradlew test --tests "com.finplay.api.ProdWebProfileContextIntegrationTest" --tests "com.finplay.api.ProdSchedulerProfileContextIntegrationTest" --tests "*Profile*" --tests "*Conditional*"
./gradlew build
```

실제 대상 테스트 이름은 구현 시 추가·재사용한 파일에 맞춰 조정한다. 외부 Feed/KIS와 다중 프로세스 SSE·Redis IPC는 범위 밖이므로 별도 미검증 항목으로 보고한다.
