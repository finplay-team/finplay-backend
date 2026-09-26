# Tasks: Issue #589 2-2 — 뉴스·공시 수집 및 배치 Scheduler 역할 분리

- [x] **1. PRD·선행 spec·번호 기준선 확정**
  - FEED-001, FEED-008·009, FEED-010·011, RANK-001·002의 현재 완료 상태와 이번 spec이 기능 범위를 바꾸지 않는다는 점을 기록한다.
  - 055/056에서 이미 분리된 Profile과 057의 대상 경계를 대조하고, 057 번호 충돌 확인 근거를 남긴다.

- [x] **2. 대상 실행부와 직접 의존성 감사**
  - 다섯 서비스의 `@Profile`, `@Scheduled`, `ApplicationReadyEvent`, 생성자와 호출자를 확인한다.
  - `FeedbackBatchConfig`, `RankingRebuildConfig`, `NewsApiPropertiesConfig`, 외부/Fake collector, `DartCorpCodeRegistry`, query builder/filter, `FeedbackBatchLock`, `RankingRebuildLock`의 생성 조건을 함께 대조한다.
  - `FeedbackCryptoConfig`·`NewsCollectionPropertiesConfig` 등 Web 공통 경계를 scheduler 전용 후보에서 제외한다.

- [x] **3. 잔여 Profile 경계만 최소 보정**
  - 감사 결과 누락된 production Profile 경계가 없어 production Java 수정은 수행하지 않았다.
  - 감사에서 실제 누락이 있을 때만 직접 실행 Bean/config/collector/lock을 보정한다.
  - 이미 1단계에서 분리된 다섯 서비스와 055/056 소유 대상에는 중복 조건을 추가하지 않는다.
  - Spring Batch 의존성·Job infrastructure는 추가하지 않는다.

- [x] **4. 운영 역할 context와 lifecycle 등록 검증**
  - `prod,web`에서 대상 Bean·collector·lock·scheduler/event 등록이 없고 공통 조회 그래프는 생성되는지 확인한다.
  - `prod,scheduler`에서 대상 Bean이 생성되고 모든 대상 scheduled 메서드 및 랭킹 기동 listener가 등록되는지 확인한다.
  - collector의 local/test Fake와 `news-real` 비운영 선택을 회귀 확인한다.

- [x] **5. 범위·민감정보·품질 게이트 확인**
  - API/DB/Repository/수집·집계·랭킹·lock 정책과 운영 설정값에 변경이 없는지 diff로 확인한다.
  - 문서에 credential·secret·운영 데이터가 없는지 확인한다.
  - 구현 단계에서 `spotlessApply`, 대상 테스트, `build`를 실행하고 외부 API·운영 인프라 미검증 범위를 분리해 기록한다.
