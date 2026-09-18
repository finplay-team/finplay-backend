# Tasks: Spring Profile 기반 Web/Scheduler Bean 역할 분리

- [x] **1. 역할 Profile과 scheduling infrastructure 경계 정의**
  - `prod,web`, `prod,scheduler`, local/test의 활성 조건을 코드와 테스트에서 명시한다.
  - `@EnableScheduling`을 Scheduler 역할과 비운영 호환 경로에만 활성화하는 최소 구성을 마련한다.
  - 기존 `prod`, `!prod`, `crypto-real` 조건을 역할 조건과 결합할 때 외부 구현체 선택이 바뀌지 않도록 매트릭스를 고정한다.

- [x] **2. 명확한 Web 전용 Bean 생성 범위 제한**
  - HTTP Controller, 인증/Security, 사용자 요청 그래프, SSE 연결 진입점에 Web 역할 조건을 적용한다.
  - Scheduler 전용 Bean에 대한 간접 의존성을 새로 만들지 않고, `prod,scheduler` context에서 Web 진입점이 생성되지 않는지 검증한다.

- [x] **3. 명확한 Scheduler 전용 Bean 생성 범위 제한**
  - Feed·leader election·연결 상태 보정, 수집기·배치·랭킹 재구성, 자동 체결 Trigger/Executor 및 startup hook을 Scheduler 역할로 제한한다.
  - `prod,web` context에서 Scheduler 전용 Bean과 scheduled task가 생성되지 않고, `prod,scheduler` context가 필수 의존성 누락 없이 구성되는지 검증한다.

- [x] **4. 혼합 Bean 공통 유지와 최소 변경 검증**
  - `StockPriceStreamService`, `PriceStore`, `LimitOrderFillService`에 전체 Profile 이동을 적용하지 않는다.
  - SSE emitter의 프로세스 내부 구조, PriceStore 이벤트, 교육 체결·자동 체결 비즈니스 로직을 변경하지 않는다.
  - 공통 Bean 생성과 생성자 그래프가 두 운영 context에서 유지되는지 확인하고, 후속 IPC/SSE 분리 결정을 미확정 사항으로 기록한다.

- [x] **5. 회귀·품질 게이트와 범위 확인**
  - local/test Fake·시뮬레이터 회귀와 역할별 context 테스트를 실행한다.
  - `./gradlew spotlessApply`와 `./gradlew build`를 실행한다.
  - API route/계약, DB/Repository, 의존성, Terraform/Docker/AWS/배포 설정, 분석 문서, `src/` 범위 밖 변경이 없는지 최종 diff로 확인한다.
