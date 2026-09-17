# Plan: Issue #589 3단계 Scheduler → Web 시세 이벤트 전달

## 관련 문서

- Spec: `./spec.md`
- 기존 profile 분리: `../055-web-scheduler-role-separation/`, `../056-market-autofill-scheduler-separation/`
- 기존 뉴스·배치 분리: `../057-news-batch-scheduler-separation/`
- 주식 SSE 계약: `docs/api/market.md`
- 현재 제품 결정: `ai/prd.md`의 주식 SSE 및 코인 SSE 제거 기록
- 과거 Redis Pub/Sub 구현 선례: `../028-crypto-card-sse-push/` (현재 코드는 제거되어 있으며 구현 재사용을 의미하지 않음)

## 현재 Bean·프로세스 경계

| Bean/기능 | 현재 생성·실행 위치 | 실제 역할 | 3단계 판단 |
|---|---|---|---|
| `BithumbWebSocketFeedClient` | `prod,scheduler` | 코인 tick 수신, 가격·캔들 저장, 로컬 가격 이벤트 발행 | Scheduler feed 유지, transport publisher 후보는 별도 경계 |
| `BithumbRestTickerPoller` | 운영 Scheduler profile | REST 관측 가격 반영, 가격 변경 시 로컬 이벤트 발행 | Scheduler 전용 유지 |
| `PriceStore` | Web/Scheduler 공통 | Redis 가격 상태 읽기·쓰기, 로컬 `CryptoPriceUpdatedEvent` 발행 | 공통 유지; IPC publisher/subscriber 책임을 직접 섞지 않음 |
| `CryptoPriceUpdatedEvent` | 발행 JVM 내부 | 자동체결 trigger 입력 | IPC payload로 재사용하지 않음 |
| `StockPriceStreamService` | Web/Scheduler 공통 Bean, scheduled method는 현재 Scheduler에서 등록 | Web snapshot/emitter와 주식 가격 변경 감지를 한 곳에서 담당 | 단순 `@Profile` 이동 금지; 감지·전달·SSE 역할 경계 설계 필요 |
| `SseEmitterRegistry` | Web/Scheduler 공통 Bean, 메모리는 JVM별 분리 | local emitter lifecycle·heartbeat | 실제 emitter 소유와 heartbeat는 Web 책임으로 정렬 필요 |
| `StockPriceSseController` | `prod,web` | Web SSE 연결·snapshot·activate | Web 전용 유지 |

## 현재 흐름과 단절 지점

### 코인 가격·자동체결

```text
Bithumb Feed / Ticker
  → PriceStore
  → Redis 가격 상태
  → local CryptoPriceUpdatedEvent
  → Scheduler LimitOrderTriggerListener / ExitPlanTriggerListener
  → 기존 체결 Service
```

Redis 상태는 Web에서도 읽지만 Spring application event는 JVM 경계를 넘지 않는다. 현재 Web에 코인 SSE consumer가 없으므로 이 경로에 IPC를 붙이는 것은 범위를 넓히며, 자동체결 listener가 Web에서 실행되지 않도록 별도 경계를 유지해야 한다.

### 주식 가격·SSE

```text
Scheduler
  → StockPriceStreamService의 매분 감지
  → MarketPriceEvent / MarketStatusEvent 생성
  → Scheduler local SseEmitterRegistry

Web
  → StockPriceSseController
  → Web local SseEmitterRegistry
  → 연결 시 MarketSnapshotEvent
```

두 registry는 공유되지 않는다. 따라서 Scheduler의 price/status broadcast는 Web client에 도달하지 않는다. 또한 heartbeat scheduled method가 Web emitter를 보유한 JVM에서 실행되는지 profile 구조상 보장되지 않는다.

## 권장 목표 흐름

```text
Scheduler
  → 가격 변경 감지
  → 시세 transport publisher
  → Redis fan-out channel
       ├─ Web-1 subscriber → Web-1 local SSE registry → clients
       └─ Web-2 subscriber → Web-2 local SSE registry → clients

Web reconnect
  → StockPriceSseController
  → current snapshot
```

권장 1차 대상은 현재 실제 SSE 계약이 있는 `STOCK`의 `MarketPriceEvent`와 `MarketStatusEvent`다. 코인 가격은 공통 `PriceStore` REST 조회로 최신 값을 확인할 수 있고 전용 SSE 계약이 제거된 상태이므로, 코인 transport를 추가하려면 별도 제품 범위와 소비자 결정을 먼저 해야 한다.

## Transport 후보 비교

