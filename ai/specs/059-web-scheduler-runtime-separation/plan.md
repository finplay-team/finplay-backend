# Plan: Web / Scheduler Runtime 분리

## 1. 분석 근거

| 영역 | 현재 확인 결과 | Stage 4·5 판단 |
|---|---|---|
| DataSource | Spring Boot 자동 구성 DataSource 1개/JVM | Web/Scheduler별 DataSource Bean 분리는 보류. 먼저 Pool 크기와 runtime 설정만 분리 |
| JPA | 공통 EntityManagerFactory·Repository 그래프 | 기존 JPA/Transaction 구조 유지 |
| HikariCP | 운영 `maximum-pool-size=20`만 명시 | 사용자 선택 전 변경 금지 |
| DB 상한 | 저장소에 RDS `max_connections` 또는 parameter group 없음 | 실제 RDS 값 확인 전 안전 상한 확정 금지 |
| 운영 인스턴스 타입 | RDS `db.t4g.small`, ElastiCache `cache.t4g.small` | RDS Connection 상한과 Redis 여유를 별도로 확인 |
| Scheduling | 역할별 `@EnableScheduling`은 이미 분리, pool size는 공통 18 | Web/Scheduler별 scheduling pool/resource 설정 검토 |
| Redis/SSE | Stage 3 Publisher → Redis Pub/Sub → Web Subscriber → SSE | 변경 금지 |
| Docker | 운영 app 컨테이너 1개 스택, profile은 `prod` 고정 | 역할별 profile 주입 구조 필요 |
| Terraform | Web EC2 2대 + Scheduler EC2 1대, Web만 ALB 연결 | 기존 인프라 방향 유지 |
| Health | HTTP actuator health check가 공통 Compose에 있음 | Web HTTP check와 Scheduler non-web check 분리 필요 |

## 2. DataSource 결론

현재는 Web/Scheduler DataSource 자체를 분리할 근거가 부족하다.

- 양쪽 모두 같은 RDS의 같은 schema를 사용한다.
- Repository와 공통 Transaction Service를 역할별로 복제할 필요가 없다.
- DataSource를 분리해도 RDS의 `max_connections` 총량은 공유되므로, Pool 상한을 먼저 정하지 않고 분리하면 Connection 고갈 위험만 키울 수 있다.
- 따라서 1차 구현 후보는 동일한 DataSource 자동 구성 구조를 유지하고, 프로파일별 Hikari property만 주입하는 방식이다.
- 실제 부하 관측 후에도 역할별 격리가 필요하면 별도 DataSource/EntityManagerFactory 분리는 후속 설계로 남긴다.

## 3. Connection Pool 후보

아래 수치는 현재 2대 Web + 1대 Scheduler를 기준으로 계산한 후보이다. `Web maximum-pool-size`는 Web 인스턴스 1대 기준이며, 전체 합계는 `Web 2대 + Scheduler 1대` 기준이다.

자동체결 Executor가 기본 8개 partition이고 각 partition의 최대 동시 실행 수가 1이므로, Scheduler Hikari Pool 8은 자동체결 작업만으로 소진될 수 있다. 아래 후보는 기존 Web Pool 20개를 유지하고 Scheduler Pool만 조정하는 안이다.

### A안 — 최소 Scheduler Pool

```text
Web:       maximum-pool-size=20, minimum-idle=2
Scheduler: maximum-pool-size=8,  minimum-idle=1
```

- Web 인스턴스당 최대 20개, 2대 합계 40개
- Scheduler 최대 8개
- 전체 최대 48개
- 유휴 최소 합계 5개
- 장점: Scheduler의 자동체결 기본 partition 수를 수용하면서 전체 Connection 수를 낮춘다.
- 주의점: 자동체결이 8개 partition을 모두 사용하면 다른 Scheduler 작업은 Connection 대기를 겪을 수 있다.
- 근거: Scheduler의 17개 scheduled 작업이 모두 동시에 DB Connection을 점유하는 것은 아니지만, 자동체결은 별도 Executor에서 최대 8개가 동시에 실행될 수 있다.

### B안 — 균형 권장 후보

```text
Web:       maximum-pool-size=20, minimum-idle=4
Scheduler: maximum-pool-size=10, minimum-idle=2
```

- Web 인스턴스당 최대 20개, 2대 합계 40개
- Scheduler 최대 10개
- 전체 최대 50개
- 유휴 최소 합계 10개
- 장점: Web의 기존 Pool 용량을 유지하고, 자동체결 8개 외에 Scheduler 작업 2개 정도의 Connection 여유를 둔다.
- 주의점: 실제 RDS `max_connections`가 50보다 충분히 큰지 확인해야 한다.
- 근거: Web의 일부 요청은 원 transaction과 `REQUIRES_NEW` 후속 처리로 최대 2개 Connection을 점유할 수 있고, Scheduler에는 8개 자동체결 partition과 수집·배치가 있다.

### C안 — Scheduler 여유형

```text
Web:       maximum-pool-size=20, minimum-idle=5
Scheduler: maximum-pool-size=12, minimum-idle=3
```

