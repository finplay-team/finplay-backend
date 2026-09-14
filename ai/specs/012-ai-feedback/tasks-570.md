# Tasks: 012 AI 피드백 — 이슈 #570 (뉴스·피드백 배치 다중 인스턴스 중복 방지)

> 이 문서는 이슈 #570의 범위를 담는다. 원래 `spec 012`의 최초 분할 밖에서 추가된 운영 안정성 후속 작업이므로 `./plan.md`와 기존 `./tasks.md`, 완료된 이슈별 tasks 문서를 소급해서 고치지 않는다.
>
> 기능의 기존 의미·배치 흐름은 `./spec.md`가 정본이고, Redis 락의 SET NX PX·토큰 검증 Lua 해제·장시간 실행 시 TTL 만료 위험은 ADR-0014·0015가 정본이다. 항목 하나는 implementer 1회 투입과 하나의 응집된 커밋을 원칙으로 한다. 테스트 수준은 ADR-0003을 따른다.

## 판정

### 1. 새 spec/ADR 필요 여부

- **새 feature spec은 필요하지 않다.** #570은 새 API·새 사용자 흐름·새 데이터 모델이 아니라 `spec 012`에 이미 정의된 네 예약 배치의 다중 인스턴스 실행 안전성 보강이다. 기존 follow-up 문서(`tasks-244`, `tasks-275`, `tasks-282`, `tasks-285`, `tasks-386`)와 같은 방식으로 이 문서에 흡수한다.
- **새 ADR도 필요하지 않다.** 기존 `global.lock.RedisLock`을 재사용하고, feedback 도메인이 키·TTL·범위를 소유하는 기존 ADR-0014·0015의 결정과 충돌하지 않는다.
- 다음을 선택하면 범위가 바뀌므로 구현을 멈추고 ADR 초안을 먼저 제안한다: 락 자동 갱신/리스 연장, 대기·재시도 큐, Redis Sentinel/Cluster/Redlock, 스케줄러 자체 리더 선출.

### 2. 현재 작업트리의 선행 변경

현재 작업트리에는 아래 구현 초안이 이미 있으므로 되돌리거나 중복 생성하지 않는다.

- `FeedbackBatchProperties.lockTtlSeconds`와 `feedback.batch.lock-ttl-seconds: 3600`
- `FeedbackBatchLock`의 `feedback:batch:lock:{batch}:{scope}` 키 조립 및 기존 `RedisLock` 위임
- `FeedbackBatchLockTest`, `FeedbackBatchLockIntegrationTest`
- 네 배치 서비스의 부분적인 락 통합과 `run-log.md`의 작업 1 기록

현재 부분 통합은 예약 진입점별 고정 `scheduled` 스코프를 사용하도록 정합화했다. 따라서 작업 1~5는 빈 파일에서 새로 설계를 발명하는 항목이 아니라, 이 선행 변경의 계약·키 스코프·락 범위를 요구사항과 맞추고 누락된 서비스 테스트를 채우는 항목이다. planner는 이 파일 외에 `src/`를 수정하지 않는다.

## API·데이터 범위

| 구분 | 결정 |
|---|---|
| API 설계 | 변경 없음. Controller·URL·요청·응답·오류 코드가 늘거나 바뀌지 않는다. 라우트 변경 수 0건이다. |
| 입력 명세 | 외부 입력 없음. 락 토큰과 고정 스코프는 내부 실행 제어값이다. |
| 데이터 모델 | 마이그레이션 없음. 기존 DB `UNIQUE` 제약과 중복 저장 방어를 최종 방어선으로 유지한다. |
| #568 | Terraform·배포 인프라 작업으로 Java 코드 선행 의존성이 아니다. 실제 다중 인스턴스 배포 검증만 #568 적용 후 수행한다. |

`ai/api-routes.md`와 `docs/api/`의 도메인 계약 파일은 Controller/API 계약 변경이 없으므로 수정 대상이 아니다. `ai/prd.md` §3도 기능 제공 범위가 그대로인 신뢰성 보강이므로 행을 변경하지 않는다. 다만 마지막 정합성 확인에서 #570 때문에 미완료 요구사항 ID가 새로 완료되는지 확인하고, 그런 경우에만 PR 번호를 근거로 갱신한다.

