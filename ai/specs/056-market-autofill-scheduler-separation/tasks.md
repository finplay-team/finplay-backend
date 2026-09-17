# Tasks: Issue #589 2단계 — 시세·수집·자동체결 Scheduler 역할 분리

- [x] **1. 1단계 기준선과 대상 역할 매트릭스 확정**
  - `471eb1a4`의 기존 `@Profile`, scheduling infrastructure, context 테스트를 기준으로 Bithumb Feed·가격 감시·KIS 수집/Import·자동체결 Trigger/Executor의 현재 조건을 목록화한다.
  - 이미 올바른 조건을 가진 Bean에 동일한 `@Profile`을 중복 추가하지 않고, 뉴스·공시·랭킹 작업은 새 대상에서 제외한다.

- [x] **2. 호출·생성자·실행 lifecycle 의존관계 감사**
  - `@Scheduled`, `@EventListener`, `ApplicationReadyEvent`, `@PostConstruct`, Executor lifecycle, `ObjectProvider`와 생성자 주입을 대상별로 추적한다.
  - Web에서 대상 실행부로 이어지는 잔여 생성·호출 경로와 Scheduler에서 누락되는 필수 의존성을 구분하고, `PriceStore`·공통 체결/원장/조회 그래프는 보존 목록으로 고정한다.

- [x] **3. 잔여 Profile 경계만 최소 보정**
  - 감사 결과 실제 누락이 있는 직접 Bean/config/lifecycle에만 Scheduler 조건을 추가·수정한다.
  - `PriceStore`, `StockPriceStreamService`, `LimitOrderFillService`, `ExitPlanFillService`, `SseEmitterRegistry`, Repository/DB 및 공통 Service는 수정 대상에서 제외하고 local/test 조건도 유지한다.

- [x] **4. 역할별 context·scheduled/event 등록 검증**
  - `prod,web`에서 대상 Bean과 scheduling/event 실행 등록이 없고 공통 Bean은 생성되는지 확인한다.
  - `prod,scheduler`에서 대상 Bean과 실행 진입점이 생성되고 생성자 의존성 누락 없이 기동되는지 확인한다.
  - common Bean의 scheduled method가 Web에서 등록되지 않는 조건과 Bean 자체의 공통 생성을 함께 검증한다.

- [x] **5. 비운영 회귀와 범위·품질 게이트 확인**
  - local/test Fake·시뮬레이터와 1단계 역할 경계 회귀를 확인한다.
  - API/DB/Repository query/체결 규칙/Transaction/Lock/수집 로직/Redis IPC/SSE 구조 변경과 민감정보 유입이 없는지 diff를 점검한다.
  - `./gradlew spotlessApply`와 대상 테스트, `./gradlew build`를 실행하고 외부 Bithumb/KIS 및 다중 프로세스 전달 미검증을 별도로 기록한다.
