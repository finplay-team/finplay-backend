# Spec: Issue #589 3단계 시세 이벤트 전달 사전 설계

## 개요

현재 Scheduler와 Web은 서로 다른 JVM으로 실행되며, Scheduler에서 발생한 시세 변경이 Web의 SSE 연결로 전달되는 IPC 경로는 없다. 이 문서는 Issue #589 3단계 구현에 앞서 현재 호출·Bean·프로세스 경계를 확인하고, Scheduler의 시세 이벤트를 여러 Web 인스턴스의 SSE 클라이언트로 전달하기 위한 후보와 구현 경계를 정의한다.

이번 문서는 분석·설계·구현 준비만 다룬다. Java, 설정, 의존성, Redis 메시징, SSE 구조는 변경하지 않는다.

## 현재 구조 분석

### 코인 시세

```text
Bithumb WebSocket 또는 REST ticker
  → PriceStore.saveTick()/recordObservation()
  → 공유 Redis 가격 상태 저장
  → JVM 내부 ApplicationEventPublisher
  → CryptoPriceUpdatedEvent
  → Scheduler의 LimitOrderTriggerListener / ExitPlanTriggerListener
```

- 운영 Bithumb feed와 ticker poller는 `prod & scheduler` 범위에서 실행된다.
- `PriceStore`의 가격 상태는 Redis를 통해 프로세스 간 공유되지만, `ApplicationEventPublisher` 이벤트는 발행한 JVM 안에서만 전달된다.
- `CryptoPriceUpdatedEvent`는 자동체결 listener가 소비하므로, Web으로 전달되는 외부 이벤트를 이 이벤트로 재발행하면 Web에서 자동체결이 재실행되거나 향후 루프가 생길 수 있다.
- 현재 Web은 코인 가격을 공통 `PriceStore`에서 REST 조회할 수 있다. 코인 SSE endpoint는 현재 제품 계약에 포함되어 있지 않으므로 코인 tick을 Web SSE로 전달하는 것은 이번 기본 설계 범위에 포함하지 않는다.

### 주식 SSE

```text
Scheduler JVM
  StockPriceStreamService.@Scheduled publishScheduledUpdates()
    → 새 가격/시장 상태 감지
    → Scheduler JVM의 SseEmitterRegistry 순회

Web JVM
  StockPriceSseController
    → Web JVM의 SseEmitterRegistry에 emitter 등록
    → 연결 시 snapshot 전송
```

- `StockPriceStreamService`는 snapshot 생성·emitter lifecycle·주기적 가격 감지·SSE 전송을 한 Bean에 함께 가지고 있다.
- Scheduler가 순회하는 registry와 Web이 보유한 registry는 JVM별 로컬 메모리이므로 서로 다른 목록이다. Scheduler에서 생성된 `price`·`status` 이벤트는 Web 클라이언트에 도달하지 않는다.
- `SseEmitterRegistry`의 heartbeat도 현재 scheduling infrastructure가 있는 프로세스의 scheduled method로 등록된다. Web이 emitter를 소유하고 Scheduler가 scheduling infrastructure를 소유하는 현재 분리는 Web emitter의 heartbeat 책임과 맞지 않는 위험이 있다.
- Web 재접속 시 Controller가 snapshot을 다시 보내며, 현재 계약에는 연결 중 누락된 과거 이벤트 replay가 없다.

## 사용자 시나리오

- Web 인스턴스 2대에 연결된 주식 SSE 클라이언트는 어느 Web 인스턴스에 연결되어도 Scheduler가 감지한 동일한 최신 가격·시장 상태 이벤트를 받는다.
- Web 인스턴스가 잠시 중단되었다가 재기동되면 SSE 재연결 시 최신 snapshot으로 상태를 복구한다.
- Scheduler의 자동체결은 Scheduler JVM의 로컬 `CryptoPriceUpdatedEvent` 경로를 계속 사용하며, Web 이벤트 전달 때문에 자동체결이 중복 실행되지 않는다.
- Redis 메시징 장애가 있어도 Scheduler의 기존 가격 저장과 자동체결 경로가 메시지 전달 실패 때문에 함께 중단되지 않는다.

