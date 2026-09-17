# Tasks: Web / Scheduler Runtime 분리

## 분석

- [x] 현재 브랜치·기존 미추적 결과 문서·Stage 3 Spec을 확인한다.
- [x] DataSource/JPA/Transaction 자동 구성 여부를 확인한다.
- [x] 운영 Hikari 설정과 테스트 전용 Hikari override를 구분한다.
- [x] Web 2대·Scheduler 1대 기준 현재 Pool 총량을 계산한다.
- [x] RDS `max_connections` 및 DB parameter group이 저장소에 존재하는지 확인한다.
- [x] Web/Scheduler scheduling infrastructure와 scheduled 작업을 확인한다.
- [x] Docker/Compose의 profile, command, port, health check를 확인한다.
- [x] Terraform EC2/RDS/ElastiCache/ALB 구조를 확인한다.
- [x] 현재 구조에서 확인된 민감정보를 문서에 기록하지 않는다.

## 사용자 선택 대기

- [x] Connection Pool 후보 A/B/C 중 사용자 선택을 받는다.
- [x] 선택한 Pool 값과 적용 위치를 Spec에 기록한다.

선택 결과: B안

- Web: `maximum-pool-size=20`, `minimum-idle=4`
- Scheduler: `maximum-pool-size=10`, `minimum-idle=2`
- Web scheduling pool: `2`
- Scheduler scheduling pool: `18`

## Stage 4 구현

- [x] 선택한 Hikari 설정을 profile별 Runtime에 적용한다.
- [x] Web/Scheduler scheduling resource 설정을 분리한다.
- [x] Stage 3 Redis Pub/Sub/SSE 경계를 변경하지 않는다.
- [x] Stage 4 profile/context/scheduling 검증을 수행한다.

## Stage 5 구현

- [ ] Web Runtime에 `prod,web`을 적용한다.
- [ ] Scheduler Runtime에 `prod,scheduler`를 적용한다.
- [ ] Web HTTP/ALB health check와 Scheduler non-web health check를 분리한다.
- [ ] Terraform은 필요한 최소 범위만 수정한다.
- [ ] Docker/Compose는 필요한 최소 범위만 수정한다.

## 최종 검증

- [ ] `spotlessApply`
- [ ] `compileJava`
- [ ] `compileTestJava`
- [ ] 영향 범위 테스트
- [ ] `spotbugsMain`
- [ ] `./gradlew build --no-daemon --max-workers=1`
- [ ] `git status`, `git diff --stat`, `git diff`로 범위 확인
- [ ] 결과 문서 작성
- [ ] 커밋하지 않고 사용자 최종 검토 대기
