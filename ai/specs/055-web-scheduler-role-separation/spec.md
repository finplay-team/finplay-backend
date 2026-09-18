# Spec: Spring Profile 기반 Web/Scheduler Bean 역할 분리

## 개요

Issue #589 1단계는 운영 애플리케이션을 `prod,web`과 `prod,scheduler` 역할로 나누고, 각 프로세스에서 생성되는 Spring Bean의 범위를 분리한다. 목적은 사용자 HTTP/SSE 요청을 처리하는 Web 역할과 시세 수집·배치·자동 체결 트리거를 실행하는 Scheduler 역할이 같은 운영 Bean 그래프를 불필요하게 생성하지 않도록 하는 것이다.

이번 단계는 Bean 생성 조건과 스케줄링 인프라의 범위만 다룬다. 프로세스 사이의 시세·SSE 전달과 기존 비즈니스 동작은 이번 단계에서 설계하거나 변경하지 않는다.

PRD에는 Issue #589에 대응하는 별도 요구사항 ID가 아직 없다. 따라서 아래 `WSR-*` 식별자는 이 spec의 검증 항목이며, PRD의 구현 현황 행을 완료로 바꾸는 근거로 사용하지 않는다.

## 사용자 시나리오

- 운영자는 `prod,web` 프로필로 기동한 인스턴스에서 Web API와 주식 SSE 연결을 제공할 수 있다.
- 운영자는 `prod,scheduler` 프로필로 기동한 인스턴스에서 시세 Feed, 수집기, 배치, 랭킹 재구성, 자동 체결 트리거를 실행할 수 있다.
- 개발자는 기존 local/test 프로필의 Fake·시뮬레이터·테스트 실행 동작을 역할 분리 때문에 잃지 않는다.

## 요구사항

- [x] **WSR-001** 운영 역할은 `prod,web`과 `prod,scheduler`로 구분한다. 한 프로세스는 두 역할 중 하나를 활성화하며, 역할 Profile을 지정하지 않은 기존 운영 기동을 새로운 기능의 정상 경로로 간주하지 않는다.
- [x] **WSR-002** `prod,web`에서는 Web 전용 Controller·인증/Security·사용자 요청 그래프와 Web 진입점이 생성되고, Scheduler 전용 Feed·수집·배치·랭킹 재구성·자동 체결 Trigger/Executor Bean은 생성되지 않는다.
- [x] **WSR-003** `prod,scheduler`에서는 Scheduler 전용 Feed·수집·배치·랭킹 재구성·자동 체결 Trigger/Executor Bean과 스케줄링 인프라가 생성되고, Web 전용 HTTP/SSE 진입점 Bean은 생성되지 않는다.
- [x] **WSR-004** Web과 Scheduler가 실제 호출 그래프 또는 저장소를 공유하는 Bean은 한 역할에만 제한하지 않는다. 특히 `PriceStore`, `LimitOrderFillService`, `StockPriceStreamService`는 이번 단계에서 전체 클래스를 어느 한 Profile로 이동하지 않는다.
- [x] **WSR-005** `prod,web`에서는 Scheduler 전용 `@Scheduled` 작업이 등록·실행되지 않고, `prod,scheduler`에서는 Scheduler 작업이 등록될 수 있다. local/test에서는 기존 스케줄·Fake·시뮬레이터 동작을 보존한다.
- [x] **WSR-006** 두 운영 역할의 ApplicationContext가 필수 의존성 누락·중복 구현체·잘못된 외부 연동 Bean 생성 없이 기동 가능한 Bean 그래프를 가진다. 역할 분리만으로 API 요청·응답 계약이나 체결·수집 비즈니스 결과를 바꾸지 않는다.

## 비즈니스 규칙

- Profile은 운영 Bean 생성 범위를 제어하는 경계이며, 기존 service의 비즈니스 규칙·트랜잭션·Repository 쿼리를 대체하지 않는다.
- `@EnableScheduling`과 Scheduler 전용 scheduling infrastructure는 `prod,scheduler`에서만 활성화한다. 비운영 프로필의 기존 스케줄 동작은 유지한다.
- 현재 혼합 Bean은 다음 원칙을 따른다.

  | Bean | 1단계 결정 | 이유 |
  |---|---|---|
  | `StockPriceStreamService` | 공통 Bean으로 보류. 클래스 분리·메서드 이동·SSE 전달 변경을 하지 않음 | Web의 emitter/snapshot API와 예약 가격 업데이트가 한 클래스에 섞여 있음 |
  | `PriceStore` | 공통 Bean 유지 | Scheduler Feed가 쓰고 Web 조회가 읽으며, 현재 가격 이벤트는 프로세스 내부 이벤트임 |
  | `LimitOrderFillService` | 공통 Bean 유지. 자동 체결 비즈니스 로직을 수정하지 않음 | Scheduler 자동 체결과 Web 교육 가격 틱 정산이 같은 service와 Attribution 그래프를 사용함 |
  | `SseEmitterRegistry` 및 혼합 SSE 경계 | 최소 변경·판단 보류 | 메모리 emitter 목록만 관리하며 프로세스 간 전달 설계가 없음 |

- Scheduler에서 발생한 시세 이벤트를 Web 프로세스의 SSE emitter로 전달하는 경로는 아직 존재하지 않는 것으로 본다. 1단계의 완료 조건은 이 경로의 제공을 의미하지 않는다.
- local/test에서만 동작하는 Fake·시뮬레이터는 `prod,web`/`prod,scheduler` 분리와 별개로 기존 프로필 조건을 우선 보존한다.

## 범위 제외

- Redis Pub/Sub, Redis Streams, Kafka, RabbitMQ 등 메시징 도입·변경
- SSE 구조 변경, Web/Scheduler 프로세스 간 시세·이벤트 전달, emitter 공유
- Bithumb 수집 동작, 자동 체결 동작, `LimitOrderFillService` 비즈니스 로직 변경
- DB 스키마, Flyway 마이그레이션, Entity/Repository 로직 변경
- Terraform, Docker, AWS, CI/CD 및 배포 설정 변경
- Gradle·Spring 등 의존성 추가·교체·버전 변경
- 대규모 패키지 이동, 공통화·추상화 리팩터링, 신규 기능 추가
- Issue #589 사전 분석 문서 `docs/issue-589-web-scheduler-code-analysis.md` 수정

## 완료 조건

- [x] `prod,web` Bean context 검증에서 Web 전용 Bean이 존재하고 Scheduler 전용 Bean·스케줄 등록이 없음을 확인한다.
- [x] `prod,scheduler` Bean context 검증에서 Scheduler 전용 Bean과 스케줄링 인프라가 존재하고 Web 전용 HTTP/SSE 진입점이 없음을 확인한다.
- [x] 두 역할 모두 `PriceStore`, `LimitOrderFillService`, `StockPriceStreamService`의 공통/보류 결정을 지키며 ApplicationContext가 실패 없이 구성된다.
- [x] local/test 프로필의 기존 Fake·시뮬레이터·스케줄 관련 회귀 테스트가 통과한다.
- [x] API route, 요청·응답·오류 계약, DB 스키마, Repository 쿼리, Bithumb 수집·체결 결과가 변경되지 않았음을 diff로 확인한다.
- [x] 변경된 Java 파일에 대해 `./gradlew spotlessApply` 후 대상 테스트와 `./gradlew build`를 실행한다.