## 락 계약과 키 범위

### 공통 불변식

- 기존 `RedisLock.tryLock(key, ttl)`을 사용한다. `Optional.empty()`는 락 경합과 Redis 장애를 모두 포함하며, 호출부는 기다리거나 재시도하지 않고 **해당 회차 전체를 즉시 반환**한다.
- 락은 각 예약 메서드의 배치 작업을 시작하기 전에 얻고, 획득한 토큰이 있을 때만 `finally`에서 같은 키·토큰으로 해제한다. 외부 뉴스/OpenAI 호출, 후속 DB 계산·저장을 락 획득 전에 시작하지 않는다.
- `RedisLock`의 토큰 비교 Lua 해제를 유지한다. TTL이 이미 만료되어 다른 인스턴스가 새 토큰을 얻은 뒤 이전 실행이 `finally`에 도달해도 이전 토큰이 새 락을 지우지 않아야 한다.
- 키 스코프는 인스턴스마다 달라질 수 있는 `Instant.now()`, UUID, 스레드 ID를 사용하지 않는다. 추천 스코프는 모든 예약 진입점에서 고정된 `scheduled`이며, 그러면 이전 틱이 아직 실행 중인 다음 틱도 같은 배치 키에서 fail-closed로 건너뛴다. 날짜를 진단 정보로 넣어야 한다면 모든 인스턴스가 동일한 `Clock` 기반 KST 날짜를 계산해야 한다.
- `NewsCollectionService.collectForInstrument`에는 `FeedbackBatchLock`을 주입하거나 예약 뉴스 락을 적용하지 않는다. 온디맨드 경로는 예약 뉴스·공시 배치 락의 범위 밖으로 남긴다.

### 권장 키와 작업 범위

현재 래퍼의 접두사와 enum을 기준으로 다음처럼 **예약 메서드별 고정 키**를 권장한다. `scope=scheduled`는 실제 키에 포함된다.

| 도메인 | 예약 진입점 | 권장 실제 키 | 락이 감싸는 범위 |
|---|---|---|---|
| 뉴스 | `collectNews()` | `feedback:batch:lock:news-collection:scheduled` | 종목 조회부터 뉴스 외부 호출·저장·전체 시장 루프 종료까지 |
| 공시 | `collectDisclosures()` | `feedback:batch:lock:disclosure-collection:scheduled` | 기준일 계산·주식 종목 조회부터 공시 외부 호출·저장·전체 루프 종료까지 |
| 개장 전 | `runPreMarketBatch()` | `feedback:batch:lock:pre-market:scheduled` | 세션 확인부터 브리핑·요약·탐지·카드 확정·통계 종료까지 |
| 주식 집단통계 | `runPeerStatsBatch()` | `feedback:batch:lock:peer-stats:scheduled` | READY 확인부터 대상 카드 집계·스냅샷·저장 전체까지 |
| 코인 집단통계 | `runCryptoPeerStatsBatch()` | `feedback:batch:lock:crypto-peer-stats:scheduled` | 대상일 계산부터 코인 카드 집계·저장 전체까지 |
| 코인 요약·브리핑 | `refreshCryptoFeedback()` | `feedback:batch:lock:crypto-feedback:scheduled` | 코인별 요약 외부 호출·저장 및 전체 브리핑 호출·저장까지 |

주식·코인 집단통계를 하나의 `peer-stats` 키로 합치면 서로 다른 시장의 정상 실행까지 막으므로 분리한다. 뉴스와 공시도 같은 시각에 실행될 수 있으나 외부 공급자와 저장 범위가 달라 각각의 키를 사용한다. 두 회차의 대상일을 키에 넣는 구현을 선택할 경우에도 동일 틱의 모든 인스턴스가 같은 값을 계산하는지 단위·통합 테스트로 고정해야 하며, 기본 추천은 고정 스코프다.

