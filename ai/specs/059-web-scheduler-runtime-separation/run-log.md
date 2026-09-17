# Run Log: 059-web-scheduler-runtime-separation

## AI 로그

| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 2026-09-17 23:37 | main | `git status --short --branch` | 기존 브랜치와 미추적 결과 문서 보존 확인 |
| 23:37 | main | `sed`/`rg`로 AGENTS, context-router, agent-mistakes, 관련 ADR·Spec 확인 | 프로젝트 작업 규칙과 배포 아키텍처 정본 확인 |
| 23:37 | main | `rg`/`sed`로 application 설정, Hikari, JPA/DataSource, Scheduling, Docker, Terraform, Health Check 확인 | 현재 Runtime·DB·Scheduling·인프라 구조 분석 |
| 23:37 | main | `git ls-tree -r --name-only origin/dev ai/specs` | `059` Spec 번호 충돌 없음 확인 |
| 23:37 | main | `apply_patch`로 `spec.md`, `plan.md`, `tasks.md`, `run-log.md` 작성 | 분석 결과와 Pool 선택지 기록; Java/설정/인프라 미변경 |
| 23:39 | main | `rg`로 `@Scheduled`, `@EventListener(ApplicationReadyEvent)`, DB/Redis/외부 API 의존성 확인 | Web heartbeat와 Scheduler 시세·자동체결·뉴스/공시·배치 작업의 scheduling 부하 분류 |
| 23:41 | main | 사용자 제공 운영 정보 확인 | RDS `db.t4g.small`, ElastiCache `cache.t4g.small` 사용 사실을 반영; 실제 RDS `max_connections`는 별도 조회 전까지 미확정으로 유지 |
| 23:45 | main | `LimitOrderFillExecutorRouter`, `LimitOrderFillExecutorProperties`, `@Scheduled` 목록 확인 | Scheduler에는 기본 8개 자동체결 partition과 prod 기준 17개 scheduled method가 있음을 확인; Web 기존 Pool 20개 유지 후보로 보정 |
| 23:54 | main | 사용자 선택 확인 | B안(Web 20/Scheduler 10)을 선택; Web minimum-idle 4, Scheduler minimum-idle 2, scheduling pool은 Web 2/Scheduler 18로 적용 예정 |
| 2026-09-18 00:08 | implementer/main | `apply_patch`로 profile별 Hikari/Scheduling 설정 적용 | `application-web.yml`은 Web `20/4/2`, `application-scheduler.yml`은 Scheduler `10/2/18`로 분리; Stage 3 코드와 민감정보는 변경하지 않음 |
| 00:08 | tester/main | `./gradlew test --tests 'com.finplay.api.global.config.OperationalProfilePropertiesTest'` | profile별 effective property 2개 테스트 통과; 최초 실행은 Gradle cache lock 권한 오류로 중단되어 권한 승인 후 재실행 |
| 00:08 | main | `./gradlew spotlessApply compileJava compileTestJava test --tests 'com.finplay.api.global.config.OperationalProfilePropertiesTest' --tests 'com.finplay.api.global.config.SchedulingConfigProfileTest'` | 포맷·컴파일·Stage 4 대상 테스트 통과 |
| 00:08 | main | `./gradlew test --tests 'com.finplay.api.ProdWebProfileContextIntegrationTest' --tests 'com.finplay.api.ProdSchedulerProfileContextIntegrationTest'` | Web/Scheduler Context 통합 테스트 통과; Redis shutdown 과정의 기존 비치명적 warning만 확인 |
| 00:09 | main | `git add ... && git diff --cached --check && git commit -m \"feat: 웹과 스케줄러 실행 리소스 분리\"` | Stage 4 변경만 커밋; 결과 문서와 기존 미추적 파일은 커밋에서 제외 |
| 00:12 | main | `rg`/`sed`로 `compose.deploy.yaml`, Terraform EC2/user-data/refresh-env, ALB/보안그룹, deploy workflow 확인 | 운영 Compose는 `prod` 고정이고 Scheduler에도 HTTP health check가 적용되는 구조임을 재확인; Web만 ALB target임을 확인 |
| 00:12 | main | `apply_patch`로 `compose.deploy.yaml`, `infra/terraform/ec2.tf`, `infra/terraform/files/refresh-env.sh.tftpl`, `.env.example` 수정 | Terraform이 인스턴스 역할별 `SPRING_PROFILES_ACTIVE`와 health command를 `.env`에 주입하도록 최소 변경; Web HTTP/Scheduler non-web liveness 분리 |
| 00:14 | main | `SPRING_PROFILES_ACTIVE=prod,web ... docker compose config --quiet` 및 `SPRING_PROFILES_ACTIVE=prod,scheduler ... docker compose config --quiet` | Web/Scheduler Compose interpolation과 health command 구성이 모두 통과 |
| 00:14 | main | `terraform fmt -check -diff` | Terraform formatting 통과 |
| 00:14 | main | `terraform init -backend=false && terraform validate` | provider 초기화 후 Terraform configuration valid 확인; 실제 AWS 접근·plan/apply는 수행하지 않음 |
| 00:19 | main | `./gradlew spotlessApply compileJava compileTestJava test spotbugsMain build --no-daemon --max-workers=1` | 전체 테스트·컴파일·SpotBugs·build 통과; 테스트 종료 과정의 기존 Redis shutdown warning만 확인 |
| 00:19 | main | `git status --short`, `git diff --stat`, `git diff --name-only` | Stage 5 변경은 Compose/Terraform/.env.example과 Spec 문서로 제한; Stage 3 Java/Redis/SSE 및 거래 코드 변경 없음 |
| 00:20 | main | `apply_patch`로 `docs/issue-589-web-scheduler-runtime-separation-result.md` 작성 | 구현 구조·검증 결과·남은 운영 작업을 민감정보 없이 정리; 결과 문서는 커밋 대상에서 제외 |
| 00:20 | main | `git diff --check` | 현재 Stage 5 변경의 whitespace 오류 없음 |
| 00:23 | main | `git add`로 Stage 5 대상 파일만 stage 후 `git diff --cached --check` 및 `git commit -m \"feat: 웹과 스케줄러 배포 런타임 분리\"` | Stage 5 변경 커밋 완료; 결과 문서와 기존 미추적 문서는 커밋에서 제외 |
| 01:11 | implementer/main | `./gradlew compileJava` | Scheduler readiness marker 컴파일 성공; health check 차단사항 수정 |
| 01:11 | tester/main | `./gradlew test --tests com.finplay.api.global.config.SchedulerReadinessMarkerTest` | ApplicationReadyEvent marker 생성·stale marker 정리·실패 격리 테스트 3건 통과 |
| 01:11 | main | `git diff` 및 새 readiness marker 코드 확인 | Scheduler non-web readiness marker와 Scheduler health check만 변경; 결과 문서와 기존 미추적 파일은 커밋에서 제외 |
| 01:12 | main | `./gradlew spotlessApply compileJava compileTestJava test --tests com.finplay.api.global.config.SchedulerReadinessMarkerTest --no-daemon --max-workers=1` | 포맷·컴파일·readiness marker 대상 테스트 통과 |
| 01:12 | main | `terraform fmt -check -diff` | Terraform 형식 검증 통과 |
| 01:12 | main | `terraform validate` | 캐시된 provider 플러그인 실행 환경 오류로 1차 실패 |
| 01:13 | main | 승인된 환경에서 `terraform validate` 재실행 | Terraform 구성 검증 통과 |
| 01:14 | main | `git add`로 차단사항 수정 파일만 stage 후 `git diff --cached --check` 및 `git commit -m \"fix: 스케줄러 readiness health check 보강\"` | Scheduler readiness health check 차단사항 수정 커밋 완료; 결과 문서와 기존 미추적 파일은 커밋에서 제외 |

