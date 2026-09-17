# Issue #589 — Web / Scheduler Runtime 분리

## 상태

현재 구조 분석 및 Connection Pool 선택 완료. Stage 4 구현 진행 중.

사용자가 Pool 설정을 선택하기 전에는 HikariCP, DataSource, Docker, Terraform, Runtime 설정을 변경하지 않는다.

## 목적

Issue #589의 Stage 4·5를 통해 Web Server와 Scheduler Server의 실행 리소스와 배포 런타임을 분리한다.

- Stage 4: HikariCP 및 Scheduling 리소스 분리
- Stage 5: Docker, Terraform, Runtime, Health Check 분리

Stage 3의 Redis Pub/Sub → Web SSE 전달 구조와 기존 주문·체결·시세 수집 로직은 유지한다.

## 현재 구조 분석

### 애플리케이션 프로세스

- Web 역할은 `prod,web` 프로필을 사용하도록 코드가 분리되어 있다.
- Scheduler 역할은 `prod,scheduler` 프로필을 사용하도록 코드가 분리되어 있다.
- Scheduler 프로필은 `application-scheduler.yml`에서 `web-application-type: none`으로 설정되어 HTTP 서버를 띄우지 않는다.
- `prod,web`은 REST/OAuth/SSE와 Web heartbeat를 생성한다.
- `prod,scheduler`는 시세 수집·감시, 자동체결, 뉴스/공시, 배치와 Scheduler scheduling infrastructure를 생성한다.
- Scheduler에는 지정가 자동체결 Executor가 기본 8개 partition으로 생성되고, 각 partition은 한 번에 하나의 체결 작업을 실행한다. 이 Executor는 `@Scheduled` 개수와 별도로 DB Connection을 동시에 사용할 수 있는 경로다.

### DataSource / JPA

- 별도 DataSource 설정 클래스는 확인되지 않았다.
- Spring Boot의 DataSource/JPA 자동 구성을 사용한다.
- EntityManagerFactory와 TransactionManager도 별도 역할별 Bean으로 구성되어 있지 않다.
- Web과 Scheduler는 각각 자기 JVM 안에 DataSource와 Hikari Pool을 하나씩 만들고, 동일한 운영 RDS MySQL을 사용한다.
- Repository, Entity, 공통 Transaction Service 그래프는 양쪽 프로세스에서 공유한다.
- Web용 DB와 Scheduler용 DB를 별도로 두는 구조는 아니다.

### HikariCP

- 공통 설정은 `src/main/resources/application.yml`에 있다.
- 현재 명시된 값은 `spring.datasource.hikari.maximum-pool-size: 20`이다.
- `minimum-idle`, `connection-timeout`, `idle-timeout`, `max-lifetime`의 운영 설정은 명시되어 있지 않다.
- 따라서 운영에서는 Hikari/Spring Boot 기본값이 적용된다.
- `build.gradle`의 테스트 설정에서만 `connection-timeout=10000`, `minimum-idle=0`, `idle-timeout=10000`을 별도로 주입한다. 이는 운영 설정이 아니다.
- 기존 20이라는 값은 단일 인스턴스에서 매도 요청의 원 트랜잭션과 `REQUIRES_NEW` 후속 처리로 최대 2개 Connection을 사용할 수 있다는 근거로 설정되어 있다.

### Scheduling

- `SchedulingConfig`는 `prod,scheduler`와 비운영 프로필에서 `@EnableScheduling`을 활성화한다.
- `WebSchedulingConfig`는 `prod,web`에서 Web heartbeat를 위해 `@EnableScheduling`을 활성화한다.
- 별도의 `TaskScheduler` 또는 `ThreadPoolTaskScheduler` Bean은 없다.
- `spring.task.scheduling.pool.size: 18`이 공통 설정으로 존재한다.
- Scheduler 프로세스에서는 현재 운영 기준 17개 내외의 scheduled 작업을 수용하기 위해 18개가 사용된다.
- Web 프로세스에서는 heartbeat 중심의 소수 작업만 실행하지만 동일한 18개 설정을 사용한다.
- 프로세스가 다르므로 Web의 scheduling thread와 Scheduler의 scheduling thread가 JVM 사이에서 직접 공유되지는 않는다. 다만 각 프로세스 내부 pool 크기는 동일하게 과대 설정되어 있다.

### Docker / Runtime