| 후보 | 장점 | 단점·위험 | 이번 목적 적합성 |
|---|---|---|---|
| Redis Pub/Sub | 현재 공통 Redis를 활용한 낮은 지연의 fan-out, 각 Web 인스턴스에 동일 전달 | 구독 중단 중 메시지 유실, replay·ack 없음, serializer·재연결 관찰 필요 | **권장**. latest-state SSE와 snapshot 복구 계약에 맞음 |
| Redis Streams | 보존·ack·재생·consumer group 제공 | trim·backlog·consumer 복구·fan-out semantics 운영 복잡도 증가 | 과도함. 모든 Web 인스턴스 fan-out 설계를 별도로 해야 함 |
| Kafka | 내구성·replay·partition 확장 | 별도 운영 인프라·의존성·직렬화·partition 설계 필요 | 현재 단순 시세 SSE 목적에는 과도함 |
| RabbitMQ | routing·ack·재전달 지원 | 별도 broker 운영과 exchange/queue 관리 필요 | 현재 Redis 공유 구조에 비해 범위가 큼 |
| SQS | 관리형·내구성 | 브로커 fan-out과 실시간 SSE 지연·순서 요구에 부적합, AWS 의존 증가 | 부적합 |

Pub/Sub는 **모든 Web 인스턴스가 같은 메시지를 받아야 하는 broadcast**로 사용한다. Web subscriber를 consumer group으로 묶지 않는다.

## 권장 구현 경계

### Scheduler

- `prod,scheduler`에서만 transport publisher를 생성한다.
- 주식 가격 변경·시장 상태 변경을 감지한 지점에서 기존 SSE payload와 호환되는 transport envelope를 발행한다.
- publisher 장애가 기존 가격 저장·자동체결을 막지 않도록 발행 실패를 격리한다.
- 코인 자동체결의 `CryptoPriceUpdatedEvent`는 기존 local application event로만 유지한다. 이를 transport publisher의 공통 입력으로 삼지 않는다.

### Web

- `prod,web`에서만 Redis subscriber와 SSE forwarding 경계를 생성한다.
- subscriber는 transport payload를 검증한 뒤 현재 Web JVM의 `SseEmitterRegistry`에 직접 전달한다.
- 수신 transport event를 generic `ApplicationEventPublisher`로 `CryptoPriceUpdatedEvent`로 재발행하지 않는다.
- Web 각 인스턴스는 독립적으로 subscriber를 보유하고 자기 registry의 emitter만 전송한다.
- heartbeat는 Web emitter가 있는 Web 프로세스에서 실행되도록 별도 책임을 확정해야 한다. `SseEmitterRegistry` 전체를 Scheduler 전용으로 바꾸는 방식은 snapshot·emitter 의존성을 깨뜨릴 수 있다.

### 공통

- transport DTO/envelope의 필드와 직렬화 규칙은 Web/Scheduler가 함께 이해하는 계약으로 둔다.
- `MarketPriceEvent`, `MarketStatusEvent`, `MarketSnapshotEvent`의 외부 SSE 계약은 기존 의미와 호환되도록 한다.
- `PriceStore`, DB Repository, 체결 Service, Redis lock은 메시징 도입만으로 profile 전용으로 바꾸지 않는다.

## Event payload 설계

### Envelope

| 필드 | 목적 | 규칙 |
|---|---|---|
| `version` | schema 호환 | 초기 버전은 1로 고정하고 변경 시 명시적으로 증가 |
| `eventType` | `PRICE`/`STATUS` 구분 | 알 수 없는 타입은 무시하고 로그·지표만 남김 |
| `eventId` | 중복 판단·SSE id | market·symbol·source time 또는 상태 전환 식별자를 포함한 안정값 |
| `market` | 시장 구분 | 현재 1차 구현은 `STOCK`만 사용 |
| `symbol` | 가격 이벤트 종목 | 시장 상태 전체 이벤트는 null 허용 여부를 기존 계약과 맞춤 |
| `sourceTime` | 원천 가격 시각 | 발행 시각과 구분 |
| `sourceTradingDate` | 주식 거래일 | 기존 `MarketPriceEvent`와 동일 의미 |
| `price` | 가격 이벤트 값 | 기존 DTO의 수치 타입·정밀도 호환 유지 |
| `marketStatus` | 시장 상태 | 가격·상태 이벤트 모두 필요 시 포함 |
| `emittedAt` | Scheduler 발행 시각 | 관측·원천 시각과 혼동하지 않음 |

별도 transport envelope를 두더라도 Web이 SSE로 내보내는 외부 payload는 현재 `MarketPriceEvent`·`MarketStatusEvent`에 맞춘다. 인증 토큰, Redis endpoint, 계정·주문·보유 데이터, 외부 API credential과 같은 정보는 payload에 넣지 않는다.

### event id·중복

- 기존 주식 price id는 symbol과 source time의 분 단위 값으로 구성된다. transport event id에도 market을 포함하여 시장 확장 시 충돌을 피한다.
- status event도 상태 종류와 원천 전환 시각을 기반으로 안정적인 id를 정한다. 발행 시각만 id로 쓰면 재시도 시 dedup이 어렵다.
- 각 Web 인스턴스는 짧은 수명의 bounded local dedup 또는 source time 단조성 검사를 사용할 수 있다. Web 인스턴스 간 공유 dedup key는 사용하지 않는다. 공유하면 Web-1이 처리한 event를 Web-2가 건너뛰어 fan-out이 깨진다.
- publisher 재시도 정책은 중복 발행 가능성을 전제로 하며, 자동체결 경로에는 연결하지 않는다.

