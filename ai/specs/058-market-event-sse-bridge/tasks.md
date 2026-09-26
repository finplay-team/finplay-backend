# Tasks: Issue #589 3단계 시세 이벤트 전달

## 분석 완료

- [x] `PriceStore`의 Redis 저장과 local `ApplicationEventPublisher` 경계를 확인한다.
- [x] `CryptoPriceUpdatedEvent`의 자동체결 listener 및 Scheduler 전용 실행 관계를 확인한다.
- [x] `StockPriceStreamService`의 snapshot·가격 감지·SSE broadcast 결합 구조를 확인한다.
- [x] `StockPriceSseController`와 `SseEmitterRegistry`의 Web local emitter 소유 관계를 확인한다.
- [x] Bithumb WebSocket/REST ticker의 가격 반영 진입점과 downstream 흐름을 확인한다.
- [x] 현재 Redis Pub/Sub/Streams publisher, subscriber, listener container가 존재하지 않음을 확인한다.
- [x] 두 Web 인스턴스 fan-out, Web 재시작, Scheduler 재시작, Redis 장애, emitter 실패의 현재 gap을 정리한다.
- [x] 기존 코인 SSE 제거 결정과 현재 주식 SSE 계약을 대조하여 1차 전달 대상 범위를 정리한다.

## 설계 확정

- [x] Redis Pub/Sub를 1차 transport로 채택한다.
- [x] PRICE/STATUS envelope와 schema version·event id·시각 필드를 확정한다.
- [x] Scheduler publisher와 Web subscriber/forwarder의 실제 클래스·패키지 경계를 확정한다.
- [x] `StockPriceStreamService`의 Scheduler 감지·publisher와 Web snapshot/SSE 경계를 최소 변경으로 분리한다.
- [x] Web 프로세스의 SSE heartbeat ownership과 profile 조건을 Web 기준으로 정렬한다.
- [x] Web subscriber가 generic Spring application event나 자동체결 event를 발행하지 않도록 한다.
- [x] snapshot recovery와 인스턴스별 독립 fan-out, publisher 실패 격리 정책을 확정한다.
- [x] Redis 채널은 `market:stock:events:v1`로 두고 Pub/Sub 보존·replay를 사용하지 않는다.

## 구현·검증

- [x] Scheduler 전용 transport publisher 구현
- [x] Web 전용 subscriber 및 local SSE forwarder 구현
- [x] Web heartbeat 실행 경계 정렬
- [x] transport 직렬화·다중 Web fan-out·loop 방지·emitter 실패 격리 테스트 작성
- [x] `prod,web`/`prod,scheduler` ApplicationContext 검증
- [x] Web 재연결 시 기존 snapshot recovery 계약 유지 및 Redis publish 실패 격리 검증
- [x] 전체 테스트 단계 실행 (`./gradlew build` 내부 `:test`)
- [x] 전체 `./gradlew build` 실행

## 이번 단계에서 수행하지 않는 항목

- [x] Redis Streams 실제 도입 금지
- [x] Kafka/RabbitMQ/SQS 도입 금지
- [x] Bithumb/KIS·PriceStore·자동체결·DB·Transaction·Lock 변경 금지
- [x] Docker/Terraform/AWS/ALB/배포 변경 금지
- [x] Scheduler → Web 외 추가 IPC와 코인 SSE 도입 금지