- 로컬 `compose.yaml`은 MySQL과 Redis 컨테이너를 실행한다.
- 운영 `compose.deploy.yaml`은 app 컨테이너만 실행하고 DB/Redis 컨테이너는 실행하지 않는다.
- 운영 DB/Redis 접속 정보는 환경변수로 주입한다.
- 현재 운영 Compose의 `SPRING_PROFILES_ACTIVE`는 `prod`로 고정되어 있다.
- 코드가 기대하는 `prod,web` 및 `prod,scheduler`가 Web/Scheduler 인스턴스별로 실제 주입되지 않는 상태다.

### Terraform / AWS

- Terraform에 Web EC2 2대와 Scheduler EC2 1대가 정의되어 있다.
- Web EC2 2대만 ALB target group에 등록된다.
- Scheduler EC2는 ALB에 등록되지 않는다.
- RDS MySQL은 private subnet의 단일 인스턴스로 정의되어 있다.
- ElastiCache Redis는 private subnet의 복제 그룹으로 정의되어 있다.
- 현재 운영 인스턴스 타입은 RDS `db.t4g.small`, ElastiCache `cache.t4g.small`이다.
- Web과 Scheduler는 동일한 RDS와 동일한 ElastiCache를 공유한다.

### Health Check

- Web ALB와 운영 Compose는 `/actuator/health` HTTP endpoint를 사용한다.
- Scheduler는 `web-application-type: none`이므로 HTTP 서버가 없다.
- 현재 공통 Compose health check를 Scheduler에도 적용하면 Scheduler 컨테이너에서 `localhost:8080/actuator/health`가 응답하지 않는 문제가 발생할 수 있다.
- Web은 HTTP/ALB health check를 유지하고 Scheduler는 non-web 프로세스에 맞는 별도 health 판단이 필요하다.

## Pool 선택 원칙

- 현재 저장소에는 실제 RDS `max_connections` 값 또는 이를 고정하는 DB parameter group이 없다.
- `db.t4g.small`이라는 인스턴스 타입은 확인했지만, 실제 `max_connections`는 RDS 엔진·parameter group·실제 적용 상태를 조회하기 전에는 확정하지 않는다.
- `cache.t4g.small`은 Redis 메모리·처리량 판단에는 영향을 주지만 Hikari DB Connection 상한을 직접 결정하지는 않는다.
- Terraform에는 RDS 인스턴스 클래스만 정의되어 있고 `max_connections`를 직접 설정하지 않는다.
- 따라서 실제 상한은 배포된 RDS에서 `SHOW VARIABLES LIKE 'max_connections'` 또는 AWS/RDS 설정을 확인해야 확정할 수 있다.
- 아래 선택지는 현재 코드의 요청당 Connection 특성, 2대 Web + 1대 Scheduler 구성, Scheduler의 배치 동시성을 근거로 만든 후보이며 최종 선택은 사용자 확인 후 진행한다.

## 완료 조건

- [x] 사용자가 Connection Pool 후보를 선택한다.
- [x] 선택한 Pool 설정을 Web/Scheduler Runtime에 반영한다.
- [x] Web/Scheduler Scheduling 리소스가 역할에 맞게 분리된다.
- [ ] Web은 `prod,web`, Scheduler는 `prod,scheduler`로 기동된다.
- [ ] Web만 ALB HTTP health check 대상이 된다.
- [ ] Scheduler는 non-web 실행 방식에 맞는 health 확인을 갖는다.
- [ ] Stage 3 Redis Pub/Sub/SSE 구조가 변경되지 않는다.
- [ ] 주문·체결·시세 수집·Repository·Entity·Transaction·Lock이 변경되지 않는다.
- [ ] 관련 테스트, 정적 분석, build가 통과한다.

## 제외 범위

- Redis Pub/Sub channel 또는 transport payload 변경
- SSE 구조 변경
- 주문·체결 비즈니스 로직 변경
- DB schema 및 Repository 변경
- Bithumb/KIS 시세 수집 방식 변경
- Redis Streams, Kafka, RabbitMQ, SQS 도입
- 비밀값·접속정보의 문서 기록

## 선택된 Connection Pool 설정

사용자 선택에 따라 B안으로 진행한다.

- Web 인스턴스당 `maximum-pool-size=20`, `minimum-idle=4`
- Scheduler 인스턴스당 `maximum-pool-size=10`, `minimum-idle=2`
- Web scheduling pool size `2`
- Scheduler scheduling pool size `18`

RDS의 실제 `max_connections` 값은 저장소에 없으므로 별도 운영 조회가 필요하다. 위 값은 현재 Web 2대와 Scheduler 1대 구성에서 애플리케이션 최대 50개 Connection을 기준으로 한다.
