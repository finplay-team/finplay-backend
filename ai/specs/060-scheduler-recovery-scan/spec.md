# Spec: 스케줄러 재시작·시세 이벤트 누락 보완 재검사

## 개요

Issue #589의 Web/Scheduler 런타임 분리 후, Scheduler가 재시작되거나 코인 시세 이벤트가 일시적으로 누락되어도 DB에 남아 있는 `PENDING` 지정가 주문과 OCO 예약을 다시 판정하고 체결한다. 재검사 경로는 새로운 체결 엔진을 만들지 않고 기존 가격 이벤트 Listener, Repository 조건, `LimitOrderFillService`·`ExitPlanFillService`, 상태 전이와 잠금 경계를 재사용한다.

이 기능은 HTTP API나 주문 계약을 추가하지 않는 운영 신뢰성 보강이다. 재검사의 정본은 공유 MySQL의 `PENDING` 상태와 Redis에 저장된 최신 코인 가격이며, 메모리 큐에 남아 있던 작업이나 과거 이벤트를 복원하려고 하지 않는다.

## 관련 요구사항

- 기존 지정가 체결 계약: `LMT-002` (`ai/prd.md` §4, `ai/specs/015-limit-order`)
- 기존 일반 OCO 트리거·정확히 한 번 체결 계약: `RISK-OCO-009` (`ai/specs/021-general-risk-management-oco`)
- 후속 운영 범위: Issue #591. 현재 PRD에는 Issue #591 전용 요구사항 ID가 없다.

## 사용자 시나리오

- 운영자는 Scheduler를 재시작한 뒤에도 DB에 남아 있는 조건 충족 `PENDING` 지정가 주문이 최신 가격으로 재검사되어 체결되는 것을 확인할 수 있다.
- 운영자는 Scheduler를 재시작한 뒤에도 조건 충족 `PENDING` OCO가 최신 가격으로 재검사되어 시장가 청산되고 반대 조건이 취소되는 것을 확인할 수 있다.
- 운영자는 시세 이벤트가 일시적으로 누락되어도 다음 정기 재검사에서 같은 결과가 복구되는 것을 확인할 수 있다.
- 사용자는 조건이 충족되지 않은 주문, 이미 `FILLED`·`CANCELLED`·`EXPIRED`인 주문 또는 OCO가 재검사 때문에 잘못 체결되지 않는 것을 기대한다.
- 운영자는 여러 Scheduler 인스턴스가 동시에 재검사해도 중복 체결·예약 이중 소비·반대 조건의 잘못된 상태 전이가 발생하지 않는 것을 기대한다.

## 요구사항