- Web 인스턴스당 최대 20개, 2대 합계 40개
- Scheduler 최대 12개
- 전체 최대 52개
- 유휴 최소 합계 13개
- 장점: Web의 기존 Pool 용량을 유지하고, 자동체결 외 뉴스·배치·수집 작업이 겹칠 여유가 가장 크다.
- 주의점: 전체 Connection 수가 52개로 올라가므로 작은 RDS의 실제 `max_connections` 확인이 특히 중요하다.
- 근거: 기존 `maximum-pool-size=20`은 동시 매도 요청의 최대 2 Connection 사용을 고려해 정한 값이고, Scheduler에는 외부 API/LLM을 포함한 blocking 작업이 있다.

### 현재값 유지안

참고로 현재처럼 모든 프로세스가 `maximum-pool-size=20`을 사용하면 전체 최대치는 `20 × 3 = 60`개이다. Web 20개를 유지하는 것은 기존 Web 처리 용량을 보존하는 근거가 있지만, Scheduler까지 20개를 유지하면 자동체결 partition 수보다 큰 여유를 별도 검증 없이 확보하게 된다.

## 4. 사용자 선택 후 구현 계획

1. 사용자가 A/B/C 중 Pool 후보를 선택한다.
2. 선택한 값과 profile별 property 주입 방식을 확정한다.
3. DataSource/JPA 자동 구성은 유지하고 Hikari 설정만 역할별로 적용한다.
4. Web/Scheduler scheduling executor의 역할별 설정을 적용한다.
5. Compose와 배포 Runtime에서 Web/Scheduler profile을 분리한다.
6. Web은 ALB/HTTP health check를 유지하고 Scheduler는 non-web health 방식을 적용한다.
7. Stage 3 transport/SSE와 거래·시세 로직의 diff를 확인한다.
8. 역할별 context 테스트, scheduling 등록 테스트, runtime 설정 테스트, compile/test/spotbugs/build를 수행한다.

## 4-1. 사용자 선택 결과

사용자는 B안을 선택했다.

```text
Web:       maximum-pool-size=20, minimum-idle=4
Scheduler: maximum-pool-size=10, minimum-idle=2
```

Scheduling pool은 Web heartbeat 전용 여유를 고려해 Web `2`, Scheduler의 기존 운영 작업 수용을 위해 Scheduler `18`로 적용한다. Web 2대와 Scheduler 1대의 애플리케이션 최대 Connection은 `20 + 20 + 10 = 50`개다.

## 4-1. Scheduling 작업 부하 확인

| 작업 묶음 | 주요 실행부 | DB/Redis/외부 연동 특성 | 역할 |
|---|---|---|---|
| Web heartbeat | `SseEmitterRegistry.sendHeartbeat` | Web JVM의 local emitter 전송, DB 미사용 | Web |
| 주식 시세 감지/발행 | `StockPriceScheduler.publishScheduledUpdates` | Instrument/가격 조회, 기존 transaction, Redis Pub/Sub 발행 | Scheduler |
| Bithumb feed/ticker | `BithumbFeedLifecycle`, `BithumbRestTickerPoller`, `BithumbFeedStatusReconciler` | 외부 시세 API/WebSocket, PriceStore, Redis lock | Scheduler |
| KIS 수집/import | `KisHistoricalCandleCollector`, `StockDailyCandleCollector` | 외부 KIS API, Candle Repository, 수집 lock | Scheduler |
| 재생 세션 | `StockReplaySessionScheduler` | DB 조회·갱신, transaction, Redis lock | Scheduler |
| 자동체결 | `LimitOrderTriggerListener`, `ExitPlanTriggerListener` 및 Executor | 기존 JVM event, DB lock/transaction, Repository | Scheduler |
| 뉴스/공시·피드백 배치 | `NewsCollectionService`, `FeedbackBatchService`, `CryptoFeedbackBatchService`, `PeerStatsBatchService` | 외부 API/LLM, DB, Redis lock/cache | Scheduler |
| 랭킹 재구성 | `RankingRebuildService` | 기동 listener와 정기 작업, DB 조회, Redis ZSET/lock | Scheduler |

현재 Scheduler pool의 `size=18`은 scheduled method 개수의 단순 합이 아니라 실행 중인 blocking 작업이 겹칠 수 있다는 전제로 설정되어 있다. Pool을 줄이거나 분리할 때에는 DB Connection 수뿐 아니라 외부 API/LLM 호출 중 scheduling thread가 점유되는 구간도 함께 확인한다.

## 5. Stage 4 검증 계획

- `prod,web`에서 heartbeat만 등록되고 Scheduler scheduled method가 등록되지 않는지 확인한다.
- `prod,scheduler`에서 시세·자동체결·뉴스·배치 scheduled method가 등록되는지 확인한다.
- Web과 Scheduler의 TaskScheduler 설정이 각각의 profile 값으로 바인딩되는지 확인한다.
- DataSource가 의도하지 않게 중복 생성되지 않는지 확인한다.
- Stage 3 Publisher/Subscriber/SSE Bean과 channel이 변경되지 않았는지 diff로 확인한다.

## 6. Stage 5 검증 계획

- Web Runtime의 effective profile이 `prod,web`인지 확인한다.
- Scheduler Runtime의 effective profile이 `prod,scheduler`인지 확인한다.
- Web 컨테이너만 8080 HTTP health check 및 ALB target이 필요한지 확인한다.
- Scheduler 컨테이너에 HTTP health check를 복사하지 않고 non-web 프로세스 상태를 확인하는지 검증한다.
- 운영 Compose에서 DB/Redis 컨테이너를 새로 추가하지 않는지 확인한다.
- Terraform의 Web/Scheduler EC2 및 ALB 관계를 불필요하게 재설계하지 않는다.
