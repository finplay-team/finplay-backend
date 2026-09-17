# Spec: Issue #589 2-2 — 뉴스·공시 수집 및 배치 Scheduler 역할 분리

## 개요

Issue #589의 1단계와 2-1단계에서 정한 `prod,web`·`prod,scheduler` 경계를 뉴스·공시 수집, AI 피드백 배치, 집단 비교 집계, 랭킹 재구성 실행부에 적용하고 실제 생성·실행 경계를 검증한다. 대상은 `FeedbackBatchService`, `CryptoFeedbackBatchService`, `PeerStatsBatchService`, `NewsCollectionService`, `RankingRebuildService`와 이들의 Profile·설정·collector·분산 lock 의존관계다.

현재 대상 서비스와 핵심 Scheduler 전용 Bean에는 이미 `prod,scheduler` 조건이 적용되어 있다. 따라서 이번 spec은 1단계에서 완료된 Profile을 중복 수정하지 않고, 누락된 직접 의존성만 확인·보정하며, Web 조회 경로가 공유하는 설정과 서비스를 Scheduler 전용으로 잘못 제한하지 않는 것을 목표로 한다.

현재 구현의 “배치”는 Spring Batch Job이 아니라 Spring Scheduling의 `@Scheduled` 메서드와 `ApplicationReadyEvent` 기동 훅이다. `build.gradle`에는 Spring Batch 의존성이 없고 JobRepository·JobLauncher·Batch metadata infrastructure도 존재하지 않는다. 이번 spec에서는 Spring Batch 도입이나 기존 작업의 Spring Batch 전환을 결정하지 않는다.

## PRD 추적

| PRD 요구사항 | PRD 수용 범위와 현재 상태 | 이번 spec의 관계 |
|---|---|---|
| FEED-001 | 뉴스·공시 수집, 제목·언론사·원문 URL·발행시각 중심 저장, 본문 미저장 — §3 완료 | 수집 실행 Bean의 운영 역할 경계만 검증하며 수집 계약·저장 알고리즘은 변경하지 않음 |
| FEED-008·FEED-009 | 종목 뉴스 요약·개장 전 브리핑 — §3 완료 | 코인/주식 피드백 배치의 생성·스케줄 등록만 검증하며 API 계약은 변경하지 않음 |
| FEED-010·FEED-011 | 반사실·집단 비교 및 확정 집계 — §3 완료 | `PeerStatsBatchService`의 Scheduler 경계와 lock 의존성만 검증하며 집계 규칙은 변경하지 않음 |
| RANK-001·RANK-002 | 시장별 Redis ZSET 랭킹 조회 — §3 완료 | `RankingRebuildService`의 기동 훅·정기 작업 경계만 검증하며 조회 계약·score 규칙은 변경하지 않음 |

이 작업은 위 요구사항의 기능 제공 범위를 바꾸지 않는 Profile·context 문서화/검증 계획이다. 따라서 PRD §3 구현 현황 행을 완료 상태로 갱신하지 않는다.

## 사용자 시나리오

- 운영자는 `prod,web` 프로세스에서 뉴스·공시 조회와 피드백·랭킹 조회 API를 사용할 수 있고, 수집·배치·랭킹 재구성 작업이 생성되거나 실행되지 않음을 확인할 수 있다.
- 운영자는 `prod,scheduler` 프로세스에서 뉴스·공시 collector와 피드백·집단 비교·랭킹 재구성 작업이 필요한 의존성과 함께 생성되고, 스케줄 또는 기동 훅으로 등록되는 것을 확인할 수 있다.
- 개발자는 local/test의 Fake collector와 기존 비운영 Scheduler 동작을 유지한다.
- 검토자는 기존 055/056에서 이미 분리된 대상에 동일한 `@Profile`을 중복 추가하지 않고 실제 생성자·호출자·lifecycle에 근거해 변경 범위를 판단할 수 있다.

## 요구사항

- [x] **NBS-001** 현재 기준선에서 다섯 대상 서비스의 기존 Profile과 `@Scheduled`·`ApplicationReadyEvent` 진입점을 목록화한다. `FeedbackBatchService`, `CryptoFeedbackBatchService`, `PeerStatsBatchService`, `NewsCollectionService`, `RankingRebuildService`가 이미 `!prod | (prod & scheduler)` 조건을 갖는 경우 동일 조건을 중복 추가하지 않는다.
- [x] **NBS-002** `prod,web`에서는 다섯 대상 서비스, `FeedbackBatchConfig`, `RankingRebuildConfig`, Scheduler 전용 News API 설정, `FeedbackBatchLock`, `RankingRebuildLock`, 외부 뉴스·공시 collector와 그 직접 실행 보조 Bean이 생성되지 않는다.
- [x] **NBS-003** `prod,scheduler`에서는 다섯 대상 서비스와 필요한 config·properties·collector·lock이 각각 하나로 생성되고, 네 개의 피드백/수집 스케줄과 랭킹 재구성의 정기 스케줄 및 `ApplicationReadyEvent` 기동 훅이 등록된다. 등록 확인은 cron 값 자체를 문서에 복사하지 않고 메서드와 property placeholder 기준으로 한다.
- [x] **NBS-004** `FeedbackCryptoConfig`, `NewsCollectionPropertiesConfig`, `FeedbackCryptoProperties`, `FeedbackNewsProperties`, `NewsMatcher`, `MarketBriefingService`, `InstrumentNewsSummaryService`처럼 Web 조회/응답 경로와 배치가 공유하는 Bean은 양쪽에서 필요한 생성자 그래프를 유지한다. 이 공통 경계를 scheduler 전용으로 바꾸지 않는다.
- [x] **NBS-005** Naver/DART 외부 collector는 `prod,scheduler`에서만 선택되고, Fake collector는 기존 local/test 조건을 유지하며 `news-real` 비운영 선택도 회귀하지 않는다. credential 값은 테스트·문서·로그에 기록하지 않는다.
- [x] **NBS-006** Profile 경계 외에는 뉴스·공시 저장 내용, AI 서술/요약 규칙, 집단 비교 계산, 랭킹 score·ZSET·조회 API, DB/Flyway/Repository, Redis key·lock 정책을 변경하지 않는다. Spring Batch 의존성·Job infrastructure를 새로 추가하지 않는다.

