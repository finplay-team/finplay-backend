# Plan: Spring Profile 기반 Web/Scheduler Bean 역할 분리

## 관련 문서

- Spec: `./spec.md`
- PRD: `../../prd.md` — Issue #589 전용 요구사항 ID 없음, §3 구현 현황 갱신 대상 아님
- Spec 규칙: `../README.md`
- 코드 규칙: `../../../docs/conventions/code.md`
- 아키텍처: `../../adr/0002-architecture.md`
- 참고 분석: `../../../docs/issue-589-web-scheduler-code-analysis.md` — 읽기 전용 참고, 수정하지 않음

## 역할 및 Profile 설계

운영 기동은 아래 두 조합을 정식 경로로 둔다.

| 역할 | 활성 Profile | 생성·실행 범위 | 생성하지 않을 범위 |
|---|---|---|---|
| Web | `prod,web` | HTTP Controller, 인증/Security, 사용자 요청·조회 그래프, Web 진입점 | Feed, 수집기, 배치, 랭킹 전체 재구성, 자동 체결 Trigger/Executor, Scheduler infrastructure |
| Scheduler | `prod,scheduler` | Feed, 수집기, 배치, 랭킹 전체 재구성, 자동 체결 Trigger/Executor, scheduling infrastructure | Web 전용 HTTP/SSE 진입점 |
| 비운영 호환 | 기존 local/test 프로필 | 기존 Fake·시뮬레이터·개발용 동작 | 새로운 운영 역할 판정으로 기존 동작을 제거하지 않음 |

Profile 조건은 기존 `prod`, `!prod`, `crypto-real` 등 환경 조건을 무시하고 덮어쓰지 않는다. 운영 전용 Bean에는 Scheduler 또는 Web 역할 조건을 결합하고, 비운영 Fake·시뮬레이터는 기존 조건을 보존한다. `@Profile("prod")`와 `@Profile("prod | crypto-real")`처럼 양쪽 운영 역할에서 동시에 활성화되는 조건은 호출자와 생성자 의존성을 확인한 뒤 역할 조건을 추가한다.

스케줄링은 애플리케이션 진입점의 무조건 `@EnableScheduling`에 맡기지 않고 역할 조건이 있는 설정으로 둔다. 계획상 `prod,scheduler`와 비운영 프로필에서는 활성화하고 `prod,web`에서는 비활성화한다. 이를 통해 `StockPriceStreamService`를 클래스 전체로 이동하지 않아도 Web 역할에서 그 클래스의 `@Scheduled` 메서드가 등록되지 않게 한다.

## Bean 분류 및 변경 경계

### Scheduler 전용으로 제한할 후보

- Bithumb WebSocket/REST ticker Feed, leader election, 연결 상태 보정
- KIS 분봉·일봉 수집, 당일 재시도, import writer, 주식 재생 세션 확정
- 뉴스·공시 수집, 주식 개장 전 배치, 코인 요약·브리핑·변동 감시, 집단 비교 배치
- 코인 가격 snapshot 기록
- `RankingRebuildService`와 재구성 lock 및 기동 훅
- 지정가/OCO 자동 체결 Trigger listener와 `LimitOrderFillExecutorRouter`

각 후보는 단순히 `@Scheduled` 존재 여부만 보지 않고 `ApplicationReadyEvent`, `@PostConstruct`, optional `ObjectProvider`, listener와 생성자 의존성을 함께 확인한다. 기존 `prod` 전용 구현체는 `prod,scheduler`로 한정하되, local/test Fake와 시뮬레이터의 기존 경로는 유지한다.

### Web 전용으로 제한할 후보

- REST Controller와 전역 Web 오류 처리 진입점
- 인증·Security·CORS·사용자 요청으로만 진입하는 Web 그래프
- `StockPriceSseController` 같은 SSE 연결 진입점

SSE 연결을 실제로 전달·구독하는 구조는 이번 단계에서 바꾸지 않는다. Web 전용으로 분류할 수 없는 `StockPriceStreamService`와 `SseEmitterRegistry`는 아래 공통 경계 원칙을 적용한다.

### 공통·보류 경계

