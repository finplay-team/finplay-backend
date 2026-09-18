# Spec: Issue #589 2단계 — 시세·수집·자동체결 Scheduler 역할 분리

## 개요

Issue #589 1단계 커밋 `471eb1a4`에서 정한 `prod,web`·`prod,scheduler` Profile 경계를 전제로, 시세를 생성하거나 자동 체결을 트리거하는 운영 Bean의 호출·생성자 의존관계를 다시 점검한다. 목표는 `prod,web`에서 Bithumb Feed·시세 수집·가격 감시·KIS 수집/Import·자동체결 Trigger/Executor가 생성·실행되지 않고, `prod,scheduler`에서만 해당 역할이 생성·실행되는지 확인하고 남은 최소 변경만 계획하는 것이다.

PRD에는 Issue #589 전용 요구사항 ID가 없고, 1단계의 `WSR-*` 식별자는 1단계 spec 내부 검증 항목이다. 이 문서의 `MSAS-*` 식별자는 이번 2단계 검증 항목이며 PRD §3 구현 현황의 새 행이나 완료 근거로 사용하지 않는다. PRD와 사용자 요구 사이의 이 매핑 부재는 미확정 사항으로 남긴다.

## 사용자 시나리오

- 운영자는 `prod,web` 프로세스에서 HTTP API와 기존 Web 조회/SSE 경계를 사용할 수 있으며, 이 프로세스에서 Feed·수집·가격 감시·자동 체결 실행이 시작되지 않음을 확인할 수 있다.
- 운영자는 `prod,scheduler` 프로세스에서 Bithumb 시세 Feed·시세 감시·KIS 수집/Import·자동 체결 Trigger/Executor가 필요한 의존성과 함께 생성되고, 기존 이벤트·스케줄 진입점이 동작하는지 확인할 수 있다.
- 개발자는 local/test의 Fake Feed·시뮬레이터와 기존 비운영 동작을 운영 Profile 분리 때문에 잃지 않는다.
- 검토자는 1단계에서 이미 적용한 `@Profile`을 중복 추가하지 않고, 실제 호출자와 생성자 의존관계에 근거한 변경 범위를 확인할 수 있다.

## Profile·등록 매트릭스

| 실행부 | 주요 클래스 | `prod,web` | `prod,scheduler` | 확인 근거 |
|---|---|---|---|---|
| Bithumb Feed·ticker | `BithumbFeedConfig`, `BithumbFeedLeaderLock`, `BithumbFeedLifecycle`, `BithumbFeedStatusReconciler`, `BithumbRestTickerPoller`, `BithumbWebSocketFeedClient` | 생성되지 않음 | 각 1개 생성 | Profile 조건, 생성자 그래프, Feed lifecycle 및 scheduled 등록 테스트 |
| 가격 snapshot·감시 | `CryptoPriceSnapshotService`, `CryptoPriceMoveWatcher` | 생성되지 않음 | 각 1개 생성 | Profile 조건과 `recordSnapshots()`, `watch()` scheduled 등록 테스트 |
| KIS 수집·Import·Replay | `KisProperties`, `KisRestClientConfig`, `KisHistoricalCandleClientImpl`, `KisDailyCandleClientImpl`, `KisHistoricalCandleCollector`, `StockDailyCandleCollector`, `StockReplaySessionScheduler` 및 Import writer/lock | 생성되지 않음 | 각 실행 Bean 생성 | 생성자 의존성, DB writer/lock 그래프, 수집·재시도·Replay scheduled 등록 테스트 |
| 자동체결 실행부 | `LimitOrderTriggerListener`, `ExitPlanTriggerListener`, `LimitOrderFillExecutorConfig`, `LimitOrderFillExecutorRouter` | 생성되지 않음 | 각 1개 생성 | `CryptoPriceUpdatedEvent` listener 등록과 Executor lifecycle 확인 |
| 공통 경계 | `PriceStore`, `StockPriceStreamService`, `LimitOrderFillService`, `ExitPlanFillService`, `SseEmitterRegistry` | 각 1개 생성, scheduling infrastructure 없음 | 각 1개 생성, 공통 scheduled method 등록 | 양쪽 ApplicationContext와 `ScheduledTaskHolder` 등록 확인 |

`prod,scheduler` 테스트에서는 전체 테스트용 비활성화 설정으로 꺼지는 가격 감시·KIS 재시도·Feed 보정 스케줄을 안전한 미래 시각으로만 재정의하여 등록 여부를 확인한다. 운영 설정이나 외부 연동 동작은 변경하지 않는다.

## 요구사항

- [x] **MSAS-001** 1단계 커밋 `471eb1a4`의 Profile·scheduling infrastructure 변경을 기준선으로 삼고, 현재 대상 클래스의 기존 Profile 표현식을 먼저 목록화한다. 이미 `prod,scheduler` 또는 비운영 호환 조건을 만족하는 클래스에는 동일한 `@Profile`을 중복 추가하지 않는다.
- [x] **MSAS-002** `prod,web`에서는 다음 운영 실행부가 Bean으로 생성되거나 실행되지 않는다.
  - Bithumb WebSocket/REST ticker Feed, leader election, Feed 연결상태 보정 및 Feed 설정
  - 코인 가격 snapshot·가격 변동 감시 등 시세 감시 실행부
  - KIS 분봉·일봉 Client, 수집기, 재시도, Import writer 및 수집 결과로 재생 세션을 확정하는 실행부
  - `LimitOrderTriggerListener`, `ExitPlanTriggerListener`, `LimitOrderFillExecutorConfig`, `LimitOrderFillExecutorRouter`
