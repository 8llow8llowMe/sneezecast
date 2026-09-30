# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트

**우리동네체온계 (sneezecast)**: 시민의 주간 건강 보고를 모아 행정동 단위 증상 변화를 보여 주고, 충분한 자료가 있을 때에만 예방 정보를 전하는 참여형 감염병 정보 모바일 웹(PWA). 2026 AI·디지털 기반 사회문제 해결 챌린지 출품작(팀 팔로팔로미)이다.

## 현재 상태

셋업 단계다. 백엔드는 모듈 골격(core · cloud · auth · surveillance · batch)까지 있고 도메인 기능은 아직 없다. 프론트엔드는 없다. 존재하지 않는 명령을 추측해서 돌리지 않는다.

| 워크스페이스 | 대상 | 엔트리 문서 |
|--------------|------|-------------|
| `backend/` | Spring Cloud MSA + Hexagonal | [`backend/CLAUDE.md`](backend/CLAUDE.md) (빌드 · 테스트 명령 포함) |

**CI**: `backend/**` 를 바꾼 PR 과 develop 푸시에서 `backend-ci` 가 전 모듈 `./gradlew check` 를 돌리고 실행된 테스트 건수를 요약에 남긴다. `label` 워크플로가 변경 경로로 PR 라벨(배포 대상)을 붙인다(`.github/labeler.yml`). 저장소가 비공개 무료 플랜이라 브랜치 보호를 걸 수 없으므로 **`backend-ci` 가 빨간불인 PR 은 머지하지 않는다.**

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

- 비단순 개발 작업의 진입점은 `/dev-orchestrator` 스킬이다. 작업을 SIMPLE / FEATURE / BUG / REFACTOR / ARCHITECTURE / LARGE_FEATURE 로 분류하고 `.claude/agents/` 의 역할 7종(explorer, crud-implementer, implementer, bug-investigator, reviewer, refactorer, architect)에 모델·effort 를 맞춰 분배한다. 역할·모델 표의 정본은 그 스킬 문서다.
- 역할 파일의 `model` 은 별칭(`fable` / `opus` / `sonnet`)으로 적어 최신 모델을 따라가게 한다. 새 역할에는 `model` 과 `effort` 를 반드시 적고, 읽기 전용 역할은 `tools: Read, Grep, Glob, Bash` 로 쓰기 권한을 막는다.
- 병렬 실행은 읽기 전용 역할에만, 쓰기 역할은 한 번에 하나만. 하위 에이전트는 커밋·푸시·이슈·PR 을 만들지 않는다.

## 파일 규칙

- 모든 파일은 **UTF-8 (no BOM)**.
- 문서·스킬·템플릿은 한국어로 쓴다.
