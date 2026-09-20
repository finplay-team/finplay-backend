# ADR-0032: 적응형 Human-in-the-loop 지속적 제품 개선 하네스

- 상태: 승인됨
- 날짜: 2026-09-09
- 대응 이슈: [#557](https://github.com/finplay-team/finplay-backend/issues/557)
- 관계: [ADR-0005](0005-local-agent-orchestration.md), [ADR-0008](0008-four-agent-roster.md), [ADR-0009](0009-codex-local-orchestration.md), [ADR-0010](0010-agent-session-lifecycle.md)의 로컬 4역할 체제를 유지하면서 Discovery·Triage·Approval·Observability를 추가한다. [ADR-0013](0013-issue-triggered-agent-harness.md)과 [ADR-0016](0016-review-gate-auto-fix-round.md)의 조건부 자동 승인 결정은 이 ADR이 대체한다. 자동 머지 금지와 [ADR-0019](0019-pre-pr-failure-issue-comment.md)의 실패 이력 원칙은 유지한다.
- 채택 범위: Pilot 0은 H-07부터 순차 적용하며, 각 후속 H 작업은 별도 Issue·PR과 검증을 거친다. 이 ADR의 채택은 전체 adaptive harness가 구현 완료되었다는 뜻이 아니다.

> 현재 적용 규칙: GitHub Actions는 PR을 준비하고 리뷰 결과를 남길 수 있지만 최종 PR 승인과 merge는 사람만 수행한다. 조건부 자동 승인과 봇 승인은 사용하지 않는다.

## 1. 맥락

FinPlay에는 로컬 `planner → implementer → tester → reviewer` 루프와 GitHub Actions의 `방향 제시 → 구현 → 빌드 → 자체 리뷰 → 최대 1회 자동 수정 → 조건부 승인` 루프가 함께 있다. 현재 구조는 spec 기반 개발, production 코드 작성자 단일화, 독립 리뷰, 사람 머지 같은 안전장치를 갖췄지만 다음 제품 개선 루프의 앞뒤가 비어 있다.

- 제품·코드·운영 신호에서 개선 후보를 지속적으로 발견하는 Discovery가 없다.
- 후보의 근거 신뢰도, 사용자 영향, 작업량, 위험도, 중복 여부를 같은 기준으로 비교하지 않는다.
- 작업 위험도와 무관하게 고정된 실행 절차를 사용한다.
- Slack·Discord에서 내린 승인, 이슈만 등록, 보류, 거절 결정을 하나의 상태로 관리하는 계약이 없다.
- 에이전트의 완료 선언과 실제 파일·명령·테스트 결과를 구분해 보여주는 공통 화이트박스 형식이 없다.
- 로컬 4역할과 GitHub Actions 단일 에이전트 호출의 관계가 명확하지 않다.

이 ADR은 에이전트 수를 늘리는 대신 현재 4역할을 유지하고, 위험도에 따라 역할 투입과 품질 게이트의 강도를 조절하는 적응형 하네스를 제안한다.

### 1.1 현재 실행 이력 표본

2026-09-09에 `gh run list --workflow agent.yml --limit 200`과 `gh run view <run-id> --json jobs`로 최근 실행을 확인했다. 200개 event 중 10개만 실제 job을 실행했고 나머지는 trigger 조건에서 skip됐다. 표본이 작고 작업 난이도가 다르므로 평균 속도의 확정 근거로 쓰지 않고, 중복 비용과 검증 가설을 세우는 근거로만 사용한다.

| 표본 | 관찰 결과 | 설계에 주는 의미 |
| --- | --- | --- |
| 방향 제시 `issues` run 3건 | 약 5.3~7.0분 | 자동 Discovery·방향 제시를 사람이 직접 요청한 모든 작업에 반복하면 고정 비용이 됨 |
| 성공한 구현 job 3건 | job 약 19.2~29.0분. 구현 호출이 약 16.2~27.2분, 독립 리뷰가 약 1.3~2.6분 | 구현 호출이 주된 비용이며 새 역할을 무조건 추가하면 느려질 가능성이 큼 |
| [run 32315660367](https://github.com/finplay-team/finplay-backend/actions/runs/32315660367) | 문서성 spec 번호 정리에서 구현 호출 약 4분 뒤 runner build 약 10.6분, PR 조회 실패로 총 약 14.8분 | 문서·비동작 파일의 Fast 분기와 build 생략은 실제 절감 후보 |
| 실제 job 10건 | 자동 수정 라운드 실행 0건 | 자동 수정의 속도 효과는 이 표본으로 입증할 수 없음 |

따라서 Fast의 속도 개선 가능성은 Actions의 문서성 작업에서는 근거가 있지만, 현재 로컬 경량 경로보다 빨라진다고 주장할 근거는 없다. Standard의 개선은 중복 build 제거와 불필요한 planner·tester 호출 억제가 실제 구현된 뒤에만 검증할 수 있다.

### 1.2 독립 검토 결과와 반영

초안 작성자와 분리된 읽기 전용 검토에서 최초 설계는 `무거움`, 결론은 `수정 후 승인 가능`으로 판정됐다. 주요 근거는 사람 직접 요청에도 전체 Discovery를 반복하는 비용, 기존 `agent.yml` 위에 새 계층을 더할 가능성, 중복 build, 승인 원장과 실제 dispatch 사이의 중복 실행 위험, 측정 가능한 성공 기준 부재였다. 이에 MVP를 GitHub 단일 dispatcher와 기존 workflow 내부의 얇은 route gate로 축소하고, 직접 요청은 Triage-lite로 시작하며, 명령 소유권·호출 예산·pilot 중단 기준을 명시했다. 2차 독립 검토에서는 Critical은 없었지만 원장의 실제 저장 표현, 리뷰 변경 요청의 revision, Strict 구현 범위, heartbeat 구현 가능성을 채택 전 보완점으로 지적했고 §10~§12·§16에 반영했다. 정기 Discovery를 추가한 뒤의 재검토도 Critical은 없고 핵심 MVP는 가볍다고 판정했지만, 변화 없음과 scan 실패 구분, 외부 입력 job 격리, 재식별 방지 선행 정책, 알림 fingerprint·cooldown, Discovery 효과의 분모, H-22~H-26의 PR 크기를 보완하라고 지적했다. 이에 §7·§15·§17을 보강하고 후속 Issue를 다시 분할했다. 이 검토는 구현 효과의 증명이 아니라 설계의 독립적인 반례 탐색이며, 실제 개선 여부는 §15의 비교 cohort로 판정한다.

## 2. 근거와 정본

| 근거 | 확인한 현재 계약 | 이 ADR에서의 처리 |
| --- | --- | --- |
| [`AGENTS.md`](../../AGENTS.md) | spec 우선, 4역할, production 작성자 1명, 사람 머지 | 유지 |
| [`CLAUDE.md`](../../CLAUDE.md) | 로컬 `feature`·`review-pr`, 문서 경량 경로 | 유지하고 위험도 라우팅을 앞에 추가 |
| [`ai/context-router.md`](../context-router.md) | 작업 유형별 최소 문서 라우팅 | 유지. 채택 후 별도 이슈에서 이 ADR 링크 추가 |
| [ADR-0008](0008-four-agent-roster.md) | planner·implementer·tester·reviewer 4역할 | 역할 수 유지 |
| [ADR-0010](0010-agent-session-lifecycle.md) | implementer·tester 재개, reviewer 신규 | Standard·Strict 실행에서 유지 |
| [ADR-0013](0013-issue-triggered-agent-harness.md) | 이슈 트리거 구현과 조건부 자동 승인 | 이슈 트리거는 재사용, 자동 승인은 채택 시 폐기 |
| [ADR-0016](0016-review-gate-auto-fix-round.md) | 차단 사항 자동 수정 1회, 재검증, 최종 판정 | Standard에서 선택적으로 재사용. High 이상에는 별도 사람 승인 없이 수정 재개 금지 |
| [ADR-0019](0019-pre-pr-failure-issue-comment.md) | PR 전후 실패 이력을 남김 | 모든 경로의 공통 실패 원칙으로 확장 |
| [`ai/parallel-agents.md`](../parallel-agents.md) | 한 파일 한 작성자, 동일 worktree 내 단계 순차 | 유지 |
| [`ai/harness-roadmap.md`](../harness-roadmap.md) | 이슈 트리거와 처리 시간 지표 | 처리량뿐 아니라 품질·승인·실패 지표 추가 |
| [`.github/workflows/agent.yml`](../../.github/workflows/agent.yml) | 방향 제시, 구현, 빌드, 리뷰, 1회 수정, 조건부 승인 | 같은 workflow 안에 얇은 route gate를 두고 중복 단계는 제거 |
| [`.codex/agents/`](../../.codex/agents)·[`.claude/agents/`](../../.claude/agents) | 도구별 4역할 정의 | 직접 수정하지 않고 공통 입출력 계약으로 감싼다 |
| [`docs/conventions/team.md`](../../docs/conventions/team.md) | 한 Issue는 한 spec, 한 PR은 리뷰 가능한 크기 | 후속 Issue 분해 기준으로 유지 |

### 2.1 Ruflo 참고 조사

2026-09-09의 [`ruvnet/ruflo`](https://github.com/ruvnet/ruflo) `main` 커밋 `d55b1bfeac2a95352df0f3fce94c105cd818f13f`를 읽기 전용으로 조사했다. Ruflo 전체 패키지를 FinPlay에 도입하지 않고 다음 검증된 패턴만 참고한다.

| Ruflo 개념 | 실제 확인 | FinPlay 처리 |
| --- | --- | --- |
| [`UnifiedSwarmCoordinator`](https://github.com/ruvnet/ruflo/blob/d55b1bfeac2a95352df0f3fce94c105cd818f13f/v3/%40claude-flow/swarm/src/unified-coordinator.ts) | 작업·agent 상태, workload·health·성공률 기반 배정, domain queue와 병렬 실행 | 새 LLM 역할이 아닌 현재 오케스트레이터의 결정적 Task Coordinator 계약으로 축소 |
| [`ModelRouter`](https://github.com/ruvnet/ruflo/blob/d55b1bfeac2a95352df0f3fce94c105cd818f13f/v3/%40claude-flow/cli/src/ruvector/model-router.ts) | 복잡도 휴리스틱 + 영속 Thompson Sampling prior + 결과 feedback | 처음에는 추천만 기록하는 shadow router. 품질·비용 표본을 통과하기 전 실제 모델을 바꾸지 않음 |
| AgentDB·ReasoningBank·trajectory | namespace 기반 명시적 저장·검색과 결과 기반 confidence 갱신 | 전체 대화 공유 금지. 검증된 Evidence·패턴·실패 anti-pattern만 제한적으로 저장 |
| receipt-backed evolution | held-out·replay·canary·다차원 gate를 통과한 정책만 승격 | production 코드가 아니라 역할 호출·모델 tier·검색 정책 후보만 만들고 사람 승인으로 승격 |
| token·cost 최적화 | LLM을 쓰지 않는 결정적 Tier 1의 `$0`은 구조적으로 확인. `-32%` retrieval, `-15%` booster 등은 [자체 baseline 문서에서도 미검증](https://github.com/ruvnet/ruflo/blob/d55b1bfeac2a95352df0f3fce94c105cd818f13f/plugins/ruflo-cost-tracker/docs/benchmarks/0002-baseline.md) | 절감률을 가져오지 않고 FinPlay의 eligible dispatch당 실제 token·호출·재작업 비용으로 재측정 |

Ruflo의 [자체 intelligence audit](https://github.com/ruvnet/ruflo/blob/d55b1bfeac2a95352df0f3fce94c105cd818f13f/docs/reviews/intelligence-system-audit-2026-05-29.md)은 영속 학습 루프가 실제라고 확인했지만 과장되거나 연결되지 않은 성능 기능도 함께 보고했다. [릴리스 `v3.38.23`](https://github.com/ruvnet/ruflo/releases/tag/v3.38.23)에는 강한 tier로 승격하면서 이전의 약한 model ID를 전달한 라우팅 오류가, [릴리스 `v3.38.21`](https://github.com/ruvnet/ruflo/releases/tag/v3.38.21)에는 memory persistence 오류가 수정됐다. 따라서 외부 하네스의 기능 수나 홍보 수치를 신뢰 근거로 사용하지 않고, shadow·fail-closed·증거 기반 승격을 필수로 한다.

## 3. 목표와 비목표

### 3.1 목표

- 낮은 위험의 작은 작업은 짧게 처리하고 위험이 커질수록 검증과 사람 승인을 강화한다.
- 모든 실행에서 판단 근거, 대상 파일, 실행 명령, 검증 결과, 미검증 영역을 확인할 수 있게 한다.
- Slack·Discord·GitHub의 결정을 하나의 멱등한 승인 상태로 수렴시킨다.
- 기존 4역할을 유지하고 Security·Docs는 조건부 전문 스킬 또는 품질 게이트로 호출한다.
- AI의 자기 보고가 아니라 저장소 diff, 명령 종료 상태, 테스트 결과, 외부 상태 조회를 완료 근거로 삼는다.
- 최종 merge는 항상 사람이 수행하고, 배포는 기존 CD 정책에 따라 사람 merge 이후에만 시작한다.

### 3.2 비목표

- 이 ADR에서 Slack·Discord 봇, 스케줄러, 구현 에이전트, 자동 수정 워크플로우를 구현하지 않는다.
- `src/**` production 코드와 Java·Kotlin 주석을 변경하지 않는다.
- 자동 승인, 자동 merge, 사람 승인 없는 배포를 허용하지 않는다.
- 인증·결제·권한·데이터 삭제·파괴적 스키마 변경을 무인 구현하지 않는다.
- 모델명, 개인 인증 정보, 채널 토큰을 저장소 정책으로 고정하지 않는다.
- 발견한 개선 후보를 이 ADR 작업에서 실제 하위 GitHub Issue로 생성하지 않는다.

## 4. 현재 구조와 목표 구조 비교

| 관점 | 현재 구조 | 목표 구조 |
| --- | --- | --- |
| 시작점 | 사람의 `feature`·`review-pr` 실행 또는 `agent` 라벨·`@claude` 댓글 | 자동 후보는 Discovery, 명확한 사람 요청은 Triage-lite에서 시작 |
| 계획 | planner가 spec을 만들거나 Actions가 방향을 제시 | planner 논리에서 근거·중복·점수·위험·예상 파일까지 구조화 |
| 실행 경로 | 로컬 경량 경로와 고정 feature 루프, Actions 단일 장경로 | Fast·Standard·Strict 중 위험도에 맞는 최소 경로 |
| 승인 | `@claude`가 구현 트리거, 자체 리뷰 결과로 조건부 자동 승인 가능 | 승인·이슈만 등록·보류·거절을 명시적으로 기록. 자동 승인은 없음 |
| 구현 | implementer 또는 Actions의 광범위한 단일 호출 | 승인된 Issue·revision·파일 범위 안에서 implementer 1명만 작성 |
| 검증 | tester, reviewer, 러너 build가 겹치며 책임 경계가 부분 중복 | 검증 소유자를 명시하고 위험도별 게이트만 실행 |
| Security | 일반 리뷰 항목에 흡수되거나 명시적 소유자 없음 | 위험 신호가 있을 때만 독립 Security 스킬·게이트 호출 |
| Docs | planner·implementer·reviewer가 모두 일부 책임 | 작성자는 동기화, Docs 게이트는 누락 검사만 담당 |
| 실패 | PR 전후 단계별 코멘트가 있으나 전체 상태 모델은 없음 | 재시도 예산, 타임아웃, 실패 분류, 사람 인계 상태를 공통 계약으로 관리 |
| 투명성 | 역할별 자연어 반환과 산재한 run-log·Actions 로그 | 공통 화이트박스 레코드와 사람이 읽는 요약을 함께 생성 |
| 관측 | 이슈→PR·머지 시간 중심 | 속도, 실패, 재작업, 승인 대기, 위험도별 품질을 함께 측정 |

## 5. 기존 4역할의 책임 중복과 빈 영역

### 5.1 중복

| 겹치는 책임 | 현재 위치 | 문제 | 목표 경계 |
| --- | --- | --- | --- |
| API·PRD 문서 동기화 | planner 동기화 모드, implementer, reviewer | 작성과 최종 확인 책임이 혼재 | implementer가 같은 작업에서 동기화하고 planner 또는 Docs 게이트는 누락만 검사 |
| 컴파일·빌드 확인 | implementer `compileJava`, tester build, 러너 build | 같은 워크스페이스의 중복 Gradle 실행과 근거 중복 가능 | implementer는 최소 컴파일, Quality Gate가 유일한 최종 검증 소유자 |
| 테스트 충분성 판단 | tester가 테스트 작성·실행, reviewer가 테스트 누락 검토 | 누가 최종 테스트 범위를 확정하는지 불명확 | tester는 실행 근거 생산, reviewer는 독립적으로 적합성 판정 |
| 품질 검증 | tester의 빌드 판독, reviewer의 코드 리뷰·QA | reviewer 한 역할이 리뷰와 QA를 겸하고 tester도 실행 검증 | 모드는 유지하되 한 투입 한 모드, 경로별 호출 조건을 명시 |
| 계획과 방향 제시 | planner, Actions `propose-directions` | 로컬과 CI가 다른 형식으로 계획 | 공통 Candidate·Decision 계약을 사용하고 채널만 다르게 표시 |
| 완료 보고 | 각 역할 반환, run-log, Actions 코멘트 | 필드와 신뢰 수준이 달라 비교하기 어려움 | 공통 Evidence Record로 정규화 |

중복 자체를 모두 제거하지 않는다. 작성자와 독립 검증자의 중복은 의도된 이중 확인이다. 같은 명령을 같은 근거로 다시 실행하거나 같은 파일을 여러 작성자가 고치는 중복만 제거한다.

### 5.2 빈 영역

| 빈 영역 | 필요한 책임 | 기본 소유자 |
| --- | --- | --- |
| Discovery | 사용자 불편, 장애, 테스트 취약점, 문서 불일치, 성능 신호를 읽기 전용으로 수집 | 오케스트레이터의 Discovery 단계 |
| 중복 제거 | 열린 Issue·PR·최근 merge·기존 spec과 후보의 동일성 판정 | planner 논리 + 결정적 검색 |
| 점수화·위험 분류 | 우선순위와 Fast·Standard·Strict 경로 계산 | Triage 정책 엔진. 별도 에이전트가 아님 |
| 승인 원장 | 채널 간 중복·상충 승인 방지, revision 고정 | GitHub 단일 dispatcher의 상태 책임 |
| 실행 범위 잠금 | 승인된 파일·명령·Issue·revision 밖 변경 차단 | 오케스트레이터 |
| 화이트박스 근거 | 계획과 실제 파일·명령·검증·미검증 영역 연결 | workflow Evidence aggregation. 별도 에이전트가 아님 |
| 보안 전문 판단 | 인증·권한·시크릿·외부 입력·공급망 변화 점검 | 조건부 Security 스킬·게이트 |
| 문서 영향 판정 | API·PRD·ADR·라우터 동기화 필요 여부 | 조건부 Docs 게이트 |
| 운영 지표 | 위험도별 처리 시간, 실패, 재작업, 승인 대기 측정 | Observability 단계 |
| 도구별 정책 편차 | Codex·Claude 역할 정의가 같은 의도를 유지하는지 검사 | 조건부 Harness Consistency 게이트 |

## 6. 목표 역할과 권한

항상 실행하는 것은 단계와 계약이지 독립 에이전트 수가 아니다. 오케스트레이터, Triage 정책, Evidence aggregation은 메인 세션 또는 결정적 코드의 책임으로 두며 새 LLM 에이전트로 만들지 않는다.

| 역할·게이트 | 호출 조건 | 입력 | 출력 | 쓰기 권한 | 금지 작업 |
| --- | --- | --- | --- | --- | --- |
| 오케스트레이터 | 모든 후보와 실행 | candidate, 현재 상태, 승인 revision, 정책 | 다음 상태, 역할 packet, 최종 Evidence Record | 상태 원장, 승인된 메타데이터 | production 코드 작성, 자기 승인, merge·배포 |
| planner | 결정적 규칙으로 scope를 정할 수 없거나 spec·ADR이 없을 때. Strict 계획 검토 | Issue·근거·PRD·ADR·중복 검색 결과 | 범위, 후보 점수, 위험도, 예상 파일, spec 또는 계획 | `ai/specs/**` 또는 승인된 설계 문서 | `src/**`, 미확정 정책 확정, Issue 범위 확대 |
| implementer | 실행 승인을 받은 변경 | 고정된 Issue·revision·작업 항목·파일 범위 | diff, 실제 변경 파일, 최소 컴파일 결과, 남은 위험 | 승인된 production·관련 문서. 작업 항목당 1명 | 테스트 결과 조작, 범위 밖 수정, merge·배포, 자기 승인 |
| tester | 새 테스트 작성, 기존 테스트 불충분, 테스트 실패 분석이 필요할 때. 검증 명령 자체는 Quality Gate가 실행 가능 | spec 완료 조건, diff, 변경 영향 | 테스트 코드·명령·결과·실패 원인 | `src/test/**`, 테스트 fixture, 허용된 run-log | `src/main/**`, production 버그 직접 수정, 미실행 테스트 통과 선언 |
| reviewer 리뷰 모드 | Standard·Strict, Fast 중 위험 신호 발생 | 승인 revision, diff, spec·ADR·검증 근거 | 차단·권장·참고 판정, 범위 위반 | 읽기 전용. 지정 시 run-log만 허용 | 코드 수정, 자기 리뷰 결과 승인, QA 모드 겸임 |
| reviewer QA 모드 | API 동작 변경 또는 Strict의 실행 검증 | spec·API 계약·격리 환경 | 요청·응답 근거, PASS·FAIL, 미검증 조건 | QA run-log만 허용 | `src/main/**` 열람·수정, 리뷰 모드 겸임 |
| Security 게이트 | 인증·권한·결제·시크릿·외부 입력·의존성·배포·파괴적 데이터 신호 | threat delta, diff, dependency·config | 위협 목록, 필수 검증, 차단 판정 | 기본 읽기 전용 | 독립 production 구현, 위험 하향, 승인 대체 |
| Docs 게이트 | controller·요구사항 상태·ADR·하네스 계약 변경 | diff, 문서 라우팅 규칙 | 갱신 대상과 누락 목록 | 기본 읽기 전용. 별도 승인 시 문서만 | production 코드, 주석 정책 파일의 부수 수정 |
| Quality Gate | 모든 실행. 강도는 경로별 차등 | diff, 명령 계획, 역할별 근거 | 검증 판정과 미검증 영역 | 검증 로그만 | 에이전트 자기 보고만으로 통과, 실패 숨김 |
| Evidence aggregation | 모든 상태 전이. workflow step이며 에이전트가 아님 | 판단·파일·명령·결과·승인 이벤트 | 기계 판독 레코드 + 사람용 요약 | 감사 로그 | 시크릿·토큰·개인정보 원문 기록, 결과 변조 |

## 7. 전체 흐름

```text
Discovery
  → Triage
  → Approval
  → Execution
  → Quality Gate
  → Observability
       └─ 실패·보류·거절·미검증은 사람 인계 또는 다음 Discovery의 근거로만 환류
```

전체 흐름은 자동 발견 후보에 적용한다. 사람이 이미 범위가 분명한 Issue나 작업을 직접 요청한 경우에는 그 요청을 실행 의도로 보고 Discovery·후보 점수화·별도 실행 승인 요청을 반복하지 않는다. 이 반응형 경로는 기존 Issue·현재 revision·허용 파일을 확인하는 Triage-lite에서 시작한다. 범위가 모호하거나 요청 뒤에 위험이 새로 발견될 때만 정식 Approval로 전환한다.

| 진입 유형 | 시작 단계 | 생략 가능한 단계 | 승인 해석 |
| --- | --- | --- | --- |
| 자동 발견 후보 | Discovery | 없음 | 채널 또는 GitHub의 명시적 결정 필요 |
| 사람이 생성한 명확한 Issue | Triage-lite | Discovery, 5축 우선순위 점수 | Issue의 수행 요청을 실행 승인으로 인정. scope 변화 시 재승인 |
| 사람이 현재 대화에서 직접 지시 | Triage-lite | Discovery, 중복 후보 생성, 별도 승인 메시지 | 현재 지시를 승인으로 인정. 파괴적 명령과 Strict 고정 조건은 별도 승인 |
| 불명확한 제보·관찰 | Discovery 또는 `NEEDS_EVIDENCE` | 없음 | 근거와 scope가 정해지기 전 실행 금지 |

### 7.1 Discovery

1. 제품 피드백, 오류·성능 지표, 실패한 CI, flaky test, 문서·코드 불일치, 의존성 경고를 읽기 전용으로 수집한다.
2. 각 근거에 출처, 수집 시각, 대상 revision, 재현 가능 여부를 붙인다.
3. 코드나 Issue를 쓰지 않고 `DISCOVERED` 후보만 만든다.

#### 7.1.1 정기 점검은 값싼 검사에서 시작한다

정기 Discovery는 모든 문서와 외부 사이트를 매번 LLM이 읽는 crawler가 아니다. 먼저 결정적 검사와 변경 digest로 새 신호가 있는지 확인하고, 새 신호가 있을 때만 의미 판정과 후보 점수화에 LLM을 호출한다. 초기 추천 주기는 다음과 같으며 Q14의 팀 결정 전에는 운영 정책이 아니다.

| 트리거 | 최초 범위 | LLM 호출 | 결과 |
| --- | --- | --- | --- |
| 매일 1회 | 저장소 링크·경로·anchor·문서 정본 연결·CI 실패·dependency 경고의 결정적 검사 | 변화나 위반이 없으면 0회 | Evidence가 있는 후보 또는 `NO_ACTIONABLE_DELTA` receipt |
| 주 1회 | 승인된 공식 release·security advisory·서비스 변경 공지와 개인정보 제거된 사용자 지표 요약 | 변경 digest가 있을 때 1회 이하의 묶음 분석 | 최대 5개의 우선순위 후보 digest |
| 긴급 event | 새 보안 공지, 같은 revision의 반복 CI 실패, 중대한 사용자 오류 신호 | 필요한 read-only 판단만 | 즉시 후보 알림. 자동 Issue·실행은 금지 |
| 사람이 직접 요청 | 정기 실행을 기다리지 않고 §7 도입부의 Triage-lite | 별도 Discovery 반복 없음 | 현재 요청의 범위·위험·검증 계약 |

결정적 scanner는 다음 receipt를 먼저 만든다.

```yaml
source_set_version: discovery-v1
previous_digest: sha256:...
current_digest: sha256:...
scan_status: SUCCESS | PARTIAL | FAILED
source_results:
  - source_id: repo-docs
    status: SUCCESS | FAILED
    current_digest: sha256:...
    source_delta_id: repo-docs:sha256:...
    last_processed_delta_id: repo-docs:sha256:...
    last_processed_receipt_id: receipt-...
    last_successful_fetch_at: 2026-09-14T00:00:00Z
processable_delta_count: 0
failed_sources: []
last_successful_scan_at: 2026-09-14T00:00:00Z
quota_available: true
dispatch_status: READY | NO_ACTIONABLE_DELTA | DEFERRED_QUOTA | NOT_EVALUATED
```

- checkpoint 정본은 Issue 댓글이 아니라 가장 최근 scan의 GitHub Actions artifact다. 전체 scan이 `SUCCESS`이면 전체 receipt를, `PARTIAL`이면 성공한 source의 `source_delta_id`·`last_processed_delta_id`를 source별 checkpoint로 기록한다. 실패 source는 직전의 성공 checkpoint를 유지한다. artifact가 만료되거나 없으면 baseline만 다시 만들고 외부 본문 전체를 새 변화로 간주하지 않는다.
- digest는 timestamp, 응답 순서, tracking query처럼 의미 없는 volatile 필드를 제거하고 stable source ID·version·정규화 URL·추출 payload hash를 정렬해 계산한다.
- LLM job 실행 조건은 `scan_status in [SUCCESS, PARTIAL] && processable_delta_count > 0 && quota_available`다. `PARTIAL`이어도 성공한 source의 독립적인 delta는 처리하며, 실패 source가 해당 판단에 필수인 후보만 `NEEDS_EVIDENCE`로 둔다. 긴급 security delta는 다른 source 실패와 독립적으로 알릴 수 있어야 한다.
- source delta의 멱등 키는 `source_id:source_delta_id`다. receipt에 `last_processed_delta_id`가 같거나 `PROCESSING`·`PROCESSED` 상태의 동일 키가 있으면 LLM dispatch와 candidate 생성을 다시 만들지 않고 기존 receipt·candidate·run ID를 반환한다. `FAILED_RETRYABLE`만 attempt 예산 안에서 재시도하며, 성공 source의 checkpoint는 전체 scan이 `SUCCESS`가 아니어도 먼저 저장한다. 따라서 한 source가 계속 실패해도 다른 source의 같은 delta가 매번 다시 처리되지 않는다.
- quota 초과는 scanner 실패가 아니라 `DEFERRED_QUOTA`로 기록한다. `FAILED`는 수집 실패, `NO_ACTIONABLE_DELTA`는 성공했지만 처리할 변화가 없음, `DEFERRED_QUOTA`는 처리할 변화가 있으나 예산 때문에 연기됨을 뜻한다.
- scanner job에는 LLM credential을 주지 않는다. source를 실제로 읽는 최소 권한은 source 계약에 함께 선언한다: 저장소 파일은 `contents: read`, Actions run은 `actions: read`, check 결과는 `checks: read`, code-scanning/security 결과는 `security-events: read`를 사용한다. Dependabot·dependency advisory API가 요구하는 설치 권한은 workflow permission 이름으로 추측하지 않고 GitHub App/토큰의 별도 read 권한으로 명시한다. scanner는 `issues: none`, `pull-requests: none`으로 유지하고, Issue·PR을 읽는 Triage job만 `issues: read`, `pull-requests: read`를 별도로 가진다. runner의 임시 workspace·`RUNNER_TEMP`·Actions summary·artifact 쓰기는 허용하지만 repository contents와 승인 상태는 쓰지 못한다. `NO_ACTIONABLE_DELTA`는 Actions summary와 artifact에만 남기며 승인 원장 입력으로 복제하지 않는다.
- `PARTIAL`·`FAILED`는 `NO_ACTIONABLE_DELTA`와 구분한다. read-only source 재시도는 1회, 같은 source의 3회 연속 실패 뒤에만 운영 알림을 한 번 보내고 last successful fetch와 실패 source를 표시한다.

두 번째 일일 실행은 기본값으로 두지 않는다. 첫 pilot에서 하루 한 번으로도 신호가 늦게 발견됐다는 근거가 있을 때만, 전체 LLM 분석이 아니라 결정적 변화 탐지 한 번을 추가하는 선택지를 검토한다. 한 주에 후보가 5개를 넘으면 점수가 높은 신규 후보 5개만 사람에게 보여주고 나머지는 다음 digest로 이월한다.

Candidate fingerprint는 source 성격에 따라 고정한다. 외부 공지는 `source_type + stable_source_id/advisory_id + affected_component + normalized_claim + upstream_affected_version_range`, 내부 CI·문서는 `stable_rule_id + component/path + normalized_failure_signature`를 사용한다. repository SHA는 identity가 아니라 `observed_at_revision` Evidence로만 남긴다. content digest는 전달 중복 키일 뿐 의미상 동일성 키가 아니며 해결 여부는 fingerprint 변경이 아니라 resolution 상태와 재검증 결과로 판단한다. 열린 Issue·PR뿐 아니라 최근 merge·closed·rejected 후보와 resolution·cooldown을 검색한다. 중복 판단이 불확실하면 새 Issue를 만들지 않고 기존 후보 연결만 제안한다. `ACKNOWLEDGED`와 `SNOOZED_UNTIL`은 알림 metadata이며 기존 canonical 실행 상태를 바꾸지 않는다. 같은 incident·revision의 긴급 알림은 한 번만 보내고, 보류·거절·이월 후보는 근거나 상태가 바뀐 경우에만 다시 노출한다.

#### 7.1.2 문서 건강 신호

| 신호 | 결정적 검사 | 의미 판정 | 자동 행동 |
| --- | --- | --- | --- |
| 깨짐 | 존재하지 않는 상대 경로·anchor, 삭제된 정본 링크, 구문 오류 | 없음 또는 최소 확인 | 후보 생성만 |
| 뒤처짐 | 문서가 근거로 삼은 source·workflow·schema가 이후 변경됨 | 실제 계약 불일치인지 확인 | 후보 생성만 |
| 중복 | 같은 제목·링크 대상·정규화된 문단의 반복, 같은 요구사항 ID의 복수 정본 | 의도된 요약과 충돌하는 정본 중복을 구분 | 후보 생성만 |
| 과도한 길이 | 파일 크기·행 수·heading 수·context budget 초과를 신호로 기록 | 독립 소유 주제가 섞였는지, 분리 시 왕복 참조가 늘지 않는지 확인 | 길이만으로 분리 금지 |
| 고립 | `README`, context router, 상위 index, 관련 ADR·spec에서 도달할 수 없음 | 폐기 문서인지 누락된 진입점인지 확인 | 삭제·링크 추가 모두 사람 결정 |

문서 검사는 `AGENTS.md`, `ai/context-router.md`, ADR·spec의 정본 관계를 우선하며 저장소 전체 문장을 매일 embedding하지 않는다. `ai/context-router.md`의 전체 순회 금지는 LLM context 로딩에 계속 적용한다. H-22의 결정적 script는 경로·link graph·heading metadata만 열거할 수 있지만, 그 결과로 모든 본문을 LLM에 전달할 수는 없다. 긴 문서는 행 수 하나로 실패 처리하지 않고 `반복`, `복수 정본`, `독립 소유 주제`, `검색·context 비용` 중 하나 이상의 추가 근거가 있을 때만 개선 후보가 된다.

#### 7.1.3 외부 정보와 사용자 신호의 경계

- 외부 입력은 팀이 승인한 allowlist의 공식 문서, release feed, security advisory, 사용 중인 서비스의 변경 공지부터 시작한다. RSS·공식 API·GitHub release처럼 구조화된 입력을 우선하고 로그인 우회, robots·이용약관 위반, 무제한 범용 web crawl은 금지한다.
- 외부 fetch job과 LLM 판정 job을 분리한다. fetch는 고정 host·HTTPS·redirect allowlist·content type·본문 최대 크기·rate limit을 결정적으로 검사하고 source URL, redirect chain, content hash, fetch timestamp를 Evidence에 남긴다.
- 외부 문서의 본문과 명령은 신뢰하지 않는 data envelope 안에 둔다. 제안 근거로 인용할 수 있지만 하네스 정책, 명령, 권한을 바꾸는 지시로 실행하지 않는다. 판정 LLM에는 shell·secret·쓰기·추가 외부 network 권한을 주지 않는다.
- 사용자 확대 실험은 하네스가 새 실험을 임의로 시작하는 기능이 아니다. 별도 승인된 제품 실험의 익명·집계 결과, 반복 문의, 오류율·이탈률 변화를 read-only 입력으로 받는다.
- 개인 메시지·계정 식별자·원문 로그는 Candidate memory에 넣지 않는다. 원문→집계는 승인된 원천 시스템에서 끝내고 하네스에는 집계 event만 전달한다. 최소 cohort·소수 집단 억제·허용 차원·민감 속성 금지·동의 또는 법적 근거·보존 기간·접근 주체·삭제 방식이 정해지지 않으면 connector를 만들지 않는다. 임계값 미달 cohort는 `NEEDS_POLICY` Candidate가 아니라 입력 단계에서 폐기한다.
- 외부 변화나 사용자 신호만으로 Issue를 자동 생성하거나 구현하지 않는다. Triage 뒤 GitHub 또는 첫 외부 채널의 `승인`, `이슈만 등록`, `보류`, `거절` 중 하나를 기다린다.

### 7.2 Triage

1. 열린 Issue·PR, 최근 merge, `ai/specs/**`, ADR을 검색해 중복을 계산한다.
2. 사용자 영향·근거 신뢰도·작업량·위험도·중복 여부를 점수화한다.
3. 위험 등급과 Fast·Standard·Strict 경로를 결정한다.
4. 예상 파일, 허용 명령, 승인 지점, 품질 게이트를 제안한다.
5. 정보가 부족하면 위험을 낮추지 않고 `NEEDS_EVIDENCE`로 둔다.

### 7.3 Approval

1. 사람에게 판단 근거와 선택지 `승인`, `이슈만 등록`, `보류`, `거절`을 보여준다.
2. 결정은 `candidate_id + proposal_revision + action`으로 멱등하게 기록한다.
3. 승인 후 계획·위험·예상 파일이 달라지면 revision을 올리고 기존 승인을 무효화한다.

### 7.4 Execution

1. 승인된 revision으로 Issue와 격리 worktree를 연결한다.
2. 한 Issue·한 PR, 작업 항목별 production 작성자 한 명 원칙을 지킨다.
3. 승인된 파일·명령 범위를 벗어나면 즉시 중단하고 재승인을 요청한다.

### 7.5 Quality Gate

1. 실제 diff가 승인 범위와 맞는지 확인한다.
2. 경로가 요구하는 결정적 검사, tester, reviewer, Security·Docs 게이트를 실행한다.
3. 파일·명령·종료 코드·테스트 수·외부 환경 여부를 근거로 판정한다.
4. 미검증 영역이 있으면 통과로 간주하지 않고 사람에게 노출한다.

### 7.6 Observability

1. 후보 생성부터 종료까지 상태별 체류 시간과 재시도·실패·재작업을 기록한다.
2. 위험도·경로별 속도와 결함 유출을 비교해 임계값을 조정한다.
3. 원문 시크릿과 긴 로그는 저장하지 않고 링크·해시·요약을 남긴다.
4. 정기 Discovery는 실행 횟수, 변화 없음 비율, LLM 호출 회피율, 후보 수, 중복 억제 수, 사람 채택률, 잘못된 경보율을 기록한다.
5. 사용자 신호 기반 후보는 집계 cohort와 관찰 창을 기록하되 개인 식별자와 원문 피드백을 Evidence에 복제하지 않는다.

## 8. 개선 후보 점수 기준

각 항목을 0~5점으로 평가한다. 점수와 근거를 함께 기록하지 않으면 Triage 완료로 보지 않는다.

| 항목 | 0점 | 1점 | 3점 | 5점 |
| --- | --- | --- | --- | --- |
| 사용자 영향 `U` | 사용자 영향 없음 | 내부 불편 또는 극소수 | 주요 흐름의 반복 불편·성능 저하 | 데이터 손실·서비스 불능 또는 다수 핵심 사용자 영향 |
| 근거 신뢰도 `C` | 추측만 있음 | 단일 제보, 재현 없음 | 로그·테스트·복수 제보 중 2종 | 재현 테스트 + 운영 지표 + 코드 근거가 서로 일치 |
| 작업량 `E` | 1파일 문서 수정 | 1~2파일 단순 변경 | 한 도메인 3~7파일·1 PR | 여러 도메인·마이그레이션·외부 시스템으로 분할 필수 |
| 위험도 `R` | 문서·비동작 파일 | 테스트·내부 도구 | API·서비스 동작 변경 | 인증·결제·권한·삭제·배포·파괴적 스키마 |
| 중복 `D` | 관련 작업 없음 | 유사 기록만 있음 | 열린 Issue·PR과 일부 겹침 | 같은 원인·완료 조건의 열린 Issue·PR이 존재 |

표에 생략된 2점과 4점은 양옆 기준의 중간 상태로만 사용하고, 근거에 어느 경계에 가까운지 적는다. 위험 점수는 `0~1=낮음`, `2~3=보통`, `4=높음`, `5=매우 높음`의 표시 등급에 매핑하며 실행 경로는 §9.1로 별도 판정한다.

추천 우선순위 점수는 다음처럼 0~100으로 정규화한다.

```text
priority = round(20 × (4U + 3C + 2(5-E) + 3(5-R) + 4(5-D)) / 16)
```

- 75~100은 승인 큐의 우선 후보로 올린다.
- 55~74는 일반 backlog 후보로 둔다.
- 35~54는 근거 보강 또는 범위 축소 후 다시 평가한다.
- 0~34는 기본 보류한다.
- `D=5`인 후보는 점수와 무관하게 새 Issue를 만들지 않고 기존 Issue·PR에 근거만 연결한다.
- `R=5`여도 §9.1의 Strict 고정 조건이 없으면 Standard에 필수 전문 게이트를 추가한다. 우선순위 점수가 실행 경로를 바꾸지 않는다.
- 이 점수는 우선순위 추천이며 승인이나 위험도 판정을 대체하지 않는다.

## 9. 위험 등급

위험 등급과 실행 경로를 일대일로 묶지 않는다. 위험 등급은 가장 높은 단일 위험을 사람에게 보여주는 표시이고, 실행 경로는 비가역적 Strict 고정 조건으로 따로 결정한다. 따라서 여러 차원이 높음이어도 복구·검증이 가능하고 Strict 고정 조건이 없으면 Standard에 필요한 전문 게이트만 추가한다.

| 판정 차원 | 낮음 | 보통 | 높음 | 매우 높음 |
| --- | --- | --- | --- | --- |
| 실행 영향 | 비동작 문서 | 단일 기능 동작 | 여러 연관 구성요소 또는 workflow 동작 | 배포·운영 제어 |
| 데이터 가역성 | 데이터 무관 | 쉽게 재생성 가능 | 비파괴 migration·대량 쓰기 | 삭제·파괴적 migration·복구 불확실 |
| 권한·보안 | 권한 무관 | 인증된 일반 기능 | 인증·인가 판단·신뢰하지 않는 외부 입력 | 시크릿·권한 확대·결제·개인정보 |
| 영향 반경 | 개발 문서만 | 단일 도메인 | 공통 인프라 또는 다수 호출부 | 전체 서비스·다수 사용자 |
| 검증 난이도 | 링크·정적 검사 | 대상 테스트로 재현 | 통합 환경·동시성 필요 | 운영 환경에서만 확인 가능하거나 rollback 불확실 |

| 등급 | 판정 예시 | 기본 경로 | 자동화 허용 범위 | 사람 승인 지점 |
| --- | --- | --- | --- | --- |
| 낮음 | Markdown·정적 문서, production 미사용 테스트 fixture, 동작을 바꾸지 않는 소규모 설정 | Fast 후보 | 자동 발견이면 Discovery·중복 검색·점수화, 직접 요청이면 Triage-lite. 공통으로 diff·링크·정적 검사 | 자동 발견이면 실행 전 1회, 직접 요청이면 현재 요청으로 갈음, PR merge |
| 보통 | 단일 도메인 서비스·API 동작, 비파괴 설정, 테스트 추가·버그 수정 | Standard | 계획, worktree, 승인 범위 내 구현, 테스트, 독립 리뷰, 실패 1회 재시도 | Issue 등록 또는 실행 전, PR merge |
| 높음 | 인증 판단, 동시성·트랜잭션, 비파괴 DB migration, 외부 API 쓰기, workflow 동작 변경 | Standard + 필수 전문 게이트. Strict 고정 조건이면 Strict | 승인 범위 내 구현과 해당 전문 검증. 위험을 낮추지는 않음 | 실행 전, scope 변화, PR merge |
| 매우 높음 | 결제, 데이터 삭제·대량 수정, 파괴적 migration, 시크릿·권한 확대, 배포·rollback, 규제·개인정보 | §9.1의 고정 조건이면 Strict. 등급 이름만으로는 경로를 결정하지 않음 | 고정 조건이면 Discovery·Issue 초안·검증 계획까지만 자동 | Issue 등록, 실행 허용, 각 파괴적 명령, PR merge·배포 판단 |

### 9.1 실행 경로 결정 규칙

- **Standard가 기본값이다.** Fast의 명시적 허용 조건을 모두 만족하지 않고 Strict 조건에도 해당하지 않는 작업은 Standard로 간다.
- 다음 중 하나면 다른 점수와 무관하게 Strict로 고정한다.
  - 데이터 삭제·파괴적 migration·복구 절차 없는 대량 쓰기.
  - production 배포·rollback·인프라 쓰기 또는 시크릿 접근.
  - 권한 부여·회수, 인증 우회 가능성, 결제·실제 자산 이동.
  - 외부 시스템에 되돌릴 수 없는 쓰기 요청.
  - 운영 환경에서만 검증 가능하고 안전한 rollback이 정의되지 않은 변경.
- Strict 고정 조건이 없으면 높은 위험 차원이 여러 개여도 Standard를 유지하고 필요한 전문 게이트를 합성한다. 게이트가 한 Issue·한 PR에서 감당하기 어려우면 Strict로 올리지 않고 작업을 분할한다.
- Fast는 `src/main/**`, migration, workflow 권한, 외부 쓰기, 보안 경계가 없고 허용 목록의 문서·비동작 파일만 바꾸는 경우에만 선택한다. 파일 수가 적다는 이유만으로 Fast가 되지는 않는다.
- 외부 API, 동시성, DB, Docs, Security 같은 단일 신호는 전체 경로를 바로 Strict로 올리지 않고 해당 전문 게이트를 Standard에 추가한다.
- 예상 파일 수와 도메인 수는 위험도가 아니라 작업 크기다. 7파일 초과 또는 독립 도메인 2개 이상이면 먼저 한 Issue·한 PR 단위로 분할하되, 분할 전후 경로는 실제 위험으로 다시 계산한다.
- 근거가 부족하면 자동으로 Strict로 올리는 대신 `NEEDS_EVIDENCE`에서 멈춘다. 사람이 불확실성을 수용하고 실행하려는 경우에만 새 revision에서 승인 강도를 정한다.
- 실제 diff가 예상 파일 또는 변경 종류를 벗어나면 `SCOPE_CHANGED`로 중단하고 새 revision 승인을 받는다.
- 실행 중 §9.1의 Strict 고정 조건이 하나라도 새로 발견되면 즉시 중단하고 Strict 새 revision으로 재승인한다. 일부 조건을 다시 열거해 누락시키지 않는다.

## 10. Fast·Standard·Strict 실행 경로

| 단계 | Fast | Standard | Strict |
| --- | --- | --- | --- |
| 적용 위험 | 낮음 + 허용 목록 충족 | Strict 고정 조건이 없는 모든 등급. 기본 경로 | §9.1의 Strict 고정 조건만 |
| Discovery·Triage | 자동 발견은 전체 흐름, 직접 요청은 Triage-lite | 자동 발견은 전체 흐름, 직접 요청은 Triage-lite | 진입 유형에 따라 전체 흐름 또는 Triage-lite + planner 상세 근거 검토 |
| 승인 | 자동 발견은 Issue+실행 결합 승인 1회, 직접 요청은 현재 요청으로 갈음 | 자동 발견은 실행 승인 1회, 직접 요청은 현재 요청으로 갈음. 높음이면 Issue만 등록을 먼저 선택 가능 | 새 후보는 Issue 등록과 실행 승인을 분리. 기존 Issue·직접 요청도 요청자와 다른 사람의 실행 승인 필요. 계획 변경 시 재승인 |
| 계획 문서 | Issue 본문 또는 짧은 변경 계약 | 기존 spec 또는 응집된 plan | spec + 새 ADR 필요 여부 + rollback·보안·검증 계획 |
| 구현 | 메인 세션의 문서 경량 경로 또는 승인된 단일 작성자 | implementer 1명 | MVP에서는 사람이 구현. 향후 Q3에서 B를 선택한 경우에만 별도 승인 후 implementer 1명의 격리 초안을 허용 |
| tester | 결정적 검사로 충분하면 별도 호출 생략 | 테스트 근거는 필수, 별도 tester 호출은 새 테스트·보강·실패 분석 때만 | 테스트 근거 필수 + 필요 시 tester, 환경·데이터 격리 |
| reviewer | 문서·비동작 파일이고 정적 게이트가 통과하면 별도 호출 생략 | 신규 독립 세션 1회 | 신규 독립 세션 + 필요 시 QA 분리 |
| Security | 위험 신호가 있을 때만. 신호가 있으면 Standard 이상 승격 | 조건부 | 필수 |
| Docs | 문서 링크·범위 검사 또는 조건부 게이트 | API·PRD·ADR 영향 시 호출 | 항상 영향 판정, 필요한 정본별 별도 확인 |
| 재시도 | 결정적 명령의 일시 실패 1회. 코드 자동 수정 없음 | 같은 implementer가 차단 결함 자동 수정 최대 1회 가능 | 환경 재시도만 최대 1회. 코드 수정은 재승인 |
| 완료 조건 | diff 범위·링크·정적 검사 | 대상 테스트 + 독립 리뷰 + 미검증 공개 | 전체 관련 검증 + Security·Docs + rollback·수동 확인 목록 |
| merge·배포 | 사람 merge | 사람 merge | 사람 merge. 배포는 기존 CD 정책과 별도 사람 판단 |

Fast는 품질을 생략하는 경로가 아니라 결정적이고 저렴한 게이트로 대체하는 경로다. Fast 허용 목록을 벗어나면 Standard로 올리되, 위험 신호의 수나 합계만으로 Strict를 선택하지 않는다. Strict는 예외 경로이며 선택 사유에 해당 고정 조건을 반드시 표시한다.

### 10.1 명령과 역할 호출의 단일 소유권

| 작업 | 유일한 실행 소유자 | 다른 역할의 처리 |
| --- | --- | --- |
| 최소 production 컴파일 | implementer | tester·reviewer는 결과를 다시 실행하지 않고 참조 |
| 변경 대상 테스트 | tester 또는 기존 테스트가 충분하면 Quality Gate | implementer는 테스트 통과를 자기 보고하지 않음 |
| 최종 범위 검증·build | Quality Gate의 runner | implementer prompt 안의 전체 build를 제거해 같은 revision에서 중복 실행하지 않음 |
| 코드 리뷰 | 신규 reviewer 세션 | implementer·tester는 승인 판정을 하지 않음 |
| 자동 수정 후 검증 | Quality Gate runner가 변경된 revision을 1회 재검증 | 수정 전 결과는 stale로 표시 |
| Evidence 집계 | workflow 마지막 aggregation step | 별도 LLM·Recorder 서비스 호출 없음 |

Standard의 수정 1회가 실행되면 대상 테스트, 최종 Quality Gate, 독립 reviewer를 모두 새 revision 기준으로 다시 실행한다. Fast는 코드 자동 수정을 허용하지 않는다. Strict는 코드 수정 전에 새 승인을 받는다.

### 10.2 기존 `agent.yml` 단계 처리

적응형 라우터는 기존 workflow 앞에 다른 장 workflow를 중첩하지 않는다. 같은 workflow 안의 얇은 분기이며 기존 단계는 다음처럼 처리한다.

| 현재 단계 | Fast | Standard | Strict |
| --- | --- | --- | --- |
| `propose-directions` | 자동 발견 후보에만 유지 | 자동 발견 후보에만 유지 | Issue 등록 승인 전 읽기 전용 제안만 유지 |
| `verify-actor` | 유지 | 유지 | 유지 + 실행 승인자 분리 확인 |
| `implement` | 문서·비동작 파일 수정만 수행, 전체 build 지시 제거 | 유지하되 전체 build 지시 제거 | 별도 실행 승인 후에만 수행 |
| runner `build` | 생략하고 링크·범위·문서 검사 | 최종 build의 유일 소유자로 유지 | 승인된 검증 계획의 유일한 광역 실행 소유자 |
| `review` | 허용 목록이면 생략 | 신규 독립 호출 1회 유지 | 신규 독립 호출 + 고정 조건에 필요한 사람·전문 검토 |
| `autofix`·재리뷰 | 사용하지 않음 | 차단 사항에 한해 최대 1회 유지 | 자동 수정 금지 |
| `gh pr review --approve` | 제거 | 제거 | 제거 |

로컬 `feature`도 같은 단일 소유권을 적용하되 현재 역할 분리는 유지한다. 기존 역할·스킬 변경 전에는 중복 build 제거를 MVP 효과로 주장하지 않는다.

### 10.3 경로별 호출 예산

| 경로 | 초기 LLM 역할 호출 예산 | 광역 build | 독립 reviewer | 추가 사람 승인 |
| --- | --- | --- | --- | --- |
| Fast·사람 직접 요청 | 현재 메인 세션 외 추가 역할 0 | 0 | 0 | 0. 현재 요청을 승인으로 사용 |
| Fast·자동 발견 | 방향 제시 1 + 문서 실행 1 이하 | 0 | 0 | 실행 승인 1 |
| Standard | planner 0~1 + implementer 1 + tester 0~1 + reviewer 1, 합계 2~4 | 최종 revision당 1 | 1 | 직접 요청이면 0, 자동 발견이면 1 |
| Standard 자동 수정 1회 포함 | 최초 예산 + implementer 재개 1 + tester 0~1 + reviewer 1, 누적 최대 7 | 수정 후 revision 1회 추가 | 재리뷰 1 | scope가 같으면 0 |
| Strict | MVP는 planner 1 + reviewer 1 + Security 1 + QA 0~1, 합계 3~4. Q3에서 B를 선택할 때만 implementer·tester 각 0~1 추가 | 승인된 계획에 명시 | 최소 1 | Issue 등록, 실행, 파괴적 명령별 승인 |

Docs 게이트와 Evidence aggregation은 MVP에서 결정적 workflow step이므로 LLM 호출 수에 포함하지 않는다. Standard 자동 수정 경로의 호출 상한은 위 표의 **7회**로 고정한다. `repair_round=1`을 이미 사용했거나 7회에 도달하면 자동으로 역할을 더 만들지 않고 `NEEDS_HUMAN`에서 초과 이유와 예상 효과를 제시한다. 이 숫자는 `agent.yml` fixture·정책 테스트·화이트박스 출력의 `max_role_calls`와 같은 상수로 검증하며, 문서에 다른 상한을 중복 선언하지 않는다.

### 10.4 Task Coordinator 계약

Coordinator는 별도 대화형 에이전트가 아니라 오케스트레이터 내부의 결정적 정책 계층이다.

| 항목 | 계약 |
| --- | --- |
| 입력 | Issue·proposal revision, 의존 작업, 위험 신호, 예상 파일, 역할별 capability, 현재 workload, token·시간 예산, 과거 검증 패턴 ID |
| 출력 | task DAG, 실행 순서·병렬 그룹, 호출 역할, 추천 model tier, 허용 파일·명령, Quality Gate, 중단 조건 |
| 쓰기 | 승인 원장의 상태 이벤트와 Evidence 연결만. production·테스트·설계 파일 직접 작성 금지 |
| 배정 | capability 충족을 먼저 확인하고 workload·과거 성공률은 동률 해소에만 사용. 낮은 비용만으로 역할·모델을 선택하지 않음 |
| 병렬화 | 독립 파일·독립 검증만 병렬. 같은 파일 작성, implementer→tester, 변경 후 reviewer는 순차 |
| 실패 | 적합한 역할이 없거나 예산을 넘으면 자동 spawn하지 않고 `NEEDS_HUMAN`. dependency 실패 시 downstream task는 `BLOCKED` |

### 10.5 제한된 공유 메모리

공유 메모리는 프롬프트 원문 공유소가 아니라 검증된 실행 지식의 검색 인덱스다.

| 영역 | 저장 가능 | 저장 금지 | 기본 scope |
| --- | --- | --- | --- |
| Run Evidence | Issue·revision·실제 파일·명령·종료 코드·검증·미검증·사람 판정 | 토큰·시크릿·개인정보·전체 대화 원문 | repository + issue + revision |
| Verified Pattern | 재현 가능한 문제 유형, 적용 조건, 검증 명령, 성공·실패 수, confidence, 근거 run ID | AI 완료 선언만 있는 패턴, 한 번의 성공을 일반화한 규칙 | repository + task type + domain |
| Anti-pattern | 실패한 라우팅·중복 build·scope 위반·회귀와 실패 원인 | 근거 없는 모델 평가·개인별 성과 순위 | repository + policy version |

- agent는 전체 memory를 받지 않고 Coordinator가 선택한 최대 3개 reference ID와 짧은 요약만 받는다. 원문은 필요할 때 명시적으로 조회한다.
- Evidence Record가 완전하고 Quality Gate 및 사람 최종 판정이 연결된 run만 Verified Pattern 후보가 된다.
- Issue·댓글·diff·명령 출력은 모두 비신뢰 입력이다. 저장 전에 secret·PII·credential·prompt-injection 패턴을 redaction/DLP로 검사하고, `trust=untrusted_reference`와 source event digest를 붙인다. retrieved pattern은 명령이 아니라 인용 가능한 참고자료로만 전달한다.
- pattern은 source path/glob, policy·schema version, 검증 명령 digest, 성공·실패 횟수, 마지막 검증 시각, TTL을 가진다. 관련 path, 정책 version, schema 또는 검증 명령이 바뀌면 자동 invalidation한다. 90일 동안 재검증되지 않은 pattern은 기본 만료시키되 정확한 보존 기간은 Q13에서 확정한다.
- bot 상태 이벤트의 digest chain이 끊기거나 원본이 삭제·변조되면 해당 Evidence와 파생 pattern을 격리한다. 원본 삭제 요청은 파생 요약·embedding·cache까지 같은 tombstone ID로 제거한다.
- memory 검색 실패·오염 의심·backend 미사용 상태는 빈 성공으로 숨기지 않고 `memory_status`와 함께 공개하며, 기존 정적 정책으로 fallback한다.
- 서로 다른 Issue의 미커밋 diff와 비공개 프롬프트 원문은 공유하지 않는다.

### 10.6 Shadow model router와 학습 승격

모델 이름은 공급자에 종속시키지 않고 `ECONOMY`, `DEFAULT`, `DEEP` capability tier로 기록한다. 실제 모델 매핑은 실행 환경의 승인된 목록을 사용한다.

1. 결정적 안전 규칙이 먼저 최소 tier를 정한다. Security·Strict·근거 부족 신호는 학습 결과로 하향할 수 없다.
2. shadow router는 task type, 위험, 파일·도메인 수, 필요한 도구, 유사 pattern 결과로 추천 tier와 confidence를 만들지만 실제 실행 모델은 바꾸지 않는다.
3. Evidence에는 concrete provider/model ID, capability mapping version, mapping 검증 suite version, actual·recommended tier를 기록한다. production implementer와 독립 reviewer의 안전 하한은 `DEFAULT`, Strict 계획·Security 전문 판단은 `DEEP`이며 학습이 이를 내릴 수 없다.
4. 실제 실행 뒤 사람 판정, reviewer 차단, 재작업, token, 비용, machine time을 동일 run ID로 연결한다. AI 자기 평가는 reward가 아니다. Q12가 확정되기 전에는 Verified Pattern 성공이나 정책 reward로 승격하지 않는다.
5. shadow log는 추천의 분포와 후보만 만든다. 품질 효과는 frozen task packet을 baseline tier와 후보 tier에 같은 입력·도구·예산으로 격리 재실행하는 paired replay로 측정한다. 비용표 기반 추정만으로 승격하지 않는다.
6. 평가 분모는 성공한 run이 아니라 eligible 상태에서 dispatch된 모든 paired attempt다. 실패·timeout·사람 인계·재시도 token도 포함한다. primary 지표는 task당 총 input+output token이며 20% 이상 감소해야 하고, 실제 비용은 악화되면 안 된다.
   - eligible은 frozen task packet, 같은 도구 권한·시간·token 상한, 두 tier 모두 사용 가능한 preflight를 통과한 pair다. preflight 뒤 한쪽이라도 dispatch되면 양쪽 결과를 분모에 남기며 실패를 사후 제외하지 않는다.
7. 품질 비열화 한계는 reviewer blocking·실패·사람 거절을 합친 비율 `+2%p`다. frozen cohort·policy version을 고정하고 paired delta의 bootstrap 95% CI 상한이 `+2%p` 이하일 때만 통과한다. tier·task cohort별 최소 30쌍을 요구한다.
8. 후보는 held-out replay, receipt coverage 100%, 기존 안전 fixture를 통과하고 PR diff로 제시된다. 새 concrete model mapping도 같은 suite를 통과하기 전 활성화하지 않는다. 자동 적용·자동 승인하지 않으며 사람 merge 후 첫 10건은 canary로 운영한다.
9. canary의 차단률·회귀율·재작업이 baseline보다 악화되면 즉시 이전 정적 mapping으로 rollback한다.

학습 대상은 역할 호출 여부, model tier 추천, memory 검색 순위뿐이다. production 코드, 테스트 기대값, 사람 승인 규칙, 권한, Strict 고정 조건은 자동 학습·변경 대상이 아니다.

## 11. 승인 상태 전이

### 11.1 상태

```text
DISCOVERED
  → NEEDS_EVIDENCE → TRIAGED
  → TRIAGED
  → AWAITING_APPROVAL
       ├─ APPROVED_FOR_EXECUTION → DISPATCH_PENDING → EXECUTING → QUALITY_GATE → READY_FOR_HUMAN_REVIEW
       ├─ ISSUE_REGISTRATION_APPROVED → ISSUE_REGISTERED → AWAITING_EXECUTION_APPROVAL
       ├─ ON_HOLD(previous_state) → previous_state
       └─ REJECTED

EXECUTING 또는 QUALITY_GATE
  ├─ RETRYABLE_FAILURE → EXECUTING 또는 QUALITY_GATE
  ├─ SCOPE_CHANGED → AWAITING_APPROVAL
  ├─ TIMED_OUT → NEEDS_HUMAN
  └─ FAILED → NEEDS_HUMAN

READY_FOR_HUMAN_REVIEW
  ├─ 사람이 merge → MERGED
  ├─ 변경 요청 → proposal_revision 증가 → AWAITING_EXECUTION_APPROVAL
  └─ 종료 → CLOSED_WITHOUT_MERGE
```

### 11.1.1 상태별 실패·재시도·복구 매트릭스

§12의 일반 실패 분류를 상태별로 다음처럼 고정한다. 재시도는 같은 `candidate_id:proposal_revision`을 유지하고, 새 승인이나 새 범위가 필요하면 `proposal_revision`을 먼저 증가시킨다.

| 현재 상태 | 실패 상황 | 자동 처리 | 재시도 상한·backoff | 상한 초과/수동 조치 |
| --- | --- | --- | --- | --- |
| `ISSUE_REGISTRATION_APPROVED` | GitHub Issue 생성 API 일시 오류 | `ISSUE_REGISTERED`로 전이하지 않고 동일 idempotency key로 재시도 | 1회, 30초 backoff | `NEEDS_HUMAN`; Issue 번호를 확인한 뒤 수동 reconcile |
| `ISSUE_REGISTERED` | Issue 번호·canonical state comment 기록 실패 | Issue 존재 여부와 fingerprint를 조회해 이미 등록됐으면 번호를 채택 | 조회 1회 후 1회 재시도 | 중복 Issue를 만들지 않고 `NEEDS_HUMAN` |
| `DISPATCH_PENDING` | dispatcher 중단 또는 workflow run ID 미기록 | `execution_id`를 workflow input·run 목록에서 조회하고 발견한 기존 run을 채택 | lease 만료 전 재조회; 만료 후에도 새 execution은 금지 | `NEEDS_HUMAN`; 기존 run 확인 후 수동 reconcile |
| `AWAITING_APPROVAL`, `AWAITING_EXECUTION_APPROVAL` | Slack·Discord adapter 전송/응답 오류 | canonical 원장은 변경하지 않고 adapter만 재전송 | 1회, 30초 backoff | 승인 상태를 바꾸지 않고 `NEEDS_HUMAN` |
| `EXECUTING`, `QUALITY_GATE` | workflow job 실패 | 실패 분류가 `RETRYABLE_FAILURE`일 때만 같은 execution의 attempt 증가 | 환경 오류 1회 | 소진 시 `FAILED` 또는 `TIMED_OUT` → `NEEDS_HUMAN` |

`ISSUE_REGISTERED`와 `DISPATCH_PENDING`에서 “성공했을 수도 있는 요청”을 새로 발행하지 않는 것이 핵심 invariant다. 복구 작업은 먼저 조회하고, 기존 결과를 찾지 못했다는 근거가 있을 때만 같은 실행 키로 한 번 재시도한다.

### 11.2 Slack·Discord 메시지 계약

Slack 버튼과 Discord component는 같은 canonical action을 전송한다. 채널 메시지는 상태 원장이 아니라 표시·입력 어댑터다.

| 사용자 선택 | canonical action | 허용 상태 | 결과 상태 | 의미 |
| --- | --- | --- | --- | --- |
| 승인 | `APPROVE_EXECUTION` | `AWAITING_APPROVAL`, `AWAITING_EXECUTION_APPROVAL` | `APPROVED_FOR_EXECUTION` | 표시된 revision·파일 범위·경로의 실행 허용 |
| 이슈만 등록 | `REGISTER_ISSUE_ONLY` | `AWAITING_APPROVAL` | `ISSUE_REGISTRATION_APPROVED` → `ISSUE_REGISTERED` → `AWAITING_EXECUTION_APPROVAL` | Issue 등록만 승인하고 구현은 시작하지 않음. Strict의 첫 승인으로 사용 |
| 보류 | `HOLD` | 종료 전 모든 승인 대기 상태 | `ON_HOLD` | 이유와 재검토 시점을 기록하고 자동 실행 중단 |
| 거절 | `REJECT` | 종료 전 모든 승인 대기 상태 | `REJECTED` | 새 근거나 새 revision 없이는 재개하지 않음 |
| 보류 해제 | `RESUME` | `ON_HOLD` | 저장된 `previous_state` | 보류 직전의 승인 단계로 복귀. revision이 바뀌었으면 `AWAITING_APPROVAL` |
| 실행 중단 | `CANCEL` | `APPROVED_FOR_EXECUTION`, `DISPATCH_PENDING`, `EXECUTING`, `QUALITY_GATE` | `NEEDS_HUMAN` | 새 쓰기 작업을 시작하지 않고 안전 지점에서 중단 |

모든 승인 메시지는 candidate ID, proposal revision, 위험도·경로, 예상 파일, 실행 명령 등급, 검증 계획, 미검증 예정 영역, Issue 링크를 보여준다. 버튼 처리 응답은 결정자, 처리 시각, 최종 상태, 중복 또는 거절 사유를 같은 thread에 갱신한다.

### 11.3 중복 승인과 상충 결정

- 멱등 키는 `candidate_id:proposal_revision:action`이다.
- Slack·Discord의 `interaction_id` 또는 message ID는 전달 중복 탐지용 보조 키로 기록한다.
- 같은 멱등 키가 다시 오면 새 실행을 만들지 않고 기존 결정·run ID를 반환한다.
- 이미 종료된 revision에 다른 action이 오면 `409 DECISION_CONFLICT`로 거절하고 현재 상태를 보여준다.
- 더 높은 proposal revision이 생기면 이전 승인은 `STALE_APPROVAL`이 되며 자동 승계하지 않는다.
- 상태 갱신은 expected version을 받는 낙관적 잠금 또는 동등한 compare-and-set으로 한 번만 성공하게 한다.
- 한 채널의 성공 응답을 다른 채널에도 갱신하되, 갱신 실패가 승인 원장 자체를 되돌리지는 않는다.

### 11.4 승인자 권한

- GitHub collaborator 또는 팀 membership은 **승인 후보인지 확인하는 자격**일 뿐 `APPROVE_EXECUTION` 권한 그 자체가 아니다. 별도의 `approver` allowlist/team을 두고, canonical 원장의 `APPROVE_EXECUTION`은 그 목록에 속한 사용자만 처리한다. 저장소 collaborator라도 approver가 아니면 승인할 수 없다.
- 권한을 다음 네 층으로 분리한다.

| 주체 | Issue·PR 읽기 | Issue 등록 | `APPROVE_EXECUTION` | workflow dispatch |
| --- | --- | --- | --- | --- |
| 일반 collaborator/team member | 허용 범위 내 | 정책이 허용한 경우만 | 금지 | 금지 |
| `approver` allowlist/team | 허용 | 정책이 허용한 경우 | 허용 | 직접 dispatch 금지 |
| dispatcher workflow bot | 필요한 읽기와 상태 comment 쓰기 | 위임된 등록만 | 금지 | 승인 원장 확인 후에만 허용 |
| `reviewer` agent | 읽기·검증 | 금지 | 금지 | 금지 |

- `workflow_dispatch`를 호출할 수 있는 GitHub Actions 권한은 사람의 `APPROVE_EXECUTION` 권한과 별개다. workflow token이 dispatch할 수 있어도 승인 이벤트가 없으면 실행하지 않는다.
- 현재 `agent.yml`의 댓글 작성자·Issue 작성자 collaborator 검사를 유지하되, 승인 처리 시에는 collaborator/team membership과 `approver` allowlist를 모두 다시 확인한다.
- Slack·Discord 사용자는 사전에 연결된 GitHub 사용자로 매핑하고, 결정 처리 시마다 현재 collaborator 권한을 다시 확인한다. 캐시만으로 승인하지 않는다.
- 연결 해제, 팀 탈퇴, 권한 회수는 다음 결정부터 즉시 반영한다. 채널 표시 이름만으로 사용자를 식별하지 않는다.
- Fast·Standard에서 권한 있는 사람이 직접 요청한 작업은 같은 사람의 실행 의도로 인정할 수 있다.
- Strict에서는 실행 요청자와 실행 승인자가 달라야 한다. 승인자가 한 명뿐이면 구현하지 않고 `NEEDS_HUMAN`으로 둔다.
- 어떤 경로에서도 구현 에이전트, reviewer, workflow bot은 사람 승인을 대신할 수 없다.

### 11.5 승인과 실행의 단일 dispatch

- 실행 고유 키는 `candidate_id:proposal_revision`이며 이 키당 `execution_id` 하나만 발급한다.
- 사람의 리뷰 변경 요청은 scope가 같아도 `proposal_revision`을 증가시킨다. 승인된 실행 안의 Standard 자동 수정 1회는 새 execution이 아니라 같은 `execution_id`의 `repair_round=1`로 기록한다. 환경·조회 재시도는 `attempt`만 증가시킨다.
- MVP는 별도 Approval Gateway 서비스를 만들지 않는다. 하나의 GitHub workflow dispatcher가 승인 상태 재확인, `execution_id` 기록, 실행 시작을 같은 직렬화 구간에서 처리한다.
- canonical 원장은 대상 Issue의 bot 소유 append-only 상태 이벤트 comment다. 각 이벤트는 `candidate_id`, 단조 증가 `version`, `proposal_revision`, `state`, `execution_id`, 이전 이벤트 digest를 포함한다. label과 채널 메시지는 조회용 projection이며 원장이 아니다.
- dispatcher만 상태 이벤트를 쓸 수 있다. `concurrency.group` 안에서 마지막 bot 이벤트의 version·digest를 다시 읽고 요청의 expected version과 다르면 `409 DECISION_CONFLICT`로 종료한다. 새 이벤트를 쓴 뒤 다시 읽어 자신의 event ID·digest를 확인한 경우에만 다음 단계로 간다.
- GitHub Actions `concurrency.group`을 candidate ID와 proposal revision으로 고정하고 `cancel-in-progress: false`를 사용한다. 대기 중인 중복 run은 최신 상태를 다시 읽고 이미 `DISPATCH_PENDING` 또는 `EXECUTING`이면 종료한다.
- 상태는 `APPROVED_FOR_EXECUTION → DISPATCH_PENDING → EXECUTING` 순서로 전이하며 `execution_id`, workflow run ID, lease 만료 시각을 함께 기록한다.
- dispatcher가 상태 기록 뒤 실행 전에 중단되면 같은 `execution_id`로만 재개한다. 새 execution을 만들지 않는다.
- job 취소·timeout으로 lease가 만료되면 필수 `workflow_run` finalizer가 GitHub run conclusion을 확인해 `TIMED_OUT` 또는 `NEEDS_HUMAN`으로 정리한다. 수동 reconcile은 finalizer 자체가 실패했을 때의 복구 수단이다.
- 외부 쓰기 명령은 provider가 지원하는 idempotency key를 `execution_id`에서 파생할 수 있을 때만 재시도한다. 지원하지 않으면 자동 재시도하지 않는다.

#### 11.5.1 중단 후 재시작의 단일 dispatch 절차

GitHub의 “workflow dispatch 요청”과 “workflow run ID 기록”은 하나의 원자적 API가 아니므로, 둘 사이에서 프로세스가 멈출 수 있음을 전제로 한다. 다음 순서를 H-04 fixture와 workflow 테스트의 정본으로 사용한다.

1. dispatcher가 `candidate_id:proposal_revision`을 읽고, canonical event의 expected version을 compare-and-set으로 `DISPATCH_PENDING`에 기록한다. 이때 `execution_id`, `dispatch_nonce`, lease 만료 시각을 함께 기록한다.
2. dispatch 전에 `execution_id`를 workflow input으로 가진 기존 run을 조회한다. `QUEUED`, `IN_PROGRESS`, `COMPLETED` run이 있으면 새 dispatch를 하지 않고 해당 run을 기존 실행으로 채택한다.
3. 기존 run이 없을 때만 `workflow_dispatch`를 한 번 호출한다. 호출 성공 응답에 run ID가 없어도 실패로 간주해 즉시 재호출하지 않는다.
4. dispatcher가 중단되면 재시작된 dispatcher 또는 reconcile job이 같은 `execution_id`로 run 목록을 다시 조회해, 발견한 run ID를 canonical event에 CAS로 기록한다. 이 단계가 끝나기 전에는 새 `execution_id`나 새 dispatch를 만들지 않는다.
5. 조회 grace period가 지나도 run이 없고 lease가 만료된 경우에만 `DISPATCH_PENDING`을 `NEEDS_HUMAN`으로 넘긴다. 자동으로 같은 workflow를 다시 dispatch하지 않으며, 사람은 기존 run 부재를 확인한 뒤 재승인 또는 새 `proposal_revision`을 만든다.

따라서 `workflow_run_id` 기록이 dispatch와 동시에 되지 않아도 `execution_id`가 중복 실행 방지의 기준이 된다. H-04에는 `dispatch 직후 중단`, `run ID 기록 직전 중단`, `재시작 후 기존 run 채택`, `기존 run이 없을 때의 수동 인계` 네 경우를 반드시 넣는다.

## 12. 재시도·실패·타임아웃

| 분류 | 예시 | 자동 재시도 | 종료 또는 인계 |
| --- | --- | --- | --- |
| `TRANSIENT_EXTERNAL` | API 429·5xx, 네트워크 일시 오류 | 지수 backoff로 최대 2회. 쓰기 요청은 idempotency 보장 시만 | 소진 후 `NEEDS_HUMAN` |
| `ENVIRONMENT` | Docker·러너·Gradle daemon 충돌 | 환경 확인·정리 후 최대 1회 | 동일 실패 반복 시 `NEEDS_HUMAN` |
| `VALIDATION_FAILURE` | 테스트 실패, 리뷰 차단 | Fast는 자동 수정 없음. Standard는 같은 implementer로 최대 1회. Strict는 재승인 | 소진 후 `NEEDS_HUMAN` |
| `SCOPE_CHANGE` | 예상 밖 파일·도메인·migration | 없음 | 새 revision으로 `AWAITING_APPROVAL` |
| `POLICY_VIOLATION` | 금지 파일, 승인 없는 명령, 자동 merge 시도 | 없음 | 즉시 중단·감사 이벤트·`NEEDS_HUMAN` |
| `MISSING_EVIDENCE` | 구조화 출력 없음, 종료 코드는 성공이나 PR·파일 없음 | 동일 스텝 1회 재수집 가능 | 계속 없으면 실패. 성공으로 간주 금지 |
| `TIMEOUT` | 단계별 또는 전체 시간 초과 | read-only Discovery만 1회 재개 가능 | 쓰기 단계는 `TIMED_OUT` 후 사람 확인 |

- MVP는 현재 `agent.yml`의 job timeout 90분만 상한으로 유지한다. 단계별 timeout은 §1.1 표본만으로 정하지 않고 경로별 p95를 수집한 뒤 `p95 + 30%`로 제안하며, 팀 승인 전에는 hard timeout으로 적용하지 않는다.
- MVP는 각 step의 `started_at`·`finished_at`, 마지막 성공 checkpoint, workflow run conclusion을 기록한다. 장시간 LLM action 내부 heartbeat는 action이 이를 지원하기 전까지 요구하지 않는다.
- 재시도는 `run_id`를 새로 만들되 `parent_run_id`와 attempt를 연결한다.
- 실패·취소·타임아웃에서도 마지막 상태, 실행 링크, 변경된 파일, 열린 PR·worktree 유무를 남긴다.
- 작업 결과가 없는 성공, 구조화 출력이 없는 성공, 검증 없는 완료 선언은 실패로 판정한다.

## 13. 화이트박스 출력 계약

기계 판독 레코드와 사람용 요약은 같은 원천에서 생성한다. 다음은 최소 필드다.

```yaml
schema_version: 1
candidate_id: candidate-20260909-001
proposal_revision: 3
run_id: run-uuid
parent_run_id: null
issue:
  number: 557
  url: https://github.com/finplay-team/finplay-backend/issues/557
decision:
  state: APPROVED_FOR_EXECUTION
  path: STANDARD
  risk:
    level: HIGH
    factors:
      - concurrency
      - workflow-permissions
    strict_trigger: null
  required_gates:
    - concurrency-integration-test
    - workflow-permission-review
  rationale:
    - 근거 문서와 코드 위치
scores:
  user_impact: 4
  confidence: 5
  effort: 3
  risk: 4
  duplicate: 0
  priority: 73
routing:
  policy_version: static-v1
  mapping_version: host-approved-2026-09
  mapping_suite_version: routing-heldout-v1
  actual_tier: DEFAULT
  actual_provider_model_id: provider/model-version
  shadow_recommended_tier: ECONOMY
  shadow_confidence: 0.81
  safety_floor_tier: DEFAULT
  reason: workflow 권한 검토가 필요해 shadow 추천으로 하향하지 않음
  memory_reference_ids:
    - pattern-id-with-evidence
evidence:
  - source: ai/adr/0013-issue-triggered-agent-harness.md
    revision: git-sha
    collected_at: 2026-09-09T17:00:00+09:00
    claim: 현재 조건부 자동 승인 계약
scope:
  exact_paths:
    - .github/workflows/agent.yml
  allowed_globs:
    - ai/adr/*.md
  required_companion_files: []
  actual_files:
    - .github/workflows/agent.yml
  forbidden_globs:
    - src/main/**
commands:
  planned:
    - git diff --check
  executed:
    - command: git diff --check
      exit_code: 0
      started_at: timestamp
      finished_at: timestamp
validation:
  results:
    - gate: workflow-schema
      status: PASS
      evidence_url: actions-url-or-log-ref
  unverified:
    - area: 실제 Slack interaction
      reason: adapter 미구현
approvals:
  - action: APPROVE_EXECUTION
    actor: verified-user-id
    channel: slack
    interaction_id: redacted-id
    decided_at: timestamp
    idempotency_key: hash
changes:
  worktree: redacted-local-path-or-logical-id
  commits: []
  pull_request: null
result:
  state: READY_FOR_HUMAN_REVIEW
  summary: 사람이 확인할 한 문단
  next_action: 사람 PR 리뷰와 merge 판단
```

### 13.1 출력 불변 규칙

- scope는 `exact_paths`, `allowed_globs`, `required_companion_files`, `forbidden_globs`로 나눈다. `actual_files`가 exact·allowed 범위를 벗어나거나 required companion이 빠지면 `SCOPE_CHANGED`를 만들고, rename·generated 파일은 미리 허용된 glob 안에서만 인정한다.
- 명령은 원문, 종료 코드, 실행 시간을 기록한다. 시크릿과 개인정보 값은 명령·출력에서 마스킹한다.
- 테스트 결과는 테스트 수준, 대상, 전체 수, 실패 수, 외부 DB·API 사용 여부를 구분한다.
- Mock·단위 테스트 결과를 실제 DB·외부 API 검증으로 표시하지 않는다.
- 검증하지 못한 영역은 빈 배열로 숨기지 않고 이유와 후속 행동을 기록한다.
- 에이전트의 `완료` 문장은 근거가 아니며 diff·명령·외부 상태 조회가 연결돼야 한다.
- 사람용 요약에는 위험도, 승인 범위, 변경 파일, 검증 통과, 미검증, 다음 사람 행동을 반드시 포함한다.

## 14. 품질 게이트 선택 규칙

| 신호 | 추가 게이트 | 이유 |
| --- | --- | --- |
| `src/main/**` | tester + reviewer | production 동작과 회귀 분리 검증 |
| controller·DTO | WebMvcTest + Docs 게이트 + 필요 시 QA | API 계약 동기화와 실행 응답 확인 |
| repository·migration | DataJpaTest/Testcontainers + DB 게이트. §9.1 고정 조건이면 Strict | 실제 DB 동작과 데이터 위험 확인 |
| 인증·인가 판단 | Security 필수 + Standard 기본. §9.1 고정 조건이면 Strict | 인증 코드라는 이유만으로 모든 수정이 Strict가 되는 것을 방지 |
| 동시성·트랜잭션 | 관련 통합 테스트 + reviewer 중점 검토. §9.1 고정 조건이 없으면 Standard | 단위 테스트로 재현하기 어려움 |
| 외부 API | Fake 단위 검증 + 실제 연동 미검증 명시. §9.1 고정 조건이면 Strict | 외부 실패·비용·rate limit |
| `.github/workflows/**` | workflow 정적 검증 + 최소 권한 검토. §9.1 고정 조건이면 Strict | 일반 CI 수정과 운영 쓰기를 구분 |
| 배포·rollback 관련 | 문서·검사만 바꾸면 위험에 맞는 경로, 실제 운영 제어는 §9.1 고정 조건으로 Strict | 관련 파일명만으로 올리지 않고 실제 공개 환경 쓰기를 구분 |
| Markdown·ADR만 | 링크·경로·diff·금지 파일 검사 | 애플리케이션 build의 신호 대비 비용이 낮음 |
| `.codex/**`·`.claude/**` | Harness Consistency 게이트 | 도구별 역할 정책 편차 방지 |

## 15. Observability 지표

| 분류 | 지표 | 목적 |
| --- | --- | --- |
| 속도 | 후보→Triage, 승인 대기, 승인→PR, PR→merge 시간 | 병목이 에이전트인지 사람 대기인지 분리 |
| 안정성 | 경로별 실패율, 재시도율, timeout율, scope 변경률 | Fast가 과도하게 넓거나 Strict가 과도한지 확인 |
| 품질 | 리뷰 차단률, merge 후 회귀·rollback률, 미검증 항목 수 | 빠른 경로의 결함 유출 감시 |
| 투명성 | Evidence Record 완전성, 근거 없는 완료 선언 수 | 화이트박스 계약 준수 확인 |
| 비용 | 역할 호출 수, LLM 사용 시간·토큰, build·QA 시간 | 불필요한 에이전트·게이트 제거 |
| 라우팅 | 실제 tier와 shadow 추천 일치율, tier별 eligible dispatch당 token·비용·재작업 | 저비용 모델 하향이 품질을 해치지 않는지 확인 |
| 메모리 | 검색 호출 수, 전달 reference 수·token, 사용된 pattern의 적중·회귀율, fallback율 | 공유 메모리가 context를 줄이는지 오염·고정 비용을 만드는지 확인 |
| Discovery | LLM 회피율, 후보 precision·중복률, source별 탐지 지연 p50·p95, 사람·채널별 주간 알림 수, muted·rejected 비율 | 정기 탐지가 신호보다 비용·소음을 더 만들지 않는지 확인 |
| 제품 가치 | 우선순위 상위 후보 채택률, 사용자 영향 해소율 | 처리량이 아니라 개선 효과 확인 |

기존 `harness:local`·`harness:ci`를 baseline 구분으로 유지하고 새 run에는 `entry_type`, `path`, `risk_level`, `production_change`, 변경 파일 수 `1~2/3~7/8+`, 독립 도메인 수를 함께 기록한다. machine time은 job 실행 시간, human wait time은 승인 대기와 PR 리뷰 대기를 따로 계산한다. 실패율의 분모는 `EXECUTING`에 진입한 run, Evidence 완전성의 분모는 종료 상태에 도달한 run이다. merge 후 회귀는 연결 Issue의 reopen·회귀 Issue·rollback을 14일과 30일 창으로 추적한다.

Discovery 지표의 분모를 다음처럼 고정한다.

- LLM 회피율 = LLM job이 실행되지 않은 schedule / `SUCCESS` schedule.
- 후보 precision = `승인` 또는 `이슈만 등록`된 후보 / 사람이 결정한 후보. `보류`는 분모에 포함하고 `미응답`은 별도 표시한다.
- 중복률 = 기존 Candidate·Issue·PR에 연결된 후보 / 전체 후보. 중복 Issue 생성은 별도로 0건을 요구한다.
- false negative는 후보 기록만으로 알 수 없으므로 알려진 깨진 link·anchor·advisory fixture의 sentinel recall과 월 1회 사람의 source 표본 감사로 측정한다.
- 탐지 지연은 source의 관측 가능한 게시·실패 시각부터 첫 Candidate receipt까지 계산한다. 일일 source p95 36시간 이내, 주간 source p95 8일 이내를 초기 상한으로 제안한다.

첫 4주 또는 비교 가능한 cohort당 20건이 모일 때까지 pilot으로 운영하고 임계값을 자동 조정하지 않는다. 둘 중 늦은 시점을 평가일로 삼는다.

| 개선 주장 | 통과 기준 | 실패 시 처리 |
| --- | --- | --- |
| Fast가 빨라짐 | Actions 문서·비동작 cohort의 machine time p50이 baseline보다 30% 이상 감소하고 human wait 단계가 늘지 않음 | Fast 단계 축소 또는 기존 경량 경로 유지 |
| Standard가 무거워지지 않음 | 같은 task-size cohort에서 LLM 호출 수와 광역 build 횟수가 baseline 이하이고 machine time p50이 악화되지 않음 | planner·tester 조건 재축소, 중복 스텝 제거 전 확대 중단 |
| 안정성이 개선됨 | 중복 execution 0건, 승인 없는 실행 0건, fault-injection의 stale·timeout·dispatch crash 시 fail-closed | 채널·Discovery 확대 중단, dispatcher부터 수정 |
| 품질이 유지됨 | 표본 20건 이상에서 30일 회귀율이 baseline 대비 2%p 넘게 악화되지 않음 | 해당 경로의 reviewer·검증 게이트 복구 |
| 투명성이 개선됨 | 종료 run의 필수 Evidence 필드 충족률 100%, self-report-only 완료 0건 | 완료 상태를 차단하고 aggregation 수정 |
| 정기 Discovery가 상환됨 | no-change fixture의 LLM API receipt 0건, sentinel recall 100%, 중복 Issue 0건, 신규 일반 후보 알림 주 5개 이하, 사람 판정 20건 뒤 후보 precision 40% 이상 | 해당 source·scheduler 확대 중단. 실패 source와 수동 표본을 분석한 뒤 계약 수정 |

속도·안정성·투명성 중 하나라도 통과하지 못하면 전체 도입 성공으로 선언하지 않는다. Fast 범위와 외부 채널은 검증을 통과한 cohort만 확대한다.

## 16. MVP 범위

전체 상태 하네스를 한 번에 만들지 않는다. 먼저 속도·안전 가설만 확인하는 Pilot 0를 통과한 뒤 상태 관리 MVP로 간다.

### 16.1 Pilot 0: 현재 workflow의 수직 축소

1. ADR 채택 직후 현재 조건부 자동 승인 스텝을 제거한다.
2. 기존 `verify-actor`와 사람 요청을 그대로 사용하고 범용 승인 원장·lease는 아직 만들지 않는다.
3. Markdown·ADR allowlist의 Fast 분기에서 Gradle build와 LLM reviewer를 생략하고 링크·범위·금지 파일·최소 Evidence만 검사한다.
4. Standard 구현 prompt 안의 전체 build를 제거하고 runner Quality Gate가 최종 build를 한 번만 소유한다. planner·tester는 아직 새로 추가하지 않는다.
5. Fast·Standard 각각 비교 가능한 10건 또는 4주 중 늦은 시점에 방향성 go/no-go를 판단한다. Fast machine time이 줄지 않거나 Standard의 build 횟수·시간이 악화되면 상태 하네스 확대를 중단한다. §15의 전체 개선 선언은 cohort당 20건 기준을 그대로 쓴다.

### 16.2 MVP 1: 상태와 증거의 최소 계약

Pilot 0가 통과했을 때만 다음을 구현한다.

1. Candidate·Decision·Evidence를 각각 다른 서비스나 schema로 나누지 않고 하나의 event envelope와 상태별 필수 필드로 정의한다.
2. 사람이 지정한 GitHub Issue 하나를 Triage-lite 입력으로 받고, GitHub workflow 하나가 append-only 상태 이벤트, 승인 확인, 중복 실행 방지, dispatch를 직렬 처리한다.
3. 새 장 workflow를 기존 `agent.yml` 앞에 겹치지 않고 같은 workflow 안에 Fast·Standard·Strict의 얇은 route gate를 추가한다.
4. workflow 마지막 aggregation step이 실제 diff·명령·검증·미검증을 event envelope에 기록한다. 별도 Evidence Recorder 서비스나 LLM 역할은 만들지 않는다.
5. timeout·취소는 필수 `workflow_run` finalizer가 run conclusion과 lease를 대조해 사람 인계 상태로 정리한다. 수동 reconcile은 finalizer 장애 복구에만 쓴다.

### 16.3 Pilot 2: Ruflo-lite shadow intelligence

MVP 1의 Evidence가 안정적으로 쌓이고 최근 8주에 비교 가능한 Standard run 30건 이상과 같은 task type의 반복 사례 10건 이상이 있을 때만 실행한다. 이 최소량이 없으면 routing·memory가 상환할 고정 비용보다 작다고 보고 시작하지 않는다.

1. Task Coordinator가 기존 4역할 중 필요한 역할과 병렬 가능 작업을 추천한다. 최초에는 현재 실행 순서를 바꾸지 않고 shadow 결과만 기록한다.
2. 검증된 run에서 최대 3개의 pattern reference만 조회하는 repository-scoped memory를 만든다.
3. model tier router는 `ECONOMY`·`DEFAULT`·`DEEP` 추천만 기록하고 실제 모델을 바꾸지 않는다.
4. shadow 후보는 동일 frozen task packet의 baseline·후보 tier paired replay를 통과해야 한다. §10.6의 cohort·절감·품질 기준을 통과한 tier mapping만 사람 검토용 정책 PR 후보가 된다.
5. Ruflo 패키지 전체, AgentDB·SONA·swarm runtime은 도입하지 않는다. 별도 throwaway 비교 실행은 production 저장소·시크릿·실제 외부 쓰기를 사용하지 않고, 재현 명령과 결과 보고서만 repository 문서 PR에 남긴다.

MVP에서 제외한다.

- 자동 Discovery와 5축 점수에 따른 무인 후보 생성. 점수 계약은 유지하되 pilot 뒤에 구현한다.
- Slack·Discord adapter 구현. 이 ADR에서는 공통 메시지·상태 계약만 확정한다.
- 일일·주간 scheduler, 외부 정보원 adapter, 사용자 지표 연동. §7.1의 계약만 확정하고 GitHub 기반 수동 Discovery의 정확도·비용을 먼저 확인한다.
- 별도 Approval Gateway·Evidence Recorder 서비스, 전용 외부 DB, 웹 대시보드.
- 별도 Security·Docs LLM 에이전트. MVP에서는 기존 reviewer에 결정적 체크리스트를 합성하고 전문 게이트가 없으면 `NEEDS_HUMAN`으로 둔다.
- 주간 지표 자동화, 자동 스케줄러, ML 기반 점수 조정.
- 매우 높은 위험 작업의 자동 구현.
- PR 리뷰 코멘트에서 자동 재구현을 시작하는 루프.
- 자동 merge와 하네스가 직접 호출하는 배포.

## 17. 후속 Issue 후보

아래 항목은 아직 GitHub Issue로 생성하지 않는다. 각 항목은 한 Issue·한 PR 크기를 목표로 한다.

| ID | 우선순위 | 후보 | 선행 | 수정 예상 파일 | 완료 기준 |
| --- | --- | --- | --- | --- | --- |
| H-01 | P0 | ADR-0032 팀 결정 반영과 기존 ADR 대체 관계 확정 | 없음 | `ai/adr/0032-*.md`, 필요 시 기존 ADR의 상태 포인터만 | 미결정 항목이 팀 결정으로 채워지고 자동 승인 정책의 정본이 하나임 |
| H-07 | P0·첫 구현 | 기존 조건부 자동 승인 제거 | H-01 | `.github/workflows/agent.yml`, 관련 하네스 문서 | `gh pr review --approve`가 없고 사람 merge만 가능. 완료 전 다른 구현 Issue 착수 금지 |
| H-13 | P0·Pilot 0 | 문서 allowlist Fast 분기 | H-07 | `.github/workflows/agent.yml`, 신규 최소 검사 fixture | Markdown·ADR 변경에서 Gradle·LLM reviewer가 생략되고 링크·범위·금지 파일 Evidence가 남음 |
| H-14 | P0·Pilot 0 | Standard build 단일 소유권 | H-13 | `.github/workflows/agent.yml`, 구현 prompt 관련 fixture | 구현 prompt의 전체 build가 제거되고 runner build만 1회 실행됨 |
| H-02 | P1·MVP 1 | 단일 event envelope JSON Schema·정책·분류 fixture | H-14 pilot 통과 | 신규 `.github/harness/policy.yml`, `.github/harness/schemas/event.schema.json`, fixture·검사 | append-only 이벤트와 상태별 필수 필드, Strict 고정 조건을 검증하고, 2·4점 경계를 포함한 대표 분류 fixture 10개 이상과 유효·무효 payload가 결정적으로 통과함 |
| H-04 | P1·MVP 1 | GitHub 단일 dispatcher와 승인·멱등 상태 전이 | H-02 | 신규 `.github/scripts/adaptive-harness/dispatch.*`, `.github/workflows/agent.yml`, 테스트 | bot 이벤트 version·digest, actor 재검증, stale 거절, revision당 execution 하나, dispatch crash·lease reconcile 검증 |
| H-05 | P1·MVP 1 | 기존 `agent.yml` 내부의 조건부 역할 라우터 | H-04 | `.github/workflows/agent.yml`, 관련 fixture | 중첩 workflow 없이 planner·tester·전문 게이트 호출 조건과 예산이 §10과 일치 |
| H-06 | P1·MVP 1 | Quality Gate 결과 집계와 필수 timeout finalizer | H-02, H-05 | 신규 `.github/scripts/adaptive-harness/evidence.*`, 신규 최소 `workflow_run` finalizer, 테스트 | 실제 diff·명령·검증·미검증이 event 하나로 연결되고 취소 run이 자동 finalizer로 정리됨 |
| H-11 | P1 | baseline·cohort 지표 수집과 주간 보고 | H-06 | 신규 `.github/scripts/adaptive-harness/metrics.*`, 저장·보고 workflow | §15의 p50·p95·회귀 창·호출 수·Evidence 완전성을 경로별 집계 |
| H-09 | P1 | 관측 결과로 필요한 Security·Docs 조건부 게이트 | H-06, H-11 | 신규 정책·검사 스크립트. agent 정의 변경은 별도 합의 후 | 기존 reviewer로 부족한 신호에서만 추가되고 호출 증가 근거가 있음 |
| H-22a | P1 | 상대 link·anchor·문서 구문 결정적 검사 | H-06의 pilot 통과 | 신규 문서 링크 검사와 valid·broken fixture | LLM 없이 link·anchor·구문 오류를 검출하고 자동 수정하지 않음 |
| H-23a | P1 | scan receipt·checkpoint·digest와 no-change 비용 차단기 | H-11, H-22a pilot 통과 | 신규 scan schema·normalization·artifact fixture | source별 결과, `SUCCESS/PARTIAL/FAILED`, `NO_ACTIONABLE_DELTA/DEFERRED_QUOTA`, digest와 LLM guard가 검증되고 no-change에서 LLM action step skipped·API receipt 0건 |
| H-03 | P1 | receipt 기반 의미 판정·점수화 | H-23a | 신규 `.github/scripts/adaptive-harness/discover.*`, `triage.*`, 대응 테스트 | `processable_delta_count > 0`인 receipt만 받아 중복·점수·위험·예상 경로를 출력하고 repository를 쓰지 않음 |
| H-22b | P2 | 정본 graph·고립 문서 결정적 검사 | H-22a | 신규 graph·router/index fixture | 본문을 LLM에 넣지 않고 정본 진입점에서 도달할 수 없는 문서와 복수 정본 후보를 출력 |
| H-22c | P2 | stale·중복·과도한 길이 shadow 판정 | H-22b, H-03, H-11 | 신규 metadata·heuristic·LLM guard fixture | 길이만으로 실패시키지 않고 추가 근거가 있는 Candidate만 만들며 호출량을 측정 |
| H-23b | P2 | 일일·주간 scheduler와 quota | H-23a, Q14 결정 | 신규 schedule workflow·quota fixture | 매일 결정적 검사와 주간 묶음 분석이 같은 checkpoint로 중복 없이 실행되고 후보 상한을 지킴 |
| H-23c | P2 | 긴급 event dedupe·cooldown·실패 알림 | H-23b | 신규 urgent policy·fingerprint·fault fixture | 같은 incident·revision은 한 번만 알리고 source 3회 연속 실패 뒤 운영 알림, 상태 변화 없는 이월 후보 재알림 0건 |
| H-24a | P2 | 외부 source 계약·allowlist·격리 fetch validator | H-23a, Q15 결정 | 신규 source schema·allowlist·fetch fixture | host·HTTPS·redirect·type·크기·rate limit을 검증하고 injection·redirect 탈출·oversized·script payload를 data로 격리 |
| H-24b | P2 | GitHub release·security advisory 첫 adapter | H-24a, H-23b | 신규 GitHub source adapter·fixture | stable ID·URL·redirect chain·hash·시각을 남기고 변경분만 Candidate 입력으로 전달. 다른 서비스 공지는 source별 후속 Issue로 분리 |
| H-27 | P2·정책 선행 | 사용자 집계·재식별 방지 정책 | Q16 결정 | 신규 개인정보·분석 정책 ADR 또는 spec | 최소 cohort·억제·허용 차원·민감 속성·동의 근거·보존·접근·삭제가 팀 결정으로 확정 |
| H-25a | P2 | 익명·집계 사용자 event schema·validator | H-23a, H-27 | 신규 aggregate event schema·threshold fixture | 원천 시스템에서 집계된 event만 받고 임계값 미달·민감 차원·원문·식별자 입력을 거절 |
| H-25b | P2 | 첫 승인된 사용자 지표 source connector | H-25a, H-23b | source별 connector·redaction·receipt fixture | 승인된 단일 지표 source만 연결하고 다른 source는 한 Issue씩 추가 |
| H-08 | P2 | 첫 외부 승인 채널 어댑터 | H-04, MVP pilot 통과 | 신규 channel adapter 파일, 채널 설정 문서 | 승인·이슈만·보류·거절이 canonical 상태로 멱등 반영 |
| H-10 | P2 | 두 번째 승인 채널 어댑터 | H-08 | H-08과 겹치지 않는 channel adapter 파일 | 첫 채널과 같은 action·멱등 계약을 사용 |
| H-26 | P2 | Discord 개선 후보 digest·상한·cooldown 표시 | H-08, H-23b, H-23c, Q17 결정 | Discord digest formatter·표시 fixture | 신규 후보 최대 5개와 근거·예상 파일·검증·미검증을 표시하고 H-08의 canonical action을 재사용하며 상태 변화 없는 재알림 0건 |
| H-12 | P2 | Codex·Claude 역할 계약 편차 검사 | H-02, 주석 정책 후속 변경과 파일 충돌 없음 확인 | 신규 검사 스크립트·fixture. agent 정의 변경은 별도 PR | 역할별 금지 작업·출력 필드의 의미 편차를 CI에서 탐지 |
| H-15 | P2·Pilot 2 | Task Coordinator의 task DAG·역할 추천 shadow 출력 | H-06, H-11 | 신규 coordinator 정책·schema·fixture. 기존 agent 정의는 읽기 전용 | 기존 실행을 바꾸지 않고 dependency·병렬 그룹·역할 추천과 예산 초과가 결정적으로 출력됨 |
| H-16 | P2·Pilot 2 | Evidence qualification·redaction·pattern 저장 계약 | H-06, H-15, Q12·Q13 결정 | 신규 memory schema·qualification·DLP fixture | 비신뢰 입력 표식, provenance digest, 삭제 tombstone, 완전한 Evidence의 provisional·verified 승격이 검증됨 |
| H-20 | P2·Pilot 2 | pattern 검색·top 3 ranking·stale invalidation | H-16 | 신규 검색·invalidation 스크립트·fixture | path·policy·schema·명령 digest·TTL 변화 시 stale pattern을 배제하고 실패 시 정적 정책으로 fallback |
| H-17 | P2·Pilot 2 | capability tier shadow model router | H-11, H-15, H-20 | 신규 router policy·결정 log·평가 fixture | 실제 모델은 유지하면서 mapping version, concrete model, actual·recommended tier와 결과가 run ID로 연결됨 |
| H-19 | P2·실험 | baseline·후보 tier paired replay와 Ruflo 격리 비교 | H-11, H-17 | 신규 고정 task packet·재현 스크립트·repo 결과 보고서. throwaway runtime은 PR에 포함하지 않음 | 동일 입력·도구·예산에서 token·시간·품질·운영 복잡도를 비교하고 production 의존성을 남기지 않음 |
| H-18 | P2 | frozen offline evaluator와 정책 후보 생성 | H-19, cohort별 30쌍 | 신규 evaluator·bootstrap·receipt fixture·정책 후보 문서 | intention-to-treat token 20% 절감, 비용 비악화, 품질 CI 상한 +2%p, receipt 100%를 모두 통과한 후보만 사람 PR로 제안됨 |
| H-21 | P2 | 승인된 routing 정책의 10건 canary와 자동 rollback | H-18 정책 PR의 사람 merge | 신규 canary·rollback 상태와 fault fixture | 실제 첫 10건에서 품질·재작업이 악화되면 이전 mapping으로 복귀하고 자동 재승격하지 않음 |

Pilot 0 순서는 `H-01 → H-07 → H-13 → H-14`로 고정한다. 지표가 악화되면 여기서 멈추고 기존 경량 경로를 유지한다. 통과한 뒤 MVP 1은 `H-02 → H-04 → H-05 → H-06` 순서로 진행한다. 구현 언어와 정확한 경로는 H-01에서 확정한다. 현재 GitHub Actions가 shell·YAML 중심이므로 별도 서버보다 저장소 내 작은 스크립트와 단일 JSON Schema를 추천한다.

## 18. 파일 소유권과 병행 작업 충돌 방지

| 파일 영역 | 이슈 #557 소유권 | 후속 하네스 소유권 | 주석 정책과의 경계 |
| --- | --- | --- | --- |
| `ai/adr/0032-*.md` | 이번 이슈 단독 작성 | H-01만 후속 수정 | 주석 정책과 무관 |
| `AGENTS.md`, `CLAUDE.md` | 수정 금지 | ADR 채택 뒤 전용 동기화 Issue에서만 | 주석 정책 정본 반영이 끝난 기준으로 별도 작업 |
| `docs/conventions/code.md` | 수정 금지 | 하네스가 소유하지 않음 | 주석 정책 작업 전용 |
| `.codex/agents/**`, `.claude/agents/**` | 수정 금지 | H-09·H-12에서도 기본 읽기 전용. 변경은 별도 Issue | 역할 주석 규칙과 섞지 않음 |
| `.agents/skills/**`, `.claude/skills/**` | 수정 금지 | H-05가 필요한 래퍼만 파일별 단일 작성자 배정 | 기존 feature 내용의 주석 규칙 정리 금지 |
| `.github/workflows/agent.yml` | 수정 금지 | H-07 → H-13 → H-14 → H-04 → H-05 순서로 한 Issue당 한 목적만 수정. 신규 장 workflow와 중첩 금지 | 주석 정리와 같은 PR에 섞지 않음 |
| 신규 `.github/harness/**`·`.github/scripts/adaptive-harness/**` | 생성하지 않음 | H-02~H-06·H-09·H-11·H-15~H-21이 파일 단위 분리 소유 | 기존 Java·Kotlin 주석과 무관 |
| `src/**` | 수정 금지 | 하네스 MVP에서도 원칙적으로 비소유 | production·주석 정책과 완전 분리 |

현재 `dev`에는 `docs/555-comment-policy`의 결과가 merge돼 있지만, 이 ADR은 그 결과를 수정·정리하거나 역할 파일의 주석 규칙 편차를 바로 고치지 않는다. 발견된 편차는 H-12의 입력으로만 남긴다.

## 19. 결정 시트

`추천 결정`은 사용자가 선택 완료했다고 표시한 항목만 채택 후보로 고정된다. 나머지 행은 §20의 선택 전까지 `미결정 추천안`이며 실제 운영 정책이 아니다.

| 구분 | 현재 상태 | 추천 결정 | 이유 | 코드 수정 필요 | 후속 Issue |
| --- | --- | --- | --- | --- | --- |
| 역할 수 | 4역할 + 메인 오케스트레이터 | 4역할 유지, Security·Docs는 조건부 게이트 | 에이전트 증가 없이 공백 보완 | 예 | H-05, H-09 |
| 자동 승인 | ADR-0013·0016에서 조건부 허용 | 모든 자동 승인 금지 | 자기 구현·자기 리뷰의 신뢰 확대 방지 | 예 | H-07 |
| merge | 사람 전용 | 유지 | `dev` merge가 배포를 시작하므로 최종 통제 필요 | 아니오 | 없음 |
| approval 정본 | 없음 | GitHub 기반 원장을 MVP 정본으로 사용 | 기존 인증·Issue·Actions와 연결되고 별도 인프라가 없음 | 예 | H-04 |
| 외부 채널 | Slack·Discord 미구현 | MVP pilot 뒤 팀 주사용 채널 하나부터 연결 | 핵심 상태 전이 검증 전에 adapter 복잡도를 추가하지 않음 | 예 | H-08, H-10 |
| Strict 기준 | 고정 경로 없음 | §9.1 고정 조건에서만 Strict | 사용자 선택 B. 위험 개수·합계로 Strict를 확대하지 않음 | 예 | H-02, H-05 |
| 매우 높은 위험 | 별도 적응형 규칙 없음 | 고정 조건이면 계획·Issue까지만 자동, 구현 기본 수동 | 파괴적·권한성 작업의 비가역성 | 예 | H-05 |
| Fast reviewer | 현재 경량 문서 작업은 메인 세션 직접 처리 가능 | 문서·비동작 파일은 결정적 게이트로 대체 가능 | 작은 작업의 불필요한 세션 비용 제거 | 예 | H-13, H-06 |
| 재시도 | Actions 자동 수정 1회 | Standard 코드 수정 1회, Strict는 재승인 | 속도와 범위 확장 위험의 균형 | 예 | H-05 |
| workflow 구조 | 현재 단일 `agent.yml` | 같은 workflow의 얇은 route gate. 별도 장 workflow·서비스 없음 | 기존 단계 중첩과 이중 build 방지 | 예 | H-04~H-06 |
| 점수 임계값 | 없음 | 자동 Discovery를 시작한 뒤 첫 pilot에서 고정값 관찰 | MVP 반응형 경로에는 점수화 비용을 부과하지 않음 | 예 | H-03, H-11 |
| Discovery 주기 | 없음 | MVP는 수동 실행, 지표 확인 후 스케줄러 별도 결정 | 노이즈·비용을 먼저 측정 | 예 | H-03, H-23a~H-23c |
| 정기 점검 구조 | 없음 | 매일 1회 결정적 검사 + 주 1회 변경분 묶음 분석 + 긴급 event | 변화가 없을 때 LLM 비용 0, 신호 지연과 상시 비용의 균형 | 예 | H-22a~H-23c |
| 외부 정보원 | 없음 | 승인된 공식 release·security·서비스 공지 allowlist만 | 범용 crawl의 노이즈·보안·약관 위험을 피함 | 예 | H-24a, H-24b |
| 사용자 신호 | 없음 | 별도 승인된 익명·집계 지표만 read-only로 수집 | 하네스가 실험을 임의 시작하거나 개인정보를 학습하는 것을 방지 | 예 | H-27, H-25a, H-25b |
| Discord 후보 수 | 없음 | 주간 digest당 최대 5개, 나머지는 이월 | 승인 피로와 알림 소음을 제한 | 예 | H-26 |
| Ruflo 적용 | 외부 swarm·memory runtime 없음 | 전체 패키지 대신 Coordinator·제한 memory·shadow routing 패턴만 자체 계약으로 구현 | 현재 Kotlin·Actions 하네스에 대규모 Node runtime을 얹지 않고 검증된 이점만 취함 | 예 | H-15~H-21 |
| 모델 자동 선택 | 실행 환경의 현재 모델 사용 | 최소 30쌍의 paired replay 뒤 사람 승인된 capability tier mapping만 적용 | Ruflo에서도 tier/model 불일치 버그가 있었으므로 shadow 추천만으로 자동 하향 금지 | 예 | H-17~H-21 |
| 하네스 학습 | 없음 | Evidence로 검증된 역할·tier·검색 정책만 후보화, 자동 적용 금지 | AI 자기 평가와 production 정책 오염 방지 | 예 | H-16, H-18, H-21 |

## 20. 남은 결정사항과 팀 확인 질문

### Q1. MVP의 첫 승인 화면은 어디에 둘 것인가

- 선택지 A. GitHub Issue 댓글·label만 먼저 사용한다.
- 선택지 B. Slack을 첫 adapter로 사용한다.
- 선택지 C. Discord를 첫 adapter로 사용한다.
- 추천: A로 canonical 상태와 멱등성을 먼저 검증하고, 팀의 실제 주사용 채널을 B 또는 C로 하나만 추가한다. GitHub를 건너뛰고 두 외부 채널을 동시에 만들면 상태 불일치와 인증 문제를 한 번에 떠안는다.

### Q2. 승인 원장의 저장소를 어디에 둘 것인가

- 선택지 A. GitHub Issue·bot comment·label을 canonical 원장으로 쓴다.
- 선택지 B. 전용 외부 DB를 둔다.
- 선택지 C. 저장소 파일에 상태를 commit한다.
- 추천: MVP는 A다. B는 동시성과 조회가 가장 좋지만 운영 인프라·시크릿·migration이 추가된다. C는 승인마다 PR·commit 노이즈가 생기므로 추천하지 않는다.

### Q3. 매우 높은 위험 작업에서 AI 구현을 어디까지 허용할 것인가

- 선택지 A. Issue·계획·검증 체크리스트까지만 자동화하고 production 구현은 사람이 한다.
- 선택지 B. 별도 실행 승인 후 격리 worktree의 초안 구현까지 허용한다.
- 추천: A다. 결제·삭제·권한 확대·파괴적 migration은 초안이라도 잘못 실행되면 영향이 크며, 현재 하네스에는 명령 sandbox와 승인 원장이 없다.

### Q4. Fast에서 독립 reviewer 호출을 생략할 수 있는 범위는 어디까지인가

- 선택지 A. Markdown·ADR·링크 수정만 생략한다.
- 선택지 B. production 미사용 테스트 fixture와 비동작 설정까지 포함한다.
- 선택지 C. 모든 낮음 위험 변경에서 생략한다.
- 추천: 초기에는 A다. 4주 지표에서 결함 유출이 없고 결정적 게이트가 충분하다고 확인한 뒤 B를 검토한다. C는 낮음 판정 오류가 바로 독립 검증 누락으로 이어진다.

### Q5. Standard의 자동 수정 1회를 유지할 것인가

- 선택지 A. 차단 사항만 같은 implementer가 1회 수정하고 독립 재검증한다.
- 선택지 B. 자동 수정 없이 항상 사람에게 돌린다.
- 추천: A다. ADR-0016의 상한·독립 재리뷰 원칙을 재사용할 수 있다. 단, 승인 scope 안의 파일과 명령만 허용하고 scope 변화가 있으면 재승인한다.

### Q6. 기존 조건부 자동 승인을 언제 제거할 것인가

- 선택지 A. ADR 채택 직후 H-07을 P0로 먼저 실행한다.
- 선택지 B. 적응형 라우터가 완성될 때까지 유지한다.
- 추천: A다. 이 ADR의 핵심 원칙과 현재 `gh pr review --approve`가 직접 충돌하므로 과도기를 길게 두지 않는다. 제거 후에도 사람 리뷰·merge와 기존 CI는 유지된다.

### Q7. Discovery의 최초 입력원을 무엇으로 제한할 것인가

- 선택지 A. GitHub Issue·PR·Actions 실패만 사용한다.
- 선택지 B. A + 애플리케이션 로그·성능 지표를 사용한다.
- 선택지 C. B + 사용자 피드백 채널까지 사용한다.
- 추천: MVP는 A다. 개인정보·운영 접근권한·노이즈 정책을 먼저 결정하지 않고 B·C를 수집하면 근거 저장 자체가 보안 범위를 넓힌다.

### Q8. Strict 진입 기준을 어떻게 고정할 것인가. 사용자 선택 완료

- 선택지 A. 비가역·권한·배포 같은 고정 조건 또는 `누적 점수 8 이상 + 높은 차원 3개 이상`일 때만 Strict로 간다.
- 선택지 B. 누적 점수를 없애고 고정 조건일 때만 Strict로 간다.
- 선택지 C. Strict 경로를 없애고 Standard에 필요한 전문 게이트와 승인만 추가한다.
- 결정: B. 여러 높은 위험이 겹쳐도 되돌릴 수 있고 검증 가능하면 Standard에 전문 게이트를 합성하거나 Issue를 분할한다. Strict는 §9.1의 고정 조건에서만 사용한다. 실제 4주 데이터에서 Strict 비율과 Standard의 게이트 수·처리 시간을 함께 보고, Standard가 사실상 Strict처럼 무거워지는지도 확인한다.

### Q9. Ruflo 구조를 어떤 방식으로 참고할 것인가. 사용자 선택 완료

- 선택지 A. Ruflo 전체 패키지와 swarm runtime을 도입한다.
- 선택지 B. Coordinator·제한 memory·검증된 정책 학습 패턴만 FinPlay 하네스로 구현한다.
- 선택지 C. Ruflo를 격리 환경에서 기존 하네스와 비교한다.
- 결정: B를 기본 설계로 사용하고, 모델 자동 선택은 C의 shadow 비교를 거친다. 외부 패키지는 production 의존성으로 추가하지 않는다.

### Q10. Pilot 2의 memory 정본을 어디에 둘 것인가

- 선택지 A. GitHub Issue 상태 이벤트와 Evidence를 정본으로 두고 실행 때 필요한 범위만 임시 인덱싱한다.
- 선택지 B. 저장소에 pattern JSONL을 commit한다.
- 선택지 C. AgentDB·외부 vector DB를 운영한다.
- 추천: A다. 초기 수십 건에는 외부 DB와 embedding 비용이 더 크며, B는 학습할 때마다 commit 노이즈를 만든다. 검색량과 정확도 요구가 GitHub 기반 scan의 한계를 넘는다는 측정이 생긴 뒤 C를 검토한다.

### Q11. capability tier를 실제 모델에 누가 매핑할 것인가

- 선택지 A. 실행 host의 승인된 모델 목록과 비용표에서 runtime이 해석하고 Evidence에 실제 모델을 기록한다.
- 선택지 B. 저장소 정책에 특정 공급자·모델명을 고정한다.
- 선택지 C. router가 사용 가능한 외부 모델을 임의 탐색한다.
- 추천: A다. 저장소는 `ECONOMY`·`DEFAULT`·`DEEP`의 최소 capability만 정하고, 실제 모델 변경은 운영자 통제와 비용·가용성 변화를 따른다. C는 공급망·비용·데이터 경계를 임의로 넓힌다.

### Q12. 학습 reward의 사람 판정을 언제 확정할 것인가

- 선택지 A. Quality Gate 통과 즉시 성공으로 기록한다.
- 선택지 B. PR merge를 성공으로 기록한다.
- 선택지 C. Quality Gate·사람 merge 후 14일 회귀 창을 통과해야 Verified Pattern 성공으로 승격한다.
- 추천: C다. A는 자기 검증 편향이 있고 B는 merge 직후 발견되는 회귀를 학습하지 못한다. 14일 전에는 `provisional`, 30일 회귀 결과는 confidence 보정에 사용한다.

### Q13. Verified Pattern의 기본 TTL을 얼마로 둘 것인가

- 선택지 A. 90일 뒤 자동 만료하고 새 검증 없이는 사용하지 않는다.
- 선택지 B. 180일 뒤 자동 만료한다.
- 선택지 C. 기간 없이 관련 파일·정책 변경 때만 무효화한다.
- 추천: A다. 초기에는 잘못 일반화된 pattern의 장기 오염 비용이 재검증 비용보다 크다. 실제 적중률과 재검증 빈도를 본 뒤 안정적인 task type만 180일로 늘린다.

### Q14. 정기 Discovery를 어떤 주기로 시작할 것인가

- 선택지 A. 매일 1회 결정적 검사, 주 1회 변경분 묶음 분석, 긴급 event만 즉시 확인한다.
- 선택지 B. 하루 2회 저장소·외부·사용자 신호를 모두 LLM으로 분석한다.
- 선택지 C. 주 1회만 모든 검사를 실행한다.
- 추천: A다. 변화가 없으면 LLM을 호출하지 않으면서도 깨진 문서와 CI 신호는 하루 안에 찾을 수 있다. 두 번째 일일 검사는 pilot에서 실제 탐지 지연이 확인된 입력에만 추가한다.

### Q15. 최초 외부 정보원 범위를 어디까지 허용할 것인가

- 선택지 A. 사용하는 프레임워크·GitHub·외부 서비스의 공식 문서, release, security advisory allowlist만 사용한다.
- 선택지 B. 검색으로 발견되는 기술 블로그·커뮤니티·SNS까지 범용 crawl한다.
- 선택지 C. 외부 정보는 수집하지 않는다.
- 추천: A다. 출처와 변경 digest를 안정적으로 남길 수 있고, 범용 crawl의 노이즈·prompt injection·약관 위험을 초기 범위에 들이지 않는다. 커뮤니티 정보는 사람이 URL을 제시한 경우의 일회성 조사로 남긴다.

### Q16. 사용자 증가에 따른 개선 신호를 어떻게 받을 것인가

- 선택지 A. 별도 승인된 실험과 운영 지표의 익명·집계 결과만 read-only 입력으로 사용한다.
- 선택지 B. Discord·문의·로그의 사용자 원문을 모두 memory에 저장한다.
- 선택지 C. 사용자 신호는 하네스에 연결하지 않는다.
- 추천: A다. 반복 불편과 이탈 변화를 볼 수 있으면서도 개인정보와 원문 prompt injection을 memory에 넣지 않는다. 최소 표본·동의·보존 기간은 H-27에서 먼저 확정한다.

### Q17. Discord가 한 번에 보여줄 개선 후보 수를 얼마로 제한할 것인가

- 선택지 A. digest당 최대 5개를 보여주고 나머지는 다음 회차로 이월한다.
- 선택지 B. 발견된 후보를 모두 즉시 알린다.
- 선택지 C. 가장 높은 점수 1개만 보여준다.
- 추천: A다. 선택 피로를 제한하면서도 서로 다른 종류의 개선 후보를 비교할 수 있다. 긴급 보안 후보는 이 상한과 별도의 즉시 알림으로 처리한다.

## 21. 수용 기준 대조

| Issue #557 수용 기준 | 설계 위치 | 판정 |
| --- | --- | --- |
| 역할별 입력·출력·쓰기 권한·금지 작업 | §6 | 충족 설계 |
| 낮음·보통·높음·매우 높음별 단계와 승인 | §9~§10 | 충족 설계 |
| 사용자 영향·신뢰도·작업량·위험도·중복 점수 | §8 | 충족 설계 |
| Slack·Discord 상태 전이와 중복 승인 방지 | §11 | 충족 설계 |
| 주석 정리와 파일 범위 비충돌 | §18 | 충족 설계 |
| 후속 Issue 후보의 의존성·우선순위 | §17 | 충족 설계 |
| 최종 merge·배포 사람 통제 | §3, §9~§10 | 충족 설계 |
| 거절·보류·실패·재시도 경로 | §11~§12 | 충족 설계 |
| 화이트박스 출력 형식 | §13 | 충족 설계 |

## 22. 예상 결과와 한계

이 ADR이 채택되고 후속 Issue가 구현되면 작은 문서 작업은 Fast로 짧게 처리하고, API·DB·보안·배포에 가까워질수록 Standard·Strict의 승인과 검증을 추가할 수 있다. 같은 4역할을 유지하면서 Discovery·승인 원장·증거 기록의 공백을 별도 에이전트가 아닌 단계와 계약으로 채운다. Pilot 2는 Ruflo의 Coordinator·memory·학습형 routing 원리를 참고하지만 외부 swarm runtime을 도입하지 않고, shadow 근거가 통과한 정책만 사람에게 제안한다.

다만 이 문서만으로 실제 자동화가 생기지는 않는다. 점수 가중치, timeout, 첫 채널, GitHub 기반 원장의 동시성 한계는 실제 MVP 실행 데이터로 검증해야 한다. 팀이 §20의 선택을 확정하기 전에는 추천값을 운영 정책으로 간주하지 않는다.
