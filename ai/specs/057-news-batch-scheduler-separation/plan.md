# Plan: Issue #589 2-2 — 뉴스·공시 수집 및 배치 Scheduler 역할 분리

## 관련 문서

- Spec: `./spec.md`
- PRD: `../../prd.md` — FEED-001, FEED-008·009, FEED-010·011, RANK-001·002는 §3 완료이며 이번 변경은 기능 제공 범위를 바꾸지 않음
- Spec 규칙: `../README.md`
- 선행 1단계: `../055-web-scheduler-role-separation/spec.md`, `plan.md`, `tasks.md`
- 선행 2-1단계: `../056-market-autofill-scheduler-separation/spec.md`, `plan.md`, `tasks.md`
- 코드 규칙: `../../../docs/conventions/code.md`
- 아키텍처: `../../adr/0002-architecture.md`

## 기준선과 중복 방지

현재 기준선은 `471eb1a4`의 1단계 Profile 변경과 그 이후의 2-1단계 검증 변경이 포함된 worktree다. 다음 대상은 현재 코드에서 이미 `!prod | (prod & scheduler)`가 적용되어 있으므로 동일한 `@Profile`을 다시 붙이지 않는다.

| 대상 | 현재 lifecycle | 057 계획 |
|---|---|---|
| `FeedbackBatchService` | 개장 전 `@Scheduled` | 기존 조건·로직 유지, context 등록 검증 |
| `CryptoFeedbackBatchService` | 코인 요약/브리핑 `@Scheduled` | 기존 조건·로직 유지, context 등록 검증 |
| `PeerStatsBatchService` | 주식·코인 집단 비교 `@Scheduled` 2개 | 기존 조건·로직 유지, 두 메서드 등록 검증 |
| `NewsCollectionService` | 뉴스·공시 `@Scheduled` 2개, 내부 종목 수집 호출 | 기존 조건·collector 그래프 검증 |
| `RankingRebuildService` | `ApplicationReadyEvent` 1개와 정기 `@Scheduled` 1개 | 기존 조건·기동/정기 등록 검증 |

055/056에서 이미 분리·검증한 시세 Feed, KIS collector, 자동체결 listener/executor, `CryptoPriceMoveWatcher` 및 `CryptoWatchLock`은 057에서 재분류하지 않는다. 다만 `NewsCollectionService`가 `CryptoPriceMoveWatcher`의 생성자에 들어가는 현재 그래프가 Web에서 scheduler 실행부로 이어지지 않는지 확인한다.

## Profile·Bean 매트릭스

| 묶음 | 현재 조건 | `prod,web` 기대 | `prod,scheduler` 기대 |
|---|---|---|---|
| 피드백/수집/랭킹 실행 서비스 | 대상 5개 모두 scheduler 조건 | 없음 | 각 1개 |
| 피드백 배치 설정 | `FeedbackBatchConfig` scheduler 조건 | 없음 | properties와 함께 생성 |
| 랭킹 재구성 설정 | `RankingRebuildConfig` scheduler 조건 | 없음 | properties와 함께 생성 |
| 외부 뉴스/공시 설정 | `NewsApiPropertiesConfig` scheduler 조건 | 없음 | Naver/DART properties 생성 |
| 뉴스 collector | Naver는 scheduler 또는 비운영 `news-real`, DART는 scheduler, Fake는 비-prod | 운영 외부/Fake collector 없음 | 외부 collector 각 1개 |
| collector 보조 | `DartCorpCodeRegistry`, `NewsSearchQueryBuilder`, `NewsTitleFilter` scheduler 조건 | 없음 | 각 1개 |
| 실행 lock | `FeedbackBatchLock`, `RankingRebuildLock` scheduler 조건 | 없음 | 각 1개 |
| 공통 조회/생성 지원 | `FeedbackCryptoConfig`, `NewsCollectionPropertiesConfig` 및 뉴스/브리핑 조회 그래프 | 필요한 공통 Bean 생성 | 필요한 공통 Bean 생성 |

`FeedbackBatchProperties`·`RankingRebuildProperties`·Naver/DART properties는 각각 scheduler 전용 config를 통해 생성되는지 확인한다. 반대로 `FeedbackCryptoProperties`와 `FeedbackNewsProperties`는 Web 조회 서비스가 직접 사용하므로 config를 scheduler 전용으로 확장하지 않는다.

## 호출·생성자·lifecycle 감사

```text
NewsCollectionService
  → NewsCollector / DisclosureCollector
  → InstrumentService
  → MarketNewsItemRepository
  → FeedbackBatchLock

FeedbackBatchService / CryptoFeedbackBatchService
  → Instrument·Replay·News Summary·Market Briefing·LLM 통계 서비스
  → FeedbackBatchLock

PeerStatsBatchService
  → PriceMoveEventRepository / PriceMovePeerStatRepository
  → HolderPopulationQueryService / StockReplayService
  → FeedbackBatchLock

RankingRebuildService
  → TradeService / AccountService
  → RankingStore
  → RankingRebuildLock
```