## 재시작·장애·연결 정책

| 상황 | 기대 동작 |
|---|---|
| Scheduler 재시작 | 기존 profile lifecycle과 feed 재연결을 따른다. transport는 새 이벤트부터 발행하며 Web은 다음 이벤트 또는 재접속 snapshot으로 복구한다 |
| Web 재시작 | 구독을 다시 열고 신규 SSE 연결에 snapshot을 먼저 보낸다. 중단 중 과거 tick replay는 제공하지 않는다 |
| Web 한 대 중단 | 다른 Web 인스턴스의 subscriber·SSE는 계속 동작한다. ALB가 재연결을 다른 Web으로 보낼 수 있다 |
| Redis Pub/Sub 일시 장애 | Scheduler 가격 저장·자동체결은 transport 발행 실패와 분리한다. Web subscriber는 Redis client 재연결 정책을 따르고 snapshot으로 최신 상태를 회복한다 |
| 역직렬화 실패 | 해당 메시지를 폐기하고 안전한 메타데이터만 로그·지표로 남긴다. listener thread와 다른 client를 중단하지 않는다 |
| 한 emitter 전송 실패 | 해당 emitter만 종료·정리하고 같은 이벤트의 다른 emitter는 계속 전송한다 |
| 메시지 순서 역전 | `sourceTime`/event id 기준으로 오래된 update를 무시하거나 snapshot으로 수렴시킨다. 주문 판단에는 사용하지 않는다 |
| Scheduler publisher 중복 실행 | 기존 leader/profile 경계를 유지하고, transport event id와 중복 방지 정책으로 SSE 중복을 제한한다. 자동체결은 local event 한 곳에서만 실행한다 |

## Profile·Bean 검증 계획

### `prod,web`

- `StockPriceSseController`, `SseEmitterRegistry`, snapshot에 필요한 공통 Bean이 생성된다.
- Web subscriber/forwarder와 Web heartbeat 책임이 생성된다.
- Bithumb feed, KIS 수집, 가격 감지 publisher, 자동체결 trigger/executor가 생성·실행되지 않는다.
- subscriber 수신이 `CryptoPriceUpdatedEvent` listener나 체결 Service를 호출하지 않는다.

### `prod,scheduler`

- Bithumb/KIS 및 기존 가격 감지 스케줄이 생성된다.
- transport publisher가 생성된다.
- 자동체결 local listener/executor가 기존 방식으로 생성된다.
- Web Controller와 Web subscriber는 생성되지 않는다. `SseEmitterRegistry`는 현재 공통 Bean이므로 Web 소유로 정렬할지 구현 전에 결정하며, 단순 profile 전환은 하지 않는다.
- Scheduler의 로컬 registry는 Web client 전달용으로 사용하지 않는다.

### 공통

- `PriceStore`, `CryptoCandleStore`, Repository, 가격 조회·snapshot에 필요한 공통 그래프는 양쪽에서 필요한 범위로 유지한다.
- `StockPriceStreamService`를 한쪽 profile로 단순 이동하지 않는다. snapshot/연결은 Web, 가격 감지/발행은 Scheduler라는 역할 분리의 최소 단위를 구현 단계에서 정한다.

## 향후 구현 시 테스트 계획

- 직렬화 계약: PRICE/STATUS payload가 Web에서 복원되고 기존 SSE DTO 의미를 유지하는지 검증한다.
- 다중 Web fan-out: 같은 Redis에 연결한 Web subscriber 2개가 각각 자기 emitter에 같은 event를 전달하는지 검증한다.
- 경계 검증: Web context에 Bithumb/KIS/자동체결 Bean이 없고 Scheduler context에 Web Controller/subscriber가 없는지 확인한다.
- loop 방지: transport 수신 후 `CryptoPriceUpdatedEvent`와 체결 listener 호출이 발생하지 않는지 검증한다.
- snapshot recovery: Web subscriber 또는 Web 프로세스가 중단된 동안 event를 놓쳐도 SSE 재연결 snapshot으로 최신 상태를 얻는지 확인한다.
- 장애 격리: Redis publish/subscribe 실패·잘못된 payload·단일 emitter 실패가 다른 경로를 중단하지 않는지 확인한다.
- 기존 회귀: 주문·체결·가격 저장·REST·SSE 기존 테스트와 `./gradlew build`를 구현 단계에서 실행한다.

## 보안·데이터 최소화

문서와 transport payload에는 비밀값, credential, 운영 endpoint/접속정보, 사용자 식별정보, 계좌·주문·보유 데이터, 실제 운영 가격 샘플을 포함하지 않는다. 시세 전달에는 SSE 계약에 필요한 시장·종목·가격·원천 시각·상태만 사용한다.