### TTL 결정과 만료 위험

- 기존 사용자 변경의 공통 TTL `3600초`는 기존 코인 감시용 `45초`를 복사하지 않았고, spec에 적힌 개장 전 배치의 최악 조건인 **LLM 호출 약 81회 × 호출 타임아웃 20초 = 약 1620초**보다 길다. 재생성·네트워크·DB 오버헤드가 있으므로 3600초도 “무조건 안전”한 값은 아니다.
- `feedback.batch.lock-ttl-seconds` 하나를 모든 배치에 공유하면 설정 변경은 최소화되지만, 뉴스·공시·집단통계의 프로세스 장애 후 stale lock 대기 시간이 길어진다. 반대로 배치별 TTL은 장애 후 회복성이 좋아지나 설정·드리프트 테스트가 늘어난다.
- TTL이 작업 시간보다 짧으면 첫 실행 중 키가 만료되고 두 번째 인스턴스가 같은 키를 획득해 외부 API/LLM을 다시 호출할 수 있다. 첫 실행의 토큰 해제는 새 토큰을 지키지만, 중복 호출 자체는 되돌리지 못한다. 이 위험을 테스트·운영 문서에 명시한다.
- **#570 최소 변경 권장안:** 현재 `3600초` 공통 설정을 유지하고, pre-market의 실측 최장 시간과 LLM 재시도/검증 횟수가 3600초 안에 들어오는지 배포 전 확인한다. 이 값은 “공통 TTL을 유지한다”는 추천이지 45초 재사용이나 절대 보장이 아니다.
- **대안:** 배치별 최장 실행시간에 안전 여유를 곱한 TTL을 각각 설정하거나, `RedisLock.renew`를 주기적으로 호출하는 lease-renewal을 도입한다. 후자는 소유권·스케줄러·장애 경계를 새로 결정하므로 #570에 임의로 확정하지 않고 별도 ADR 대상으로 남긴다.

## 작업 항목

- [x] **1. 배치 락 설정·래퍼 계약과 스코프 정합화**

  현재 작업트리의 `FeedbackBatchProperties`, `FeedbackBatchLock`, application 설정을 기준으로 TTL 양수 검증, 키 접두사, batch enum, 토큰 전달·해제 계약을 확정한다. 예약 호출부는 고정 `scheduled` 스코프를 사용하고 실행 시각·UUID를 키에 넣지 않는다. `FeedbackBatchLockTest`와 실제 Redis SET NX 경쟁 테스트에서 키·TTL·획득 실패·토큰 해제·TTL 만료 시 이전 토큰 보호를 검증한다. 기존 `RedisLock`의 동작 자체는 다시 구현하지 않는다.

  검증 — 단위 + `RedisLock`/`FeedbackBatchLock` Testcontainers 통합.

- [x] **2. 뉴스·공시 예약 수집에 fail-closed 락 통합**

  `NewsCollectionService.collectNews()`와 `collectDisclosures()`의 예약 진입점 각각에 작업 1의 키를 적용한다. 락 실패/Redis 장애 시 종목 조회, 뉴스·공시 외부 호출, 저장을 시작하지 않고 반환한다. 정상·예외·빈 결과에서도 `finally` 해제가 실행되어야 한다. `collectForInstrument()`에는 락을 적용하지 않으며, 기존 `UNIQUE(instrument_id, url)` 중복 방어를 유지한다.

  검증 — `NewsCollectionServiceTest`에서 두 예약 경로의 획득 실패·외부 호출 0회·예외 후 해제를 단위 검증하고, 기존 스케줄/수집 통합 테스트를 회귀 확인한다. 온디맨드 경로에서 `FeedbackBatchLock`이 호출되지 않는 단정도 둔다.