각 시작점에서 생성자 주입, `@Scheduled`, `@EventListener(ApplicationReadyEvent.class)`, 선택 collector 구현체를 대조한다. `prod,web`에서는 scheduler 전용 직접 의존성의 부재가 Web 조회 그래프의 실패로 이어지지 않아야 하며, `prod,scheduler`에서는 외부 collector·properties·lock이 모두 충족되어야 한다.

현재 코드는 Spring Batch가 아니라 Spring Scheduling을 사용한다. `build.gradle`의 의존성 목록에 Spring Batch starter가 없고 코드에 JobRepository·JobLauncher·Batch configuration이 없으므로, 실행 경계 검증 대상은 `ScheduledAnnotationBeanPostProcessor`, `ScheduledTaskHolder`, ApplicationReady listener다.

## API 설계

API endpoint, 요청 DTO, 응답 DTO, 오류 코드의 추가·수정·삭제가 없다. `ai/api-routes.md`와 `docs/api/`는 갱신하지 않는다.

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| 해당 없음 | 해당 없음 | 해당 없음 | 해당 없음 | 운영 Bean 생성·스케줄/event 등록 경계만 다루는 spec |

## 입력 명세

HTTP 입력은 없다. 역할 선택은 기존 `prod,web` 또는 `prod,scheduler` Profile 조합이며, 실제 cron·API key·credential 값은 이 문서에 기록하지 않는다.

## 데이터 모델

Entity, Repository query, DB schema, Flyway migration, Redis key·payload, API 응답 모델 변경 없음. 기존 뉴스/공시 저장 및 피드백·랭킹 산출 로직도 변경하지 않는다.

## 구현 순서

1. PRD ID와 055/056의 범위를 대조하고, 현재 다섯 서비스 및 관련 config/collector/lock의 Profile·lifecycle 매트릭스를 고정한다.
2. 대상별 생성자·호출자·외부 collector 선택·lock 주입을 감사해 `prod,web` 잔여 생성 경로와 `prod,scheduler` 누락 의존성을 구분한다. 이때 이미 올바른 1단계 조건은 수정 후보에서 제거한다.
3. 감사에서 실제 누락이 발견된 경우에만 직접 실행 Bean/config/collector/lock에 최소 조건을 보정하고, Web 공통 properties·조회 서비스를 유지한다. 누락이 없으면 production 변경 없이 검증만 보강한다.
4. 역할별 ApplicationContext에서 다섯 서비스·관련 Bean 생성 여부, 두 종류의 scheduled 등록, 랭킹 기동 listener 등록을 검증한다. local/test와 `news-real` collector 선택도 회귀 확인한다.
5. 최종 diff에서 API/DB/비즈니스/lock 정책과 민감정보 유입이 없는지 확인하고, Spring Batch를 추가하지 않았음을 확인한다.

## 테스트 계획

- 단위: `ApplicationContextRunner`로 collector와 config의 Profile 조합을 검증한다. 기존 `NewsCollectorProfileTest`, `DisclosureCollectorProfileTest`, `SchedulingConfigProfileTest`의 의미를 재사용하며 기존 test 파일은 수정하지 않는다.
- 통합: 2-2 전용 `ProdWebNewsBatchContextIntegrationTest`와 `ProdSchedulerNewsBatchContextIntegrationTest`에서 다섯 대상 서비스, 관련 config/lock/collector를 확인한다. `prod,web`에서는 대상 scheduled/event가 없고 공통 조회 Bean이 있으며, `prod,scheduler`에서는 대상 scheduled 메서드와 `RankingRebuildService`의 ApplicationReady listener가 등록되는지 검증한다.
- 회귀: local/test Fake collector와 `news-real` 외부 collector 선택, 대상 서비스 단위/통합 테스트를 실행한다. 실제 외부 뉴스·공시 API, 운영 Redis/DB, LLM 호출 성공을 context 테스트 통과로 주장하지 않는다.
- 범위 확인: `git diff`로 `build.gradle`에 Spring Batch가 추가되지 않았고 API/DB/운영 설정값/credential/운영 데이터가 문서·코드에 유입되지 않았는지 확인한다.

## 완료 검증 명령

```text
./gradlew spotlessApply
./gradlew test --tests "com.finplay.api.ProdWebProfileContextIntegrationTest" --tests "com.finplay.api.ProdSchedulerProfileContextIntegrationTest" --tests "*NewsCollectorProfileTest" --tests "*DisclosureCollectorProfileTest" --tests "*Profile*"
./gradlew build
```

위 명령은 구현 단계의 계획이며 현재 spec 작성 단계에서는 실행하지 않는다. 실제 테스트 이름과 추가된 검증 범위에 맞춰 구현 시 조정한다.