- [ ] **RECOVERY-001** `prod,scheduler` 역할에서만 재검사 실행부를 생성하고 실행한다. `prod,web`에서는 재검사 `@Scheduled`·`ApplicationReadyEvent` 실행부가 생성·등록되지 않는다.
- [ ] **RECOVERY-002** Scheduler의 `ApplicationReadyEvent` 이후 한 번 재검사하고, 시세 이벤트 누락을 보완할 정기 재검사를 추가한다. 시작 시 최신 가격이 없거나 유효하지 않으면 해당 종목을 건너뛰고 다음 정기 실행에서 다시 시도한다.
- [ ] **RECOVERY-003** 코인 실물 종목별 최신 가격 snapshot을 기준으로 지정가와 OCO를 모두 재검사한다. 한 종목의 실패가 다른 종목 또는 같은 실행의 다른 체결 작업을 중단시키지 않는다.
- [ ] **RECOVERY-004** 지정가 재검사는 기존 `LimitOrderTriggerListener`와 `OrderRepository.findPendingLimitOrdersToFill`의 매수 `currentPrice <= limitPrice`, 매도 `currentPrice >= limitPrice`, `PENDING`, 코인·일반 주문 조건을 재사용한다. 교육용 price session/attempt 주문은 기존 Repository 제외 조건을 그대로 따른다.
- [ ] **RECOVERY-005** OCO 재검사는 기존 `ExitPlanTriggerListener`와 `ExitPlanRepository.findPendingExitPlansToFill`의 `PENDING`, 익절 `currentPrice >= takeProfitPrice`, 손절 `currentPrice <= stopLossPrice` 조건을 재사용한다. 교육용 attempt 귀속 OCO는 기존 제외 조건을 그대로 따른다.
- [ ] **RECOVERY-006** 재검사 체결은 기존 `LimitOrderFillService`·`ExitPlanFillService`를 호출한다. 계좌·보유·체결 원장·예약 해제/확정·FIFO·OCO 반대 조건 취소·기존 상태 전이를 별도 구현하지 않는다.
- [ ] **RECOVERY-007** 최신 가격이 후보 조회 후 비동기 체결 실행까지 변할 수 있으므로, 지정가 체결은 해당 가격 snapshot을 FillService까지 전달하고 order row lock 아래에서 조건을 다시 확인한다. 재확인 시 조건이 불충족이면 주문은 `PENDING`으로 남긴다.
- [ ] **RECOVERY-008** 기존 Redis 분산락과 DB row lock을 함께 유지한다. 재검사 전체는 기존 `RedisLock` 패턴의 Scheduler 전용 분산락으로 중복 실행을 억제하고, 실제 체결은 기존 지정가 `order → account → holding` 잠금과 OCO `account → holding → plan` 잠금·상태 재확인을 따른다. 재검사 실행부가 먼저 원장을 잠그거나 FillService의 잠금 순서를 바꾸지 않는다.
- [ ] **RECOVERY-009** `FILLED`, `CANCELLED`, `EXPIRED` 상태는 체결 대상이 아니다. Repository와 FillService 모두 `PENDING` allow-list를 기준으로 삼고, 동시 취소·체결·재검사에서는 먼저 row lock을 확보한 트랜잭션의 상태 전이만 성공한다.
- [ ] **RECOVERY-010** 개별 종목·개별 후보·개별 청크의 실패는 로그로 격리하고 전체 Scheduler 스레드, 다른 종목의 재검사, 다른 종류의 체결을 중단시키지 않는다. 기존 지정가 청크 원자성은 유지하며 청크 내부 실패를 임의로 부분 커밋하지 않는다.

## 비즈니스 규칙

### 실행 시점

- 시작 보완: `prod & scheduler`에서 `ApplicationReadyEvent`가 발생한 뒤 전체 코인 실물 종목을 한 번 순회한다. Feed 연결이 아직 준비되지 않았거나 가격이 유효하지 않은 종목은 체결하지 않고 건너뛴다.
- 이벤트 누락 보완: 같은 재검사 흐름을 `@Scheduled` 정기 실행으로 반복한다. 정기 실행은 메모리 큐를 복원하지 않고 매번 DB의 현재 `PENDING` 후보를 다시 조회한다. 주기는 fixed-delay 5초로 하며, 기존 `PriceStore` stale 기준 10초보다 짧게 둔다.
- 한 실행은 최신 가격을 확보할 수 있는 종목만 처리한다. 한 종목의 예외는 해당 종목의 이번 시도만 실패시킨다.

### 최신 가격 기준

- 종목별 기준 가격은 `PriceStore`의 해당 심볼 최신 가격이며, `receivedAt`이 가장 최근인 값이다. 가격의 `observedAt`이나 이전 체결가를 체결 가격으로 사용하지 않는다.
- 기존 `PriceStore.isPriceAvailable` 의미를 따른다: Redis Feed 상태가 `CONNECTED`이고 `receivedAt`이 기존 `STALE_THRESHOLD` 10초 이내인 경우만 유효하다. 최신 값이 없거나 연결이 끊겼거나 stale이면 해당 종목은 재검사하지 않는다.
- 가격 snapshot의 `receivedAt`은 조건 판정용 시장 데이터 시각이다. 실제 `Trade`·`Order`·`ExitPlan`의 원장 시각은 기존 FillService가 사용하는 `Clock`의 체결 시각을 따른다.
- 하나의 종목에서 지정가와 OCO는 같은 재검사 가격 snapshot을 공유한다. 정기 실행 도중 Redis 값이 바뀌었다고 해서 한 실행 안에서 임의로 과거·현재 가격을 섞지 않는다.