- [x] **3. 개장 전 배치에 장시간 fail-closed 락 통합**

  `FeedbackBatchService.runPreMarketBatch()`의 기존 READY 게이트를 통과한 뒤 `pre-market` 키를 획득하고, 종목 조회부터 모든 LLM·탐지·카드 확정 단계와 기존 `LlmCallStats` 종료까지 락 범위에 둔다. 락을 얻지 못하면 종목 조회·외부 호출·배치 계산을 시작하지 않고 해당 회차를 건너뛴다. 기존 단계별 실패 격리·재생 세션 트랜잭션 경계는 바꾸지 않는다. 3600초가 spec의 최악 조건과 실제 설정된 재시도 상한을 감당하는지 검증 근거를 남긴다.

  획득 후 종목 조회 자체가 실패해도 토큰이 남지 않도록 종목 조회를 포함한 전체 작업을 `try/finally` 범위에 둔다. 검증 — `FeedbackBatchServiceTest`에서 락 실패 시 모든 하위 서비스·LLM 호출이 0회이고, 정상·종목 조회 예외·단계 예외·READY 아님 경로에서 토큰 해제가 정확히 일어나는지 단위 검증한다. 기존 개장 전 Testcontainers 테스트는 `@Transactional` 경계를 바꾸지 않는 회귀 테스트로 실행한다.

- [x] **4. 주식·코인 집단통계 배치에 별도 키 통합**

  `PeerStatsBatchService.runPeerStatsBatch()`와 `runCryptoPeerStatsBatch()`에 각각 `peer-stats`·`crypto-peer-stats` 키를 적용한다. 주식은 기존 READY 게이트와 대상 `serviceDate`, 코인은 기존 전날 KST 대상일 계산을 유지하되, 그 뒤 락 실패 시 카드 조회·집계·스냅샷 조회·저장을 시작하지 않는다. 두 시장의 서로 다른 키가 정상적으로 동시에 획득될 수 있어야 하며 기존 DB UNIQUE 제약은 제거하지 않는다.

  검증 — 기존 단위 테스트가 없으면 전용 서비스 단위 테스트를 추가해 두 진입점의 실패·성공·예외 해제를 고정한다. 기존 주식·코인 집단통계 통합 테스트에서 반복 실행 시 출력 중복이 없는지 회귀 확인한다.

- [x] **5. 코인 요약·브리핑 배치에 단일 실행 락 통합**

  `CryptoFeedbackBatchService.refreshCryptoFeedback()`에 `crypto-feedback` 키를 적용해 코인별 요약과 마지막 브리핑을 한 예약 회차로 감싼다. 락 실패/Redis 장애 시 코인 조회 및 LLM 호출을 시작하지 않는다. 기존 종목별 실패 격리와 요약·브리핑 저장 계약은 유지하고, `CryptoWatchLock`의 종목 단위 45초 TTL이나 온디맨드 감시 경로를 재사용하지 않는다.

  검증 — `CryptoFeedbackBatchServiceTest`에서 락 실패 시 summary/briefing 호출 0회, 정상·예외 시 토큰 해제를 단위 검증하고, 기존 코인 요약·브리핑 통합 테스트의 반복 실행·출력 테이블 범위를 회귀 확인한다.

- [ ] **6. 실제 Redis 기반 네 서비스 동시성 통합 테스트**

  실제 Redis Testcontainers와 두 동시 실행을 사용해 같은 고정 키에서 정확히 하나만 배치 본문을 진행하는지 확인한다. 뉴스/공시 외부 collector, pre-market 및 crypto feedback LLM 호출, peer stats 저장/집계를 각 서비스의 핵심 관측값으로 삼는다. 락이 없는 대조 경로에서 두 실행이 진행될 수 있고, 서로 다른 시장 키는 불필요하게 막지 않는지도 확인한다. Redis 장애/락 선점은 해당 회차가 fail-closed로 외부 호출 없이 끝나는 시나리오로 검증한다.

  동시성 테스트는 기존 `@Transactional` 통합 테스트에 얹지 않고 별도 비트랜잭션 테스트로 둔다. `@BeforeEach`·`@AfterEach`에서 Redis 키와 테스트 DB를 정리하고, `CountDownLatch`로 시작점을 맞추며, 스레드별 예외·호출 횟수·최종 행 수를 모두 확인한다. 테스트 성공을 #568 이후의 실제 다중 인스턴스 배포 검증으로 표현하지 않는다.