## 비즈니스 규칙

- Profile은 Bean 생성과 lifecycle 등록 경계를 제어할 뿐 수집·요약·집계·랭킹의 비즈니스 규칙이나 트랜잭션을 대체하지 않는다.
- `FeedbackBatchLock`은 뉴스/공시·주식 피드백·코인 피드백·집단 비교 작업이 공유하는 Scheduler 실행 lock이므로 대상 작업과 같은 운영 역할에서만 생성한다. lock key·TTL·획득 순서는 이번 범위에서 재설계하지 않는다.
- `RankingRebuildLock`은 랭킹 재구성 실행부의 운영 lock이며 조회용 `RankingStore`와 혼동하지 않는다. `RankingStore`와 랭킹 조회 서비스는 Web 공통 경계로 유지한다.
- `NewsCollectionService.collectForInstrument`와 같은 내부 호출 경로가 있더라도 Web 프로세스가 collector를 실행하는 진입점으로 해석하지 않는다. Web 조회는 저장된 뉴스/공시를 읽고 Scheduler가 수집한다.
- local/test Fake와 비운영 외부 collector 선택 조건은 운영 Profile 분리보다 우선해 기존 개발·테스트 경로를 보존한다.
- 이번 spec에서 배치라는 용어는 현재 코드의 Scheduler 작업을 뜻한다. Spring Batch의 재시작, Job metadata, chunk/step, JobRepository 의미를 새로 부여하지 않는다.

## 범위 제외

- 471eb1a4에서 이미 적용된 운영 Profile과 055/056에서 이미 검증한 Bithumb Feed·KIS 수집·시세 감시·자동체결 경계의 중복 수정
- `CryptoPriceMoveWatcher`·`CryptoWatchLock`의 역할 분리 자체. 다만 `NewsCollectionService`와의 생성자 그래프가 깨지지 않는지만 교차 확인한다.
- 뉴스·공시 외부 API 요청 형식, 검색어·필터 알고리즘, 수집 재시도, 중복 저장, 본문 처리·저작권 정책 변경
- AI 프롬프트·LLM 호출·요약/브리핑 문장, 가격 변동 카드·반사실·집단 비교 계산, 랭킹 score 계산·ZSET 자료구조 변경
- API route, 요청·응답·오류 계약, Controller, Entity/Repository, DB schema/Flyway migration, Redis key/payload, SSE/IPC
- Spring Batch starter, JobRepository, JobLauncher, Batch metadata schema 또는 `@EnableBatchProcessing` 도입
- 운영 cron 값, API key, token, credential, 환경변수 값, 운영 DB/Redis 데이터 기록
- 이번 spec 작성 단계의 production Java와 기존 test 파일 수정
- 사용자 요청 파일 `docs/issue-589-profile-separation-result.md` 읽기·수정·삭제

## 완료 조건

- [x] 1단계 기준선과 현재 코드의 다섯 대상 서비스·관련 config/collector/lock Profile을 대조하고, 이미 분리된 항목의 중복 수정이 없음을 문서와 diff에서 확인한다.
- [x] `prod,web` context에서 대상 실행 Bean·외부 collector·Scheduler 전용 lock/config와 대상 scheduled/event 등록이 없고, Web 조회에 필요한 공통 Bean은 생성됨을 확인한다.
- [x] `prod,scheduler` context에서 대상 실행 Bean과 생성자 의존성이 누락 없이 생성되고, 대상 `@Scheduled` 메서드와 랭킹 `ApplicationReadyEvent` listener가 등록됨을 확인한다.
- [x] local/test와 `news-real`의 Fake/외부 collector 선택 조건이 유지됨을 확인한다.
- [x] API/DB/Repository/수집·집계·랭킹 규칙/lock 정책의 변경이 없고 Spring Batch 의존성·infrastructure가 추가되지 않았음을 diff로 확인한다.
- [x] 민감한 설정값·credential·운영 데이터가 spec, plan, tasks, run-log에 기록되지 않았음을 확인한다.
