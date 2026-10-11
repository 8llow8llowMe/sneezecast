# 에이전트 공용 스킬 관리

## 목적

sneezecast 프로젝트 스킬(`issue` · `pr` · `mr` · `dev-orchestrator`)을 Claude Code · Codex · Cursor 에서 같은 내용으로 쓴다. 정본을 한 곳에 두고 호스트별 위치로 동기화해 규칙이 호스트마다 갈라지지 않게 한다.

프로젝트 고유 규칙의 정본은 루트 `CLAUDE.md` 와 각 워크스페이스 문서다. 스킬은 그 규칙을 작업별로 불러오는 진입점이다.

## 디렉터리와 호출 방식

| 호스트 | 발견 경로 | 호출 |
|--------|-----------|------|
| Codex | `.agents/skills/<name>/SKILL.md` | `$name` |
| Cursor | `.agents/skills/<name>/SKILL.md` | `/name` |
| Claude Code | `.claude/skills/<name>/SKILL.md` | `/name` |

- **`.agents/skills/` 가 정본이다.**
- `.claude/skills/` 는 Claude Code 가 발견할 수 있게 두는 동기화 사본이다. **직접 고치지 않는다.**
- 두 디렉터리의 파일 집합과 내용은 byte 단위로 같아야 한다. Cursor 가 두 경로를 다 발견하더라도 이름과 내용이 같으므로 어느 쪽을 써도 동작이 같다.

## 변경 절차

1. `.agents/skills/<name>/` 의 스킬을 고치거나 추가한다.
2. Claude Code 사본을 갱신한다.

   ```bash
   sh scripts/sync-agent-skills.sh --write
   ```

3. 파일 집합 · 내용 · frontmatter · 상대 링크 · UTF-8 no BOM 을 검사한다.

   ```bash
   sh scripts/sync-agent-skills.sh --check
   ```

4. 새 스킬이면 `name` 이 디렉터리명과 같은지, `description` 이 실제 사용 시점을 구분하는지 확인한다. 상세 규칙은 문서에 두고 `SKILL.md` 에는 작업 흐름과 필요한 문서 링크만 둔다.

`--write` 는 사본에만 있는 파일을 지우지 않고 멈춘다. 누군가 사본을 직접 고친 흔적일 수 있어서다. 그 변경을 정본으로 옮긴 뒤 사본 파일을 지우고 다시 돌린다.

`.agents/` · `.claude/` · `.codex/` 를 바꾼 PR 에서 `agents-ci` 가 `--check` 와 `scripts/check-claude-agents.sh` 를 돌린다.

**왜 sh 인가** — 개발 PC 에 Python 이 없을 수 있다(Windows 의 Microsoft Store 스텁은 `python` 이 exit 49 로 죽는다). Git Bash 와 CI 의 sh 로 어느 환경에서든 돈다.

## 호스트 독립성

- 스킬 본문에 특정 호스트의 도구 이름을 필수 전제로 두지 않는다.
- 역할 분리가 필요하면 현재 호스트의 하위 에이전트 기능을 쓴다. 없으면 메인 실행자가 같은 역할을 순서대로 수행한다.
- 구현 파일은 한 실행자만 수정하고 검토 역할은 읽기 전용으로 둔다.
- 스킬 호출만으로 외부 상태 변경이나 위험한 명령의 권한이 넓어지지 않는다.

## 역할 에이전트

`dev-orchestrator` 의 공통 절차는 스킬 본문이고, 호스트별 실행 수단은 스킬 안의 어댑터다.

| 호스트 | 역할 파일 | 실행 수단 | 정본 |
|--------|-----------|-----------|------|
| Claude Code | `.claude/agents/*.md` | 역할 파일의 `model` · `effort` | [claude-agents.md](claude-agents.md) |
| Codex | `.codex/agents/*.toml` | 역할 파일의 `model` · `model_reasoning_effort` | [codex-agents.md](codex-agents.md), [adapters/codex.md](../.agents/skills/dev-orchestrator/adapters/codex.md) |
| Cursor | 없음 | Ask · Plan · Agent 모드 | [adapters/cursor.md](../.agents/skills/dev-orchestrator/adapters/cursor.md) |

Cursor 는 `.cursor/rules/sneezecast.mdc` 가 항상 적용되는 프로젝트 룰로 `AGENTS.md` 와 이 문서를 가리킨다.

## 인코딩

- 모든 `SKILL.md` · 참고 문서 · 스크립트 · `.toml` 은 UTF-8 no BOM 으로 저장한다.
- Windows PowerShell 5 는 UTF-8 no BOM 스크립트의 한글 리터럴을 CP949 로 오해할 수 있다. 외부 API 에 한글을 보낼 때는 UTF-8 처리가 명시된 런타임을 쓰고, 저장 후 다시 조회해 왕복 검증한다.