- [ ] **7. 문서·완료 정합성 및 검증 게이트**

  변경된 구현이 `spec.md`의 기존 배치 흐름·실패 격리·DB UNIQUE 규칙과 일치하는지 확인한다. API가 바뀌지 않았으므로 `ai/api-routes.md`와 `docs/api/`는 변경하지 않는다. 기능 제공 범위가 그대로이므로 `ai/prd.md` §3도 기본적으로 변경하지 않되, #570으로 미완료 요구사항 ID가 새로 완료되는 경우에만 해당 행과 PR 근거를 갱신한다. 구현 커밋별 `ai/specs/012-ai-feedback/run-log.md` 기록과 `#568 적용 후 실제 다중 인스턴스 검증`을 별도 후속 근거로 남긴다.

  검증 — 변경된 대상 단위 테스트, Redis Testcontainers 동시성 통합 테스트, 프로젝트 규칙에 따른 제한 Gradle 게이트를 새로 실행한다. 실행하지 못한 실제 다중 인스턴스·#568 배포 검증은 완료 주장과 분리한다.

## 완료 조건 대응

| # | 이슈 #570 완료 조건 | 작업 |
|---|---|---|
| 1 | 뉴스·공시 예약 수집이 중복 외부 호출을 만들지 않는다 | 1·2·6 |
| 2 | 개장 전 배치가 중복 LLM 호출을 만들지 않는다 | 1·3·6 |
| 3 | 주식·코인 집단통계가 중복 실행·저장을 만들지 않는다 | 1·4·6 |
| 4 | 코인 요약·브리핑이 중복 LLM 호출을 만들지 않는다 | 1·5·6 |
| 5 | 락 경합·Redis 장애는 fail-closed로 해당 회차를 건너뛴다 | 1~6 |
| 6 | 온디맨드 `collectForInstrument`는 예약 뉴스 락 범위 밖이다 | 2·6 |
| 7 | 기존 DB UNIQUE 제약과 단일 인스턴스 동작이 유지된다 | 2·4·5·6·7 |
| 8 | #568 이후 실제 다중 인스턴스 검증 계획이 분리돼 있다 | 7 |

## 이 작업에서 하지 않는 것

- #568의 Terraform apply·AWS 리소스 생성·시크릿 이관·실환경 다중 인스턴스 배포.
- `collectForInstrument` 온디맨드 경로에 예약 배치 락을 공유시키는 변경.
- Redis Sentinel·Cluster·Redlock 전환.
- 락 자동 갱신·재진입·대기 큐 도입.
- DB UNIQUE 제약 삭제·완화 또는 신규 Flyway 마이그레이션.
- Controller·DTO·API 라우트·API 오류 계약 변경.
- 기존 `spec.md`의 완료 체크박스와 원래 `plan.md` 8개 분할 표의 소급 수정.

## 미확정 사항과 추천

1. **공통 TTL 3600초를 그대로 확정할지** — 추천은 #570에서는 현재 작업트리의 3600초를 유지하는 것이다. 단, 81회 × 20초는 재시도 없는 하한에 가까우므로 pre-market의 실제 최장 시간을 측정해 3600초 초과 가능성이 확인되면 구현을 완료 처리하지 말고 배치별 TTL 또는 lease-renewal ADR로 분리한다.
2. **고정 스코프와 날짜 스코프 중 선택** — 추천은 `scheduled` 고정 스코프다. 날짜·시각을 넣을 경우 동일 인스턴스/동일 틱에서 값이 달라지는 구현을 금지하고 KST `Clock` 기반 결정론을 테스트해야 한다.
3. **장애 후 stale lock 허용 시간** — 공통 3600초는 단순하지만 짧은 배치에는 길다. 운영자가 뉴스/공시 회복성을 더 중시하면 배치별 TTL로 나누되, 이는 설정·드리프트 테스트 증가를 감수하는 별도 선택이다.
