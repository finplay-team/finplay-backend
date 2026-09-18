# 배포 스택 실행 방법 (수동 배포 — 파이프라인 폴백)

`compose.deploy.yaml` + `Dockerfile`로 구성된 **백엔드 전용** 스택을 EC2에서 수동으로 띄우는 절차다. 프론트는 여기 포함되지 않는다 — S3에서 독립 배포된다 (ADR-0022).
설계 배경과 완료 조건은 `ai/specs/010-deployment/spec.md`에 있다. 예전에는 nginx가 프론트와 API를 한 오리진에서 같이 서빙했지만(이슈 #108 A안), ADR-0022로 그 결정이 뒤집혀 nginx는 제거됐고 지금은 CORS로 연다.

> **2026-08-12 — 이 문서는 더 이상 정상 배포 경로가 아니다** (ADR-0021, 이슈 #345).
> 정상 배포는 `dev` 머지로 GitHub Actions가 수행하며, 그 구조·구축 순서·실패 대응은 [`cd-runbook.md`](cd-runbook.md)에 있다.
> 아래 수동 절차는 **폐기하지 않는다** — 파이프라인이 막혔을 때(GitHub 장애, OIDC 실패 등)의 폴백이다.
>
> **2026-09-14 정정** — [ADR-0030](../ai/adr/0030-rolling-deploy-multi-instance.md)으로 웹
> EC2 2대 + 스케줄러 1대 롤링 배포로 바뀌면서, 아래 "ALB 리스너 전환" 관련 안내는 더 이상
> 해당하지 않는다 — 타깃 그룹이 하나뿐이라 전환할 리스너 가중치 자체가 없다. `compose.deploy.yaml`도
> `build:` 대신 `image: ${APP_IMAGE}`를 쓰도록 바뀌었으므로, 아래 수동 절차로 폴백할 때는 ECR에서
> 이미지를 직접 pull(`docker compose -f compose.deploy.yaml up -d`)하거나, 정말 소스에서 바로
> 빌드해야 한다면 `docker build -f Dockerfile .`로 로컬 빌드한 이미지를 `.env`의 `APP_IMAGE`에
> 넣고 실행한다. 웹 인스턴스가 2대이므로 폴백 시에도 **한 대씩** 작업해 나머지 한 대가 트래픽을
> 계속 받게 한다.
>
> 아래 절차는 단일 스택(`compose.deploy.yaml`) 기준이다. 블루-그린 스택(`compose.bluegreen.yaml`)은
> 더 이상 배포에 쓰이지 않으며 과거 기록·롤백 참고용으로만 남아 있다.

## 구성

```
브라우저 ──▶ app :8080 ─┬─▶ RDS (MySQL)        ┐ EC2 밖
                        └─▶ ElastiCache (Redis)┘ 관리형 서비스

프론트(별도 오리진) ──▶ S3 정적 웹 호스팅 (ADR-0022, finplay-frontend 레포에서 독립 배포)
```

## Web / Scheduler 역할별 실행 기준

`compose.deploy.yaml`은 실행 역할을 자동으로 선택하지 않는다. 각 EC2의 `.env`에는 역할에 맞는
Profile과 health check 명령이 함께 주입되어야 한다. 운영 배포에서는 Terraform user-data와
`refresh-env.sh`가 이 값을 역할별로 생성하며, 수동 폴백에서도 동일한 기준을 사용한다.

| 실행 역할 | Profile | 컨테이너 health check | ALB 연결 |
|---|---|---|---|
| Web EC2 2대 | `prod,web` | Actuator `/actuator/health` HTTP 확인 | 연결 |
| Scheduler EC2 1대 | `prod,scheduler` | Java 프로세스와 Scheduler readiness marker 확인 | 연결하지 않음 |

Scheduler는 non-web 애플리케이션이므로 Web과 같은 Actuator HTTP health check를 사용하지 않는다.
`SPRING_PROFILES_ACTIVE=prod`처럼 역할이 없는 값으로 실행하지 않으며, Web과 Scheduler는 반드시
각자의 Profile을 사용한다.

기존 EC2에 대한 첫 배포도 GitHub Actions가 최신 `compose.deploy.yaml`과 역할별 런타임 갱신 스크립트를
먼저 전송한 뒤 진행한다. 따라서 Terraform user-data를 다시 실행하거나 기존 인스턴스를 교체해야만
역할 Profile을 반영할 수 있는 구조가 아니다.

앱 컨테이너가 호스트 포트 8080을 직접 연다(ADR-0022 — nginx 제거). 프론트가 다른 오리진(S3)에서 오므로 백엔드가 CORS로 열어야 한다 — `.env`의 `CORS_ALLOWED_ORIGINS`가 그 허용 목록이다. 값이 비어 있으면 `CorsConfig`가 기동 단계에서 fail-fast로 거부한다.

**DB·캐시는 이 스택 안에 없다 (ADR-0020, 이슈 #326).** 예전에는 `compose.deploy.yaml`이 mysql·redis 컨테이너를 함께 띄웠지만, EC2를 종료하면 그 볼륨의 원장이 함께 사라지는 문제 때문에 RDS·ElastiCache로 분리했다. 그래서 이 스택이 띄우는 컨테이너는 **app 하나뿐이고**, 접속 정보는 전부 `.env`에서 온다. 로컬 개발(`compose.yaml` + `bootRun`)은 바뀌지 않았다 — 여전히 컨테이너 mysql·redis를 쓴다.

## 절차

1. **`.env`를 만든다.** `.env.example`을 복사해 값을 채운다. 배포에 필요한 값은 다음과 같다.
   - 시크릿 — `JWT_SECRET`, `OAUTH_STATE_SECRET`, `EMAIL_VERIFICATION_SECRET`, `PASSWORD_RESET_SECRET`
   - DB(RDS) — `DB_URL`(RDS 엔드포인트), `DB_USERNAME`(root 불가), `DB_PASSWORD`.
     **compose가 덮어쓰지 않으므로 여기 값이 그대로 쓰인다.**
   - 캐시(ElastiCache) — `REDIS_HOST`(기본 엔드포인트), `REDIS_PORT`,
     그리고 **`SPRING_DATA_REDIS_SSL_ENABLED=true`**. 아래 "알려진 함정" 참고.
   - **CORS — `CORS_ALLOWED_ORIGINS`.** 프론트가 서빙되는 S3(또는 CloudFront) 오리진을 스킴+호스트까지 적는다(끝 슬래시 금지). 비어 있거나 형식이 틀리면 기동이 실패한다(ADR-0022).
   - OAuth — `KAKAO_*`, `NAVER_*`. `*_REDIRECT_URI`는 배포 주소 기준으로 적고 각 콘솔에도 같은 값을 등록한다.
   - 메일 — `RESEND_API_KEY`, `EMAIL_FROM`
   - KIS — `KIS_APP_KEY`, `KIS_APP_SECRET` (없어도 기동은 성공한다)
   - `OAUTH_STATE_COOKIE_SECURE`는 **건드리지 않는다(기본 `true`).** prod 프로필에서 `false`를 주면
     `OAuthStateCookieFactory`가 기동 단계에서 예외를 던져 앱이 뜨지 않는다 (2026-07-31 실측).
     아래 "알려진 제약" 참고.

2. **기동한다.** `compose.deploy.yaml`은 `build:`가 아니라 `image: ${APP_IMAGE}`를 쓰므로 `--build`
   플래그는 더 이상 아무 효과가 없다 — `.env`의 `APP_IMAGE`가 가리키는 ECR 이미지를 그대로 받아 온다.
   ```bash
   docker compose -f compose.deploy.yaml up -d
   ```
   Flyway 마이그레이션이 끝나야 healthcheck가 통과하므로, 이 명령은 앱 기동이 끝날 때까지 돌아오지 않는다 — 로컬 실측 40초 안팎이다.

3. **확인한다.**
   ```bash
   docker compose -f compose.deploy.yaml ps
   ```
   Web EC2에서는 다음 HTTP 확인을 추가한다.
   ```bash
   curl http://<호스트>:8080/actuator/health          # {"status":"UP"}
   curl -N http://<호스트>:8080/api/stocks/stream     # SSE — heartbeat 확인
   ```
   Scheduler EC2에서는 HTTP Actuator/SSE를 확인하지 않고 `docker compose ps`의 컨테이너 health 상태와
   Scheduler readiness 결과를 확인한다. Scheduler는 ALB 대상이 아니다.
   프론트(S3)에서 브라우저 개발자도구로 CORS 오류 없이 API 호출이 성공하는지, `/trade` 같은 하위 경로 새로고침이 404가 아닌지도 확인한다(SPA 폴백은 S3 오류 문서 설정이 담당하며 이 스택과 무관하다).

## 갱신

- 백엔드가 바뀐 경우: `.env`의 `APP_IMAGE`를 새 ECR 태그로 바꾼 뒤 `docker compose -f compose.deploy.yaml up -d app`
- 프론트가 바뀐 경우: 이 스택과 무관하다. `finplay-frontend` 레포에서 빌드해 S3에 `aws s3 sync`로 올린다.
- `CORS_ALLOWED_ORIGINS` 등 `.env`만 바뀐 경우(이미지는 그대로): `docker compose -f compose.deploy.yaml up -d --force-recreate app` — 재빌드 불필요.

## 알려진 함정 — ElastiCache 접속 실패는 네트워크 문제처럼 보인다

`SPRING_DATA_REDIS_SSL_ENABLED`를 빠뜨리면 **DNS 해석·보안 그룹·TCP 연결이 전부 정상인데** 앱만 기동에 실패한다.

```
io.lettuce.core.RedisConnectionException: Unable to connect to <엔드포인트>/<unresolved>:6379
Caused by: io.lettuce.core.RedisCommandTimeoutException: Connection initialization timed out after 2 second(s)
```

`<unresolved>`라는 표기 때문에 DNS 문제로 읽히지만 아니다. ElastiCache의 "전송 중 암호화"가 켜져 있으면 서버가 TLS 핸드셰이크를 요구하는데 Lettuce 기본 설정은 평문으로 붙어서, **TCP는 연결되고 Redis 핸드셰이크만 타임아웃된다** (2026-08-11 실측).

컨테이너 안에서 이렇게 확인할 수 있다.

```bash
docker exec finplay-app bash -c 'timeout 5 cat < /dev/null > /dev/tcp/<엔드포인트>/6379 && echo TCP_OK'
```

`TCP_OK`가 나오는데 앱이 위 예외로 죽는다면 네트워크가 아니라 `.env`의 `SPRING_DATA_REDIS_SSL_ENABLED=true`가 빠진 것이다. 전송 중 암호화는 클러스터 생성 후 끌 수 없으므로 클라이언트를 맞추는 것 외의 방법이 없다.

## S3 업로드 이미지 저장소 설정 (ADR-0020, 이슈 #330)

`community.storage.S3FileStorageService`(`@Profile("prod")`)가 커뮤니티 게시물 첨부 이미지를 저장하는 곳이다. 로컬 개발(`!prod`)은 여전히 `LocalFileStorageService`로 디스크에 저장한다 — 아래는 배포(prod 프로필)에서만 필요한 AWS 콘솔 설정이다.

**버킷 — S3**

- [ ] 버킷(예: `finplay-community-images`)을 생성한다. **퍼블릭 액세스 차단(Block Public Access) 4개 옵션을 모두 켠 채로 유지한다** — 이미지는 앱의 다운로드 엔드포인트(`GET /api/community/posts/images/{imageId}/file`)로만 노출되고 버킷을 직접 공개하지 않는다.
- [ ] 버킷 이름을 `.env`의 `COMMUNITY_S3_BUCKET`에 넣는다 — 값이 비어 있거나 플레이스홀더가 그대로 남아 있으면(예: 환경변수 미주입) `prod` 기동이 다른 필수 환경변수(`DB_URL` 등)와 같은 방식으로 즉시 실패한다(fail-fast, 이슈 #335).

**IAM 역할·EC2 인스턴스 프로파일**

- [ ] 대상 버킷 하나만 한정한 최소 권한 정책을 만든다 — `s3:GetObject`·`s3:PutObject`·`s3:DeleteObject`, 리소스는 `arn:aws:s3:::<버킷명>/*`.
- [ ] 이 정책을 붙인 IAM 역할을 만들고, EC2 인스턴스에 인스턴스 프로파일로 연결한다(콘솔: EC2 → 인스턴스 선택 → 작업 → 보안 → IAM 역할 수정).
- [ ] **정적 액세스 키를 `.env`·코드 어디에도 두지 않는다.** `S3Client`는 SDK 기본 자격 증명 체인에 맡기므로, 위 인스턴스 프로파일이 배포 환경의 유일한 자격 증명이 된다 — RDS·ElastiCache처럼 로테이션할 시크릿을 새로 만들지 않는 선택이다.
- [ ] `.env`의 `AWS_REGION`에 Terraform 출력값을 넣는다. 애플리케이션의 S3 리전 설정은 이 환경변수에서 주입하며, 자격 증명은 SDK 기본 자격 증명 체인(EC2 인스턴스 프로파일)에 맡긴다.
- [ ] `compose.deploy.yaml`의 `app` 서비스에 이미지 볼륨을 새로 붙이지 않는다 — ADR-0020 §결정 3이 "볼륨을 붙이면 그 파일이 다시 인스턴스에 묶여 이 ADR의 목적을 되돌린다"고 명시했다. 이 배포 스택은 볼륨 없이 그대로 유지한다.

## 기존 로컬 업로드 파일 이관 (다중 인스턴스 전환 전 1회)

`CommunityPostImage.storedFilename`은 `UUID+확장자`뿐인 순수 키라 저장소 위치 정보를 담지 않는다 — 이관은 "같은 키로 바이트를 로컬 디스크에서 S3로 복사"하는 것만으로 끝나고, DB 마이그레이션·엔티티 변경은 필요 없다.

1. **이관 대상이 실제로 있는지 먼저 확인한다.** 이 프로젝트는 아직 실사용자 트래픽 이전 단계이고(ADR-0020 §후속), 현재 배포된 EC2가 다중 인스턴스 전환 전 유일한 스택이다. 그 인스턴스에 SSH로 접속해 `${COMMUNITY_IMAGE_STORAGE_DIR}`(compose.deploy.yaml 기준 컨테이너 내부 `./data/community-images`)에 파일이 몇 개나 있는지 확인한다.
   ```bash
   docker exec <app 컨테이너> find /app/data/community-images -type f | wc -l
   ```
2. **판단 기준.**
   - 파일이 없거나 소수(운영 검증용 테스트 데이터 수준)면 — 이관 스크립트를 만들지 않는다. 전환(새 인스턴스를 S3 프로필로 띄우는 시점) 후 기존 데이터는 폐기하고, 필요하면 사용자에게 재업로드를 안내한다. 1회성 이관 자동화를 만드는 비용이 이 팀 규모에서는 더 크다.
   - 파일이 실사용 데이터 수준으로 있으면 — 아래 1회성 스크립트를 다중 인스턴스 전환 직전에 실행한다.
3. **이관 스크립트 (실사용 데이터가 있을 때만).**
   ```bash
   # EC2 인스턴스 위에서, 다중 인스턴스 전환 직전 1회 실행
   aws s3 sync /path/to/mounted/data/community-images s3://${COMMUNITY_S3_BUCKET}/ \
     --exclude "*" --include "*.jpg" --include "*.png" --include "*.webp"
   ```
   `aws s3 sync`가 로컬 파일명(=`stored_filename`)을 그대로 S3 키로 쓰므로 DB의 `stored_filename` 값과 키가 자동으로 일치한다. 이 동기화가 끝난 뒤에만 새 인스턴스(S3 프로필)로 트래픽을 넘긴다 — 이관 완료 확인은 롤링 배포 첫 실행 시 사람이 체크하는 항목으로 추가하고 자동화하지 않는다.
4. 이관 여부와 관계없이 `compose.deploy.yaml`에는 이미지 볼륨을 붙이지 않는다(위 체크리스트와 동일 판단).

## 알려진 제약 — HTTP 배포에서는 OAuth 로그인이 안 된다

이슈 #108은 "급하면 `OAUTH_STATE_COOKIE_SECURE=false`로 내려서 `http://<EC2-IP>/`로도 데모가 돌아간다"를 A안의 근거 중 하나로 들었지만, 실제 코드는 그걸 막는다.

`OAuthStateCookieFactory`(`src/main/java/com/finplay/api/auth/oauth/OAuthStateCookieFactory.java:39`)는 `prod`·`oauth-real` 프로필에서 `secure=false`면 생성자에서 `IllegalStateException`을 던진다. 즉 prod로 띄우면서 이 값을 내리는 선택지는 없다.

그래서 HTTPS를 붙이기 전 상태는 이렇게 정리된다.

- 앱은 기본값 `true`로 정상 기동한다. 프론트·API·SSE·이메일 로그인은 HTTP에서 전부 동작한다.
- 카카오·네이버 OAuth 로그인만 동작하지 않는다 — 브라우저가 `http://`에서 `Secure` 쿠키를 저장하지 않아 state 검증이 실패한다.
- 해결은 HTTPS를 붙이는 것이다. 가드를 완화하는 선택은 별도 이슈에서 판단한다.

## 아직 하지 않은 것

- **HTTPS** — `infra/terraform/route53.tf`·`alb.tf`가 ALB에 ACM 인증서를 붙이는 것으로 코드화했다([ADR-0030](../ai/adr/0030-rolling-deploy-multi-instance.md)). `terraform apply`가 아직이라 이 문서의 수동 폴백 절차(`http://<EC2-IP>:8080/`)는 여전히 HTTP 기준이다.
- **블루-그린 무중단 배포** — 폐기됐다([ADR-0030](../ai/adr/0030-rolling-deploy-multi-instance.md)). 웹 인스턴스를 2대로 늘려 둘 다 항상 트래픽을 받게 하고, 배포는 한 대씩 순차로 교체하는 롤링 방식으로 무중단을 얻는 쪽으로 방향이 바뀌었다 — `compose.bluegreen.yaml`은 과거 기록으로만 남아 있다.
- **S3 콘솔 설정·기존 파일 이관 실행** — 버킷 생성·IAM 역할 연결은 `infra/terraform/s3.tf`·`iam.tf`로 코드화돼 `terraform apply`가 대신한다. 위 "기존 로컬 업로드 파일 이관" 절의 `aws s3 sync` 이관만 여전히 사람이 1회 수행해야 한다.
- **배포 자동화(CD)** — 완료됐다. `.github/workflows/deploy.yml`이 웹 2대 순차 교체 + 스케줄러 1대 SSM 배포로 구축돼 있다 — 블루-그린 가중치 전환이 아니다. 구축 순서와 실패 대응은 [`cd-runbook.md`](cd-runbook.md).
- **프론트 연동·SSE의 브라우저 검증은 실제로 완료됐다 (2026-08-12, ADR-0022).** 프론트를 S3에 올리고 EC2(당시 블루-그린 스택)의 `CORS_ALLOWED_ORIGINS`를 그 주소로 맞춘 뒤, 일반 API 호출·`/api/stocks/stream`의 `Authorization` 헤더 포함 preflight가 CORS에 막히지 않고 서버까지 도달하는 것을 브라우저에서 실측했다 — 이슈 #108이 우려한 지점의 반증이다. 이 단일 스택(`compose.deploy.yaml`)은 같은 앱 이미지를 쓰지만 별도로 실측하지는 않았다.
