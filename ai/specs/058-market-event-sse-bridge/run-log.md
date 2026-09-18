# Run Log: 058-market-event-sse-bridge

## AI 로그 (에이전트 참조용)

| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 21:58 | main | `git status --short --branch` | 기존 브랜치·미추적 사용자 문서 보존 확인 |
| 21:58 | main | `sed -n ... ai/specs/README.md`, `CryptoCandleStore.java`, `RedisLock.java` | spec 번호·Redis 저장/락 역할 확인 |
| 21:58 | main | `rg -n -i 'pub.?sub|redis.?stream|MessageListenerContainer|...' src build.gradle` | 현재 Java 코드에 시세 IPC publisher/subscriber가 없는지 확인 |
| 21:58 | main | `git ls-tree -r --name-only origin/dev ai/specs` 및 `ls -1 ai/specs` | `058` spec 번호 충돌 여부 확인; 현재 `058` 없음 |
| 21:58 | main | `sed -n ... StockPriceStreamService.java`, `SseEmitterRegistry.java`, `StockPriceSseController.java` | Scheduler local broadcast와 Web local emitter 단절 확인 |
| 21:58 | main | `sed -n ... BithumbWebSocketFeedClient.java`, `BithumbRestTickerPoller.java` | Scheduler 가격 입력과 `PriceStore` downstream 확인 |
| 21:58 | main | `rg -n ... ai/prd.md docs/api/market.md ai/specs/056...` | 현재 SSE 계약, 코인 SSE 제거 결정, 1·2단계 경계 확인 |
| 21:59 | main | `TZ=Asia/Seoul date '+%H:%M'` | 로그 기록 시각 확인 |
| 22:01 | main | `apply_patch`로 `spec.md`, `plan.md`, `tasks.md`, `run-log.md` 작성 | 분석·설계·Spec만 기록; Java/설정/테스트/의존성 미변경 |
| 22:41 | implementer | `git status --short --branch`, `git diff` | Scheduler publisher, Web subscriber/forwarder, Web heartbeat profile 경계 구현 확인 |
| 22:41 | main | `./gradlew compileJava` | production 변경 컴파일 성공; deprecated API 경고 1건 확인 |

## 민감정보 점검

문서에는 credential, token, 운영 접속정보, 실제 사용자·계좌·주문 데이터, 실제 운영 가격 샘플을 기록하지 않았다.

## 3단계 구현·검증 로그

| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 22:34 | implementer | `git rev-parse --show-toplevel`, `git status --short --branch` | 지정 worktree와 기존 미추적 문서 보존 확인 |
| 22:34 | implementer | `rg -n 'StockPriceStreamService|SseEmitterRegistry|RedisMessageListenerContainer|@Profile|@Scheduled' src/main/java src/main/resources` | Scheduler/Web profile·주식 SSE·Redis 경계 확인 |
| 22:34 | implementer | `./gradlew compileJava --console=plain` | production Java 컴파일 성공; Gradle 캐시 권한 오류 후 권한 확장 재실행 |
| 22:34 | implementer | `git status --short --untracked-files=all`, `git diff --stat` | production·run-log 변경 범위 확인 |
| 22:39 | implementer | `./gradlew compileJava --console=plain` | listener container·payload 검증·emitter 격리 보강 후 `BUILD SUCCESSFUL` |
| 22:47 | main | `./gradlew spotlessApply --no-daemon --max-workers=1` | Java·테스트 포맷 적용 성공 |
| 22:47 | main | `./gradlew compileJava compileTestJava --no-daemon --max-workers=1` | production·테스트 컴파일 성공; 기존 deprecated API 경고 1건 확인 |
| 22:47 | main | `./gradlew test --no-daemon --max-workers=1 --tests '...StockMarketTransportEventTest' --tests '...StockMarketEventPublisherTest' --tests '...StockMarketEventSubscriberTest' --tests '...StockPriceStreamServiceTest' --tests '...SseEmitterRegistryTest' --tests '...ProdWebProfileContextIntegrationTest' --tests '...ProdSchedulerProfileContextIntegrationTest'` | 29개 관련 테스트 통과; Redis shutdown 시 기존 Bithumb lock 경고가 출력되지만 테스트 실패는 없음 |
| 22:52 | main | `./gradlew test --no-daemon --max-workers=1 --tests '...StockMarketTransportEventTest' --tests '...StockMarketEventPublisherTest' --tests '...StockMarketEventSubscriberTest' --tests '...StockPriceStreamServiceTest' --tests '...SseEmitterRegistryTest' --tests '...ProdWebProfileContextIntegrationTest' --tests '...ProdSchedulerProfileContextIntegrationTest'` | transport 테스트 보강 후 29개 관련 테스트 최종 통과; 기존 Redis shutdown 경고 외 실패 없음 |
| 22:56 | main | `./gradlew build --no-daemon --max-workers=1` | 전체 빌드에서 기존 `ProdWebNewsBatchContextIntegrationTest`가 Web scheduling post-processor를 0개로 기대해 실패; 사용자 요청으로 빌드 중지 |
| 22:57 | main | `./gradlew spotlessApply --no-daemon --max-workers=1 && ./gradlew test --no-daemon --max-workers=1 --tests 'com.finplay.api.ProdWebNewsBatchContextIntegrationTest'` | Web heartbeat 활성화에 맞춘 기존 Profile 테스트 기대값 보정 후 대상 테스트 통과 |
| 23:19 | main | `config/spotbugs/exclude.xml`에 `StockPriceStreamService`의 두 `EI_EXPOSE_REP2` 항목을 클래스·필드 단위로 좁게 제외 | Spring 관리 Bean 참조를 방어적 복사하지 않는 의도된 구조를 SpotBugs에 명시; 운영 코드와 비즈니스 로직은 변경하지 않음 |
| 23:19 | main | `./gradlew spotbugsMain --no-daemon --max-workers=1` | SpotBugs 성공 |
| 23:19 | main | `./gradlew build --no-daemon --max-workers=1` | 전체 build 성공; 테스트·JaCoCo·SpotBugs·Spotless 검증 단계 통과 |