| 항목 | 계획 |
|---|---|
| `PriceStore` | 양쪽에서 Bean을 생성한다. Redis 읽기·쓰기, `ApplicationEventPublisher`, stale 판정의 동작은 변경하지 않는다. |
| `LimitOrderFillService` | 양쪽 생성자 그래프에서 사용 가능하게 둔다. 자동 체결과 교육 정산의 비즈니스 로직·트랜잭션은 변경하지 않는다. |
| `StockPriceStreamService` | 전체 클래스에 Web/Scheduler 중 하나의 Profile을 붙이지 않는다. Web 메서드와 예약 업데이트를 분리하거나 IPC를 도입하는 일은 후속 단계다. |
| `SseEmitterRegistry` | 현재 프로세스 내부 emitter 저장 구조를 유지한다. Scheduler가 Web emitter를 직접 보유한다고 해석하지 않으며, cross-process 전달은 설계하지 않는다. |
| Store·Repository·조회 Service | 양쪽 호출 관계를 확인해 공통 유지한다. 저장소나 쿼리를 Scheduler 전용으로 제한하지 않는다. |

이 보류 경계 때문에 1단계 완료는 “모든 클래스가 한 역할에만 생성됨”이 아니라 “명확한 Web/Scheduler 전용 그래프의 생성 범위를 분리하고 혼합 Bean을 안전하게 보존함”으로 정의한다.

## API 설계

API endpoint, 요청 DTO, 응답 DTO, 오류 코드의 추가·수정·삭제가 없다. `ai/api-routes.md`와 `docs/api/`는 갱신 대상이 아니다.

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| 해당 없음 | 해당 없음 | 해당 없음 | 해당 없음 | Spring Bean 생성 Profile만 변경하는 계획 |

## 입력 명세

HTTP 입력 명세는 없다. 운영 기동 시 `spring.profiles.active`에 `prod,web` 또는 `prod,scheduler`를 지정하는 것이 역할 선택 입력이며, 환경변수·배포 매니페스트 자체를 이번 범위에서 변경하지 않는다.

## 데이터 모델

DB schema, Flyway migration, Entity, Repository query 변경 없음. Redis key·event payload·SSE payload·메시징 계약도 변경하지 않는다.

## 구현 순서

1. 현재 `@Profile`, `@Scheduled`, `@EnableScheduling`, startup/listener hook을 역할별 매트릭스로 고정한다.
2. scheduling infrastructure를 `prod,scheduler`와 비운영 프로필에서만 활성화하도록 최소 변경한다.
3. 명확한 Scheduler 전용 Bean에 Scheduler 조건을, 명확한 Web 전용 Bean에 Web 조건을 추가한다. 기존 `prod`/비운영 조건과 외부 구현체 선택 조건을 함께 보존한다.
4. `PriceStore`, `LimitOrderFillService`, `StockPriceStreamService` 및 SSE 혼합 경계는 공통/보류로 유지하고, 프로필 부여로 인한 생성자 그래프 단절을 만들지 않는다.
5. 두 운영 역할과 local/test의 context·scheduled task 등록을 검증한 뒤 포맷과 전체 빌드로 마무리한다.

## 테스트 계획

- 단위: Profile expression과 scheduling configuration의 활성/비활성 조건. `ApplicationContextRunner`로 `prod,web`, `prod,scheduler`, 비운영 프로필을 각각 검증한다.
- 슬라이스: 해당 없음. Controller API 계약이나 Repository 쿼리를 바꾸지 않는다.
- 통합: 역할별 ApplicationContext 기동 검증. `prod,web`에서 Scheduler 전용 Bean과 `ScheduledTaskHolder` 등록이 없고, `prod,scheduler`에서 Scheduler 전용 Bean과 scheduling infrastructure가 존재하는지 확인한다. 양쪽에서 `PriceStore`, `LimitOrderFillService`, `StockPriceStreamService`가 필요한 공통 그래프를 만족하는지도 확인한다.
- 회귀: 기존 local/test 프로필의 Fake·시뮬레이터와 현재 스케줄 검증 테스트를 실행한다. Bithumb 실수집·자동 체결 외부 동작의 성공을 이 테스트로 주장하지 않는다.

## 완료 검증 명령

```text
./gradlew spotlessApply
./gradlew test --tests "*WebScheduler*" --tests "*Profile*"
./gradlew build
```

대상 테스트 이름은 실제 추가 파일명에 맞춰 조정하되, 실행하지 못한 외부 연동 검증과 남은 SSE 프로세스 간 전달 위험은 완료 보고에 분리해 기록한다.
