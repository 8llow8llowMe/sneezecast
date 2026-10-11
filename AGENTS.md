# sneezecast Agents Guide

## 목적

- Codex · Cursor 등 코딩 에이전트가 저장소 **루트**에서 작업을 시작할 때 먼저 읽는 엔트리다.
- **프로젝트 규칙의 정본은 루트 [`CLAUDE.md`](CLAUDE.md) 다.** 이 문서는 지도와 요약만 두고, Claude Code 에 없는 호스트 고유 규칙(Codex 공동 작성자 표기 · `fork_turns`)만 여기서 정한다. 호스트와 무관하게 `CLAUDE.md` 를 먼저 읽는다.

## 서비스

**우리동네체온계 (sneezecast)** — 시민의 주간 건강 보고를 모아 행정동 단위 증상 변화를 보여 주는 참여형 감염병 정보 모바일 웹(PWA). 보고 · 집계 · 공식 정보 · 개인정보에 관한 불변식은 `CLAUDE.md` 의 "도메인 규칙" 이 정본이며, **구현은 반드시 그것을 지킨다.**

## 워크스페이스

| 경로 | 대상 | 엔트리 문서 |
|------|------|-------------|
| `backend/` | Spring Cloud MSA + Hexagonal | [`backend/CLAUDE.md`](backend/CLAUDE.md) (빌드 · 테스트 명령, `backend/docs/*.md`) |
| `frontend/` | 모바일 웹(PWA) · 화면 설계 | [`frontend/CLAUDE.md`](frontend/CLAUDE.md) (시안 · 디자인 규칙 · 명령) |

**작업 시작 시 해당 워크스페이스의 엔트리 문서를 먼저 읽는다.** 두 워크스페이스에 걸치면 양쪽 다 읽는다. 문서에 없는 명령을 추측해서 돌리지 않는다.

## 공통 규칙 요약

세부와 근거는 `CLAUDE.md` 에 있다.

- **인코딩**: 모든 파일은 UTF-8 (no BOM). `.editorconfig` · `.gitattributes` 를 덮어쓰지 않는다.
- **흐름**: 이슈 → 브랜치 → 커밋 → PR(`develop`) → CI 통과 → Rebase and merge → 브랜치 삭제. 이슈 없이 브랜치를 만들지 않는다.
- **브랜치**: `<type>/<영역>/<이슈번호>-<요약>` (`영역` = `fe` / `be` / `ai` / `infra` / `common`)
- **제목**: `[영역] type: 요약` (`[BE]` / `[FE]` / `[AI]` / `[DOCS]` / `[INFRA]`)
- **CI**: `backend-ci` · `frontend-ci` · `agents-ci` 가 빨간불인 PR 은 머지하지 않는다.

### AI 공동 작성자 표기

Codex 가 실질적으로 작성한 변경을 Codex 가 커밋할 때는 커밋 메시지 마지막에 아래 트레일러를 붙인다. 기존 작성자 · 커미터 정보는 바꾸지 않는다. 사용자가 직접 작성한 변경만 있는 커밋에는 붙이지 않는다.

```text
Co-authored-by: Codex <codex@openai.com>
```

## 스킬

스킬 정본은 `.agents/skills/*` 이고, `.claude/skills/*` 는 Claude Code 용 동기화 사본이다. **정본만 고치고 `sh scripts/sync-agent-skills.sh --write` 로 사본을 갱신한다.** 관리 규칙은 [docs/agent-skills.md](docs/agent-skills.md).

| 스킬 | 쓰임 | Codex | Claude Code · Cursor |
|------|------|-------|----------------------|
| `dev-orchestrator` | 비단순 개발 작업의 분류와 역할 분배 | `$dev-orchestrator` | `/dev-orchestrator` |
| `issue` | 이슈 초안 · 생성 | `$issue` | `/issue` |
| `pr` | PR 본문 · 생성 | `$pr` | `/pr` |
| `mr` | MR 본문 | `$mr` | `/mr` |

## 역할 에이전트

비단순 개발 작업은 `dev-orchestrator` 로 분류해 필요한 역할만 부른다. 분류와 흐름은 호스트가 같고, **실행 수단만 다르다.**

| 호스트 | 역할 파일 | 운영 정본 |
|--------|-----------|-----------|
| Claude Code | `.claude/agents/*.md` (kebab-case, `model` · `effort`) | [docs/claude-agents.md](docs/claude-agents.md) |
| Codex | `.codex/agents/*.toml` (snake_case, `model` · `model_reasoning_effort` · `sandbox_mode`) | [docs/codex-agents.md](docs/codex-agents.md) |
| Cursor | 없음 — Ask · Plan · Agent 모드로 적용 | [adapters/cursor.md](.agents/skills/dev-orchestrator/adapters/cursor.md) |

- 공용 역할 7종: `explorer`, `crud-implementer`/`crud_implementer`, `implementer`, `bug-investigator`/`bug_investigator`, `reviewer`, `refactorer`, `architect`.
- **역할 본문의 정본은 `.claude/agents/*.md` 다.** Codex 역할 파일은 그 본문을 읽어 따른다.
- **Codex 에서 역할을 부를 때는 `fork_turns` 를 `"none"` 이나 양의 정수 문자열로 준다.** 기본값 `all` 이면 부모의 모델 · 추론 강도를 물려받아 역할 파일 값이 무시된다 ([adapters/codex.md](.agents/skills/dev-orchestrator/adapters/codex.md)).
- 한 호스트의 모델 ID 를 다른 호스트에 복사하지 않는다.
- 병렬 실행은 서로 독립적인 읽기 전용 조사 · 검토에만 쓴다. 쓰기는 한 실행자가 순차로 한다. 하위 에이전트는 커밋 · 푸시 · 이슈 · PR 을 만들지 않는다.