- [x] **MSAS-003** `prod,scheduler`에서는 MSAS-002의 실행부가 누락된 생성자 의존성 없이 생성되고, `@Scheduled`, `ApplicationReadyEvent`, `@PostConstruct`, 이벤트 listener, Executor lifecycle 등 실제 실행 진입점이 Scheduler 역할에서만 등록·실행된다.
- [x] **MSAS-004** 다음 공통 경계는 Scheduler 전용으로 바꾸지 않는다: `PriceStore`, `StockPriceStreamService`, `LimitOrderFillService`, `ExitPlanFillService`, `SseEmitterRegistry`, Entity/Repository/DB 경계, 공통 체결·원장·조회 Service. 공통 Bean의 생성자 그래프가 `prod,web`과 `prod,scheduler`에서 각각 필요한 방식으로 유지된다.
- [x] **MSAS-005** 실제 호출·생성자 의존관계 검토에서 Scheduler 실행부와 공통 Web 그래프가 분리되었음을 확인한다. Bithumb 가격 저장 후의 JVM 내부 `CryptoPriceUpdatedEvent`와 자동 체결 listener, KIS 수집 writer의 DB 저장, 공통 체결 Service의 교육 경로를 Profile 변경으로 대체하거나 재설계하지 않는다.
- [x] **MSAS-006** local/test의 Fake Feed·시뮬레이터·기존 비운영 Profile 조건은 유지한다. 뉴스·공시·랭킹 등 1단계에서 처리된 기존 Scheduler 작업은 새 리팩터링 대상에 포함하지 않는다.

## 비즈니스 규칙

- Profile은 Bean 생성과 scheduling/event 실행 경계를 제어할 뿐 주문 체결 규칙, Transaction, Lock, Repository 쿼리, 시세 수집 로직을 대체하지 않는다.
- `prod,web`에서 공통 `PriceStore`가 존재하는 것은 허용하지만, Web 프로세스가 Bithumb Feed·ticker·가격 감시·KIS 수집/Import·자동 체결 Trigger/Executor의 실행 주체가 되어서는 안 된다.
- `StockPriceStreamService`와 `SseEmitterRegistry`는 양쪽에 공통 Bean으로 남긴다. Web에서 scheduling infrastructure가 비활성화되어 이들의 scheduled method가 등록되지 않는지는 검증하되, 클래스를 어느 한 Profile로 이동하지 않는다.
- `LimitOrderFillService`와 `ExitPlanFillService`는 Scheduler 자동 체결과 Web 교육 가격 틱 정산이 공유하므로 양쪽 생성자 그래프에 남긴다. 자동 체결 Trigger/Executor만 Scheduler 실행부로 제한한다.
- Repository, Entity, DB 원장과 공통 조회 Service는 두 프로세스가 공유하는 계약이다. 이를 Scheduler 전용으로 제한하거나 저장 경로를 변경하지 않는다.

## 범위 제외

- `471eb1a4`에서 이미 올바르게 적용된 `@Profile`의 중복 추가와 대규모 Profile 재분류
- 주문 체결 규칙, Transaction 경계, Lock 순서·정책, Repository 쿼리와 DB 스키마/Flyway
- Bithumb/KIS 시세 수집 알고리즘·외부 API 요청·Import 데이터 처리 로직 자체의 변경
- Redis IPC, Pub/Sub/Stream 도입, `CryptoPriceUpdatedEvent`의 프로세스 간 전달
- SSE payload·emitter 구조·Web/Scheduler 간 SSE 전달 구조
- `PriceStore`, `StockPriceStreamService`, `LimitOrderFillService`, `ExitPlanFillService`, `SseEmitterRegistry`와 공통 체결·원장·조회 Service의 Scheduler 전용 전환
- 뉴스·공시 수집, 주식 개장 전 피드백, 코인 요약·브리핑, 랭킹 재구성 등 1단계 결과를 전제로 하는 기존 Scheduler 작업
- API route, 요청·응답·오류 계약, 신규 기능, 엔티티·Repository 추가, 실제 운영 설정값·운영 데이터·credential·보안정보 기록
- 이번 spec 작성 단계의 production 코드와 test 코드 수정
- 사용자 요청 파일 `docs/issue-589-profile-separation-result.md` 수정·삭제

## 완료 조건

- [x] 1단계 기준선과 현재 대상 Bean의 Profile 상태를 표로 대조하고, 중복 `@Profile` 후보가 없음을 확인한다.
- [x] 대상 호출·생성자 그래프에 Bithumb Feed, 시세 감시, KIS 수집/Import, 자동체결 Trigger/Executor의 각 실행 진입점과 공통 경계를 빠짐없이 기록한다.
- [x] `prod,web` ApplicationContext 검증에서 MSAS-002 Bean과 Scheduler scheduling infrastructure·대상 scheduled/event 실행 등록이 없고, 공통 경계 Bean은 생성됨을 확인한다.
- [x] `prod,scheduler` ApplicationContext 검증에서 MSAS-002 Bean과 대상 실행 진입점이 생성되고, 생성자 의존성 누락·중복 구현체 없이 기동됨을 확인한다.
- [x] local/test에서 Fake Feed·시뮬레이터의 기존 생성·실행 조건이 유지됨을 확인한다.
- [x] API/DB/Repository/체결 규칙/Lock/Transaction/수집 로직/Redis IPC/SSE 구조의 변경이 없음을 diff로 확인하고, 문서에 보안정보·실제 설정값·운영 데이터·credential이 없음을 확인한다.
- [x] 구현 시 `./gradlew spotlessApply`, 대상 Profile/context 테스트, `./gradlew build`를 실행하고 각 결과와 외부 연동 미검증 범위를 구분해 보고한다.