## 현재 분석 결과

- 운영 Hikari `maximum-pool-size=20` 외 역할별 Pool 설정은 아직 없다.
- Web/Scheduler는 같은 RDS MySQL과 ElastiCache Redis를 공유한다.
- RDS `max_connections`는 저장소에서 확인할 수 없어 실제 운영값 확인이 필요하다.
- 운영 인스턴스 타입은 RDS `db.t4g.small`, ElastiCache `cache.t4g.small`로 확인했다.
- Scheduling pool은 공통 `size=18`이며 역할별 resource 설정은 아직 없다.
- 운영 Compose는 `SPRING_PROFILES_ACTIVE=prod`로 고정되어 있어 Web/Scheduler profile 주입이 남아 있다.
- 운영 Compose와 ALB health check는 HTTP actuator endpoint를 사용하지만 Scheduler는 non-web 실행이다.
- Scheduler 작업은 DB/Redis뿐 아니라 KIS·Bithumb·뉴스·LLM 외부 호출을 포함하므로 scheduling thread와 Hikari Connection의 점유 시간을 별도로 검증해야 한다.
- 자동체결 Executor는 기본 8개 partition이므로 Scheduler Hikari Pool 8은 자동체결만으로 소진될 수 있다.

## 완료 상태

Connection Pool 선택, Stage 4·5 구현·검증 및 커밋을 완료했다. 리뷰 차단사항인 Scheduler readiness health check를 추가 구현하고 검증·커밋까지 완료했다.