## 구현 결과

- Scheduler 주식 가격·시장 상태 transport publisher와 Web 독립 Redis subscriber/local SSE forwarder를 추가했다.
- Web profile에 scheduling을 활성화하고 `SseEmitterRegistry`를 Web 소유로 정렬했다.
- transport·SSE·Profile 경계 관련 테스트를 추가·수정하고 통과시켰다.
- 전체 테스트 단계는 `./gradlew build` 내부 `:test`로 통과했다.
- `spotbugsMain`의 `StockPriceStreamService` 참조 보관 경고는 해당 클래스·필드에 한정한 제외 규칙으로 처리했다.
- 최종 `./gradlew build --no-daemon --max-workers=1`은 성공했다.

## PR 리뷰 권장사항 보강 로그

| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 2026-09-18 11:08 | main | Web scheduling 테스트명과 Redis Pub/Sub 장애 정책 문서 보강 | Web heartbeat scheduling을 유지하는 실제 검증 내용에 맞게 테스트명을 정리하고, 발행 실패 시 실시간 SSE 이벤트 유실 가능성을 Spec에 명시했다 |
| 11:08 | main | `./gradlew spotlessJavaCheck compileTestJava test --tests 'com.finplay.api.ProdWebProfileContextIntegrationTest' --no-daemon --max-workers=1` (외부 `AWS_REGION` 환경변수 설정) | Java 포맷·테스트 컴파일은 성공했으나 로컬 Docker 환경 부재(`/var/run/docker.sock`)로 Testcontainers Context 테스트 실행은 실패 |
| 11:08 | main | `./gradlew spotlessJavaCheck compileTestJava test --tests 'com.finplay.api.domain.market.transport.StockMarketEventSubscriberTest' --no-daemon --max-workers=1` (외부 `AWS_REGION` 환경변수 설정) | transport subscriber 단위 테스트와 Java 포맷·테스트 컴파일 성공 |

## PR 차단사항 수정 로그

| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 2026-09-18 11:23 | main | STATUS subscriber 검증 완화 및 `StockPriceStreamService` transport-to-SSE 회귀 테스트 추가 | 기존 SSE 계약의 `status=null`, `reason=null` 상태 이벤트를 Web subscriber가 전달하도록 수정 |
| 11:23 | main | `./gradlew spotlessJavaCheck compileTestJava test --tests 'com.finplay.api.domain.market.transport.StockMarketEventSubscriberTest' --tests 'com.finplay.api.domain.market.service.StockPriceStreamServiceTest' --no-daemon --max-workers=1` (외부 `AWS_REGION` 환경변수 설정) | 포맷·테스트 컴파일 및 차단사항 관련 테스트 성공 |