### 상태·중복 체결

- 재검사 후보 조회는 `PENDING`만 허용한다. 현재 코드의 `OrderStatus`와 `ExitPlanStatus`에는 `EXPIRED` enum이 없으므로, 새 만료 상태를 추가하지 않고 `PENDING` allow-list로 terminal 상태 전체를 배제한다. 별도 `EXPIRED` 영속 상태가 요구되면 구현 전 추가 결정이 필요하다.
- 후보 조회는 잠금 없는 조건 조회일 수 있으나 최종 권한은 FillService의 row lock 후 상태 확인이다. 재검사와 가격 이벤트, 취소 요청이 동시에 도착해도 이미 종결된 대상은 no-op 또는 기존 오류 규칙으로 종료한다.
- Redis 재검사 락 획득 실패 또는 Redis 장애 시 해당 실행을 건너뛴다. DB 원장 체결을 Redis 장애 시 임의로 우회하지 않는다. 재검사 coordinator 락 키는 `order:recovery-scan:lock`, TTL은 30초로 고정한다.
- 재검사 경로는 `PENDING` 행을 삭제하거나 상태를 임의로 복구하지 않는다. 체결·취소·만료의 원장과 예약 수량/현금은 기존 서비스가 단 한 번만 변경한다.

## 범위 제외

- 새로운 HTTP endpoint, 요청·응답·오류 계약, Controller, API route
- 지정가·OCO의 가격 정책, 부분 체결, 슬리피지, 주문 수정·취소 계약 변경
- 새로운 체결 엔진, 새로운 원장·상태 전이·예약 원장, 새로운 메시지 큐
- Terraform, EC2, ALB, Compose, deploy workflow, OAuth, Web/frontend, AWS 서비스
- SQS, Kafka, Redis Streams 및 프로세스 간 `CryptoPriceUpdatedEvent` 전달
- 주식 지정가·주식 OCO와 KIS 재생 가격을 이용한 자동 체결
- 교육용 price session/attempt의 별도 정산 경로 변경
- 현재 PRD에 없는 `EXPIRED` 상태를 임의로 enum·migration으로 추가하는 것
- 민감정보, Redis/DB 접속정보, 운영 credential 기록

## 완료 조건

- [ ] `prod,scheduler`에서만 시작·정기 재검사 실행부가 등록되고 `prod,web`에서는 등록되지 않는다.
- [ ] 재시작 시 영속 `PENDING` 지정가와 OCO가 유효한 최신 Redis 가격으로 재검사되어 조건 충족 대상만 기존 FillService 경로로 체결된다.
- [ ] 이벤트를 전달하지 않은 상태에서도 정기 재검사로 조건 충족 대상이 체결된다.
- [ ] 최신 가격이 stale/부재/연결 끊김이면 체결하지 않고, 조건 불충족이면 `PENDING`을 유지한다.
- [ ] `FILLED`·`CANCELLED`·`EXPIRED` 대상이 재검사로 체결되지 않는다.
- [ ] 재검사와 이벤트·취소가 동시에 실행되어도 지정가·OCO가 정확히 한 번만 원장을 변경하고 예약이 이중 소비·반환되지 않는다.
- [ ] 한 종목·한 후보·한 청크의 실패가 다른 Scheduler 작업을 중단시키지 않는다.
- [ ] 재시작·이벤트 누락·동시성·실패 격리 테스트가 ADR-0003의 적절한 테스트 레벨로 통과한다.