## 요구사항

- [ ] Scheduler에서 승인된 주식 시세·시장 상태 이벤트를 Web 프로세스가 구독할 수 있다.
- [ ] Web 프로세스마다 동일한 이벤트를 받아 해당 프로세스의 로컬 SSE emitter에만 전송한다.
- [ ] Web 인스턴스 간 공유 deduplication 때문에 한 인스턴스의 클라이언트 이벤트가 사라지지 않는다.
- [ ] 외부 이벤트 수신이 `CryptoPriceUpdatedEvent` 또는 자동체결 listener를 재호출하지 않는다.
- [ ] Web 연결 시 기존 snapshot 계약으로 최신 상태를 복구할 수 있다.
- [ ] 이벤트 payload에 비밀값, 인증정보, 계좌정보, 주문정보, Redis 연결정보를 포함하지 않는다.
- [ ] 기존 `MarketPriceEvent`·`MarketStatusEvent` SSE 계약과의 호환 여부를 구현 전에 확정한다.
- [ ] Web과 Scheduler의 profile별 Bean 생성·스케줄 등록을 검증한다.

## 비즈니스 규칙

- 이벤트 전달은 최신 시세의 실시간 fan-out을 위한 경로이며 영속 이벤트 원장이나 주문 체결 명령 채널이 아니다.
- Scheduler가 자동체결에 사용하는 JVM 내부 `CryptoPriceUpdatedEvent`와 Web SSE 전달용 transport event는 서로 다른 경계와 소비자를 가진다.
- 여러 Web 인스턴스는 같은 transport event를 각각 받아야 한다. 한 Web 인스턴스만 소비하는 consumer-group semantics를 사용하면 다른 인스턴스의 SSE 클라이언트가 누락된다.
- 전달 중 유실된 이벤트는 재접속 snapshot으로 최신 상태를 복구한다. 과거 모든 tick의 정확한 재생은 이 단계의 목표가 아니다.
- event id는 market·symbol·source time 등 원천 이벤트의 안정적인 식별정보를 포함해야 하며, 발행 시각만으로 만들지 않는다.
- transport subscriber는 전송 실패·역직렬화 실패를 해당 이벤트 또는 emitter 단위로 격리하여 다른 client와 수집 작업을 중단시키지 않아야 한다.

## 범위 제외

- 이번 사전 작업에서 Java/YAML/properties/Gradle/test 코드를 수정하지 않는다.
- Redis Pub/Sub, Redis Streams, Kafka, RabbitMQ, SQS를 실제로 도입하지 않는다.
- Scheduler → Web IPC, SSE forwarding, heartbeat 책임 이동을 구현하지 않는다.
- Bithumb/KIS 수집, PriceStore 저장 규칙, 자동체결·Transaction·Lock·Repository를 변경하지 않는다.
- 코인 SSE endpoint를 부활시키거나 코인 `CryptoPriceUpdatedEvent`를 Web으로 전달하지 않는다.
- 재생 가능한 시세 이벤트 저장소나 장기 replay 기능을 추가하지 않는다.
- Docker, Terraform, AWS, ALB, 배포 구성은 변경하지 않는다.

## 완료 조건

- [ ] 현재 Scheduler → Web 단절 지점과 공통/전용 Bean 경계가 문서에 근거와 함께 기록된다.
- [ ] Pub/Sub, Streams, Kafka, RabbitMQ, SQS의 적합성·비용·유실/재생 특성이 비교된다.
- [ ] 권장 transport, publisher/subscriber profile, payload, fan-out, deduplication, 재시작·장애 정책이 설계된다.
- [ ] 자동체결 이벤트와 SSE transport event의 재사용 금지 경계가 명시된다.
- [ ] 다음 구현 단계의 파일·테스트·운영 검증 범위가 제안된다.
- [ ] 사전 작업 결과 문서와 본 spec 문서에 민감한 운영값·비밀값이 포함되지 않는다.
