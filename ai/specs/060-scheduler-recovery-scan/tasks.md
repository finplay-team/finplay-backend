# Tasks: 스케줄러 재시작·시세 이벤트 누락 보완 재검사

- [x] **재검사 Scheduler와 실행 경계 구성** — `prod & scheduler` 전용 startup/정기 진입점을 만들고, 실물 CRYPTO 종목 순회·최신 `PriceStore` snapshot·종목/phase별 실패 격리·기존 `RedisLock` 기반 중복 실행 방지를 연결한다. `prod,web`에는 실행부·scheduled task가 등록되지 않게 한다.
- [ ] **기존 Listener·Repository 조건 재사용** — 재검사 가격 snapshot을 기존 `LimitOrderTriggerListener`·`ExitPlanTriggerListener`에 전달하고, `findPendingLimitOrdersToFill`·`findPendingExitPlansToFill`의 `PENDING`·가격 방향·교육 주문 제외·정렬 조건을 유지한다. API·새 메시지 큐·새 후보 저장소는 추가하지 않는다.
- [ ] **체결 시점 조건 재확인과 기존 Fill 경계 보존** — 지정가 단건/청크 경로에 snapshot 가격을 전달해 order row lock 후 조건을 재확인하고, 불충족 주문은 `PENDING`으로 유지한다. 기존 `LimitOrderFillService`의 `order → account → holding`, `READ_COMMITTED`, 청크 원자성과 `ExitPlanFillService`의 account→holding→plan, OCO 상태 전이·예약 원장을 변경하지 않는다.
- [ ] **재시작·이벤트 누락·상태·stale·실패 격리 검증** — MySQL/Redis Testcontainers 통합 테스트로 startup scan, 정기 scan만으로 복구, 최신 가격 부재/stale/조건 불충족, `FILLED`·`CANCELLED`·terminal 상태 제외, 한 종목 실패 후 다른 작업 지속을 검증한다.
- [ ] **동시성·Profile 회귀 및 문서 정합 검증** — 두 scan과 이벤트/취소 경합에서 정확히 한 번 체결·예약 정합성을 검증하고, `prod,web`/`prod,scheduler` context를 확인한다. HTTP 계약이 없음을 확인하고 PRD §3 요구사항 상태 갱신 필요 여부를 최종 판정한다.
