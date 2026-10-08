# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트

**우리동네체온계 (sneezecast)**: 시민의 주간 건강 보고를 모아 행정동 단위 증상 변화를 보여 주고, 충분한 자료가 있을 때에만 예방 정보를 전하는 참여형 감염병 정보 모바일 웹(PWA). 2026 AI·디지털 기반 사회문제 해결 챌린지 출품작(팀 팔로팔로미)이다.

## 현재 상태

도메인 기능 구현 초기 단계다. 백엔드는 모듈 골격(core · cloud · auth · surveillance · batch)과 개발 서버 배포 파이프라인이 있고, 도메인 기능은 auth 의 이메일 인증 · 회원 가입 · 로그인 · 세션 · 내 정보 · 비밀번호 · 카카오 로그인 · 동의 관리 · 관심 동네부터 들어가고 있다(이슈 #56~#61 · #112 · #154 · #155 · #217). 프론트엔드는 Next.js 기본 구성(토큰 · 린트 · 테스트)까지 있고 화면은 아직 없다. 존재하지 않는 명령을 추측해서 돌리지 않는다.

| 워크스페이스 | 대상 | 엔트리 문서 |
|--------------|------|-------------|
| `backend/` | Spring Cloud MSA + Hexagonal | [`backend/CLAUDE.md`](backend/CLAUDE.md) (빌드 · 테스트 명령 포함) |
| `frontend/` | 모바일 웹(PWA) · 화면 설계 | [`frontend/CLAUDE.md`](frontend/CLAUDE.md) (시안 · 디자인 규칙 · 명령 포함) |

**CI**: `backend/**` 를 바꾼 PR 과 develop 푸시에서 `backend-ci` 가 전 모듈 `./gradlew check` 를 돌리고 실행된 테스트 건수를 요약에 남긴다. `frontend/**` 를 바꾼 PR 과 develop 푸시에서 `frontend-ci` 가 `qa:verify` 와 같은 검사(format:check → lint → typecheck → test → build)를 돌린다. 문서만 바뀌면 format:check 만 돈다(`scripts/classify-frontend-changes.sh`). `label` 워크플로가 변경 경로로 PR 라벨(배포 대상)을 붙인다(`.github/labeler.yml`, dependabot PR 은 제외). dependabot 이 매주 월요일 프론트엔드 의존성 갱신 PR 을 묶어 올린다. 브랜치 보호가 걸려 있지 않으므로 **`backend-ci` · `frontend-ci` 가 빨간불인 PR 은 머지하지 않는다.**

작업 시작 시 해당 워크스페이스의 엔트리 문서를 먼저 읽는다. 로컬 인프라는 두지 않고 팀 개발 서버에 배포해서 개발한다 (팀 인프라 레포 `8llow8llowMe/Infra`).

| 환경 | 웹 | API (게이트웨이) |
|------|-----|------------------|
| dev | `https://dev.sneezecast.com` | `https://api-dev.sneezecast.com` |
| prod | `https://www.sneezecast.com` | `https://api.sneezecast.com` |

CORS 허용 오리진은 웹 도메인 두 개 + FE 로컬(`http://localhost:*`)이다. 게이트웨이와 auth-service 설정을 같게 맞춘다.

## 도메인 규칙 (기획서 기준, 구현이 지켜야 할 불변식)

**보고와 집계**
- 성인 본인이 주 1회 `증상 없음` 또는 증상군(호흡기: 발열·기침·인후통 / 장관: 구토·설사)을 보고한다.
- 같은 사람의 같은 주 보고는 수정할 수 있고, 집계에서는 **한 번만** 센다.
- 지표는 제보 건수가 아니라 **그 주 참여자 대비 증상 보고 비율**이다. 건강한 주간 보고가 분모라서 반드시 받는다.
- 지역 단위는 **행정동**이며 사용자가 직접 선택한다 (GPS 로 정하지 않는다).
- 표본이 적거나 보고가 불안정하면 **`자료 부족`** 으로 표시하고 수치·색상으로 위험을 암시하지 않는다. 시안의 단계는 `좋음` / `보통` / `주의` / `판단 보류`.

**공식 정보와 안내**
- 질병관리청 감시 자료는 시민 자가보고와 **섞지 않고** 출처·집계 단위·기준 주를 밝혀 별도로 보여 준다.
- 예방 안내는 **운영자가 검토·승인한 지역에만** 발행하고, 승인·수정·발행 이력을 남긴다. 운영자 화면은 참여 급증·반복 보고·기준선 대비 변화 후보를 보여 준다.
- AI 는 **출처가 확인된 집계값을 쉬운 문장으로 옮기는 초안 작성에만** 쓴다. 진단·공식 유행 선언을 하지 않는다.
- 알림은 동의한 사용자에게만 PWA 푸시로 보낸다. 미지원 환경(iOS 등)에서는 서비스 안에서 같은 내용을 볼 수 있어야 한다.

**개인정보**
- 건강·증상 정보는 민감정보다 (개인정보 보호법 제23조). 별도 동의, 접근권한 제한, 보관 기간·삭제 절차를 둔다.
- 정확한 주소·GPS 위치·성명·자유 서술을 **수집하지 않는다.**
- 아동 대리 보고·가족/학부모 그룹(시안의 `우리 가족 상태 알려주기`, `그룹` 탭)과 학교·보건소 연동은 **다음 단계**다. 법정대리인 동의·소수 집계 보호 설계 전에는 전제로 구현하지 않는다.

**외부 데이터** (출처·이용 조건 확인 후 연계): SGIS 행정구역·경계 API, 공공데이터포털 질병관리청 감염병 발생현황 API. 클라우드·AI 자원은 민관협력 지원 플랫폼 제공분을 이용 승인 후 쓴다.

## Git 협업 규칙

흐름: **이슈 생성 → 브랜치 → 커밋 → PR(`develop`) → CI 통과 → Rebase and merge → 브랜치 삭제.** 이슈 없이 브랜치를 만들지 않는다.

- **브랜치**: `<type>/<영역>/<이슈번호>-<요약>` — 예: `chore/infra/1-github-templates-skills`
  - `type`: `feature` | `fix` | `chore` | `refactor` | `docs` | `test` | `style`
  - `영역`: `fe` | `be` | `ai` | `infra` | `common` (여러 영역에 걸치면 `common`)
- **커밋·이슈·PR 제목**: `[영역] type: 요약` — 영역은 `BE` / `FE` / `AI` / `DOCS` / `INFRA`
  - 문서만 → `[DOCS]`, 빌드·CI·GitHub·Claude 설정 → `[INFRA]`
- **base 브랜치는 `develop`.** `main` 에 직접 올리지 않는다.
- **머지는 Rebase and merge 만** (`gh pr merge <번호> --rebase --delete-branch`). 커밋이 그대로 `develop` 에 남으므로 의미 단위로 나누고, `develop` 동기화는 merge 가 아니라 rebase 로 한다.
- 이슈 하나 = PR 하나, **30파일 / 1,000줄 이내**가 목표. 넘기면 쪼갤 수 있는지 먼저 검토하고, 넘겨야 하면 PR 본문에 이유를 적는다.
- PR 은 `Issue Number: #N` 을 채우고 assignee(`@me`)를 함께 지정한다.

이슈·PR·MR 본문은 템플릿 정본(`.github/ISSUE_TEMPLATE/`, `.github/PULL_REQUEST_TEMPLATE.md`)을 따르며, `/issue` · `/pr` · `/mr` 스킬이 이 규칙대로 초안 작성과 `gh` 생성을 맡는다.

## Claude Code 에이전트 구성

- 비단순 개발 작업의 진입점은 `/dev-orchestrator` 스킬이다. 작업을 SIMPLE / FEATURE / BUG / REFACTOR / ARCHITECTURE / LARGE_FEATURE 로 분류하고 `.claude/agents/` 의 역할 7종(explorer, crud-implementer, implementer, bug-investigator, reviewer, refactorer, architect)에 모델·effort 를 맞춰 분배한다. 분류 흐름의 정본은 그 스킬 문서, 모델 · effort 의 정본은 역할 파일 frontmatter 다.
- 역할 파일의 `model` 은 별칭(`fable` / `opus` / `sonnet`)으로 적어 최신 모델을 따라가게 한다. 새 역할에는 `model` 과 `effort` 를 반드시 적고, 읽기 전용 역할은 `tools: Read, Grep, Glob, Bash` 로 쓰기 권한을 막는다.
- 병렬 실행은 읽기 전용 역할에만, 쓰기 역할은 한 번에 하나만. 하위 에이전트는 커밋·푸시·이슈·PR 을 만들지 않는다.

### 작업별 역할 · 모델 배정

`/dev-orchestrator` 를 부르지 않아도 하위 에이전트를 쓸 때는 이 표로 역할을 고른다. 모델 · effort 는 역할 파일 frontmatter 가 정하므로 **역할만 고르면 배정이 따라온다.**

| 작업 | 역할 | 모델 · effort | 예 |
|------|------|---------------|----|
| 고칠 위치 · 호출 흐름 · 영향 범위 찾기 | `explorer` | sonnet · low | "이 API 를 부르는 화면", "이 엔티티를 쓰는 모듈" |
| 설계 판단이 없는 작은 변경 | `crud-implementer` | sonnet · medium | DTO · 매퍼 · 문구 · 목 데이터 · 설정값 · 문서 동기화 · 작은 테스트 |
| 기능 구현 · 원인이 확정된 버그 수정 | `implementer` | opus · high | 시안 → 화면 구현, API 엔드포인트, 잡 · 도메인 로직 |
| 원인이 불명확한 버그 | `bug-investigator` | opus · xhigh | 재현 불안정, 트랜잭션 · 동시성 · 캐시 · 인증 얽힘 |
| 커밋 · PR 직전 diff 검토 | `reviewer` | opus · high | 구현 역할이 끝낸 뒤, 공개 API · 보안 · 복수 계층 변경 |
| 동작 보존 리팩토링 | `refactorer` | opus · high | 범위 · 보존할 동작이 정해진 구조 변경 |
| 되돌리기 비싼 설계 | `architect` | fable · xhigh | 서비스 경계 · 서비스 간 계약 · 보안 · 트랜잭션 구조 |

- 하위 에이전트는 **이 저장소의 역할로만** 부른다. 내장 `general-purpose` · `Explore` · `Plan` 은 역할 파일의 `model` · `effort` 를 따르지 않으므로(세션 설정을 물려받음) 쓰지 않는다. 맞는 역할이 없으면 메인 실행자가 직접 하거나 역할을 새로 만든다.
- `Agent` 호출에서 `model` 을 덮어쓰지 않는다. 계정에 그 모델이 없을 때만 예외로 덮어쓰고 보고에 적는다.
- 위임 비용이 더 큰 일(한두 줄 수정, 이미 연 파일의 작은 편집)은 역할을 부르지 않고 메인 실행자가 한다.
- 역할 · 모델을 바꾸면 역할 파일, `/dev-orchestrator` 스킬 표, 이 표를 함께 고친다.

## 파일 규칙

- 모든 파일은 **UTF-8 (no BOM)**.
- 문서·스킬·템플릿은 한국어로 쓴다.
