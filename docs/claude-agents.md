# Claude Code 역할별 에이전트 운영 가이드

## 목적

작업의 난이도와 위험도에 맞춰 하위 에이전트의 모델 · 추론 깊이를 고른다. 단순 작업에 고비용 추론을 반복하지 않으면서, 버그 · 리팩토링 · 아키텍처에는 충분한 깊이를 확보한다.

역할 정의는 저장소의 `.claude/agents/*.md` 에 있어 이 저장소를 여는 모든 PC · 세션에 같이 적용된다. 개인 권한 설정은 저장소에 넣지 않는다.

## 운영 결론

**모든 대화에서 에이전트를 자동으로 병렬 실행하지 않는다.** 비단순 개발 작업은 `/dev-orchestrator` 가 분류해 필요한 역할만 부른다. 분류 흐름의 정본은 [dev-orchestrator 스킬](../.agents/skills/dev-orchestrator/SKILL.md), 모델 · effort 의 정본은 역할 파일 frontmatter 다.

- 명시 호출: `/dev-orchestrator 작업 내용`
- 직접 요청: `explorer 로 호출 흐름을 조사해줘` 처럼 역할을 지정
- 내장 `general-purpose` · `Explore` · `Plan` 은 쓰지 않는다. 역할 파일의 `model` · `effort` 를 따르지 않고 세션 설정을 물려받는다.

## 역할과 모델

| 역할 | `model` | `effort` | `tools` | Codex 대응 |
|------|---------|----------|---------|------------|
| `explorer` | `sonnet` | `low` | `Read, Grep, Glob, Bash` | `explorer` |
| `crud-implementer` | `sonnet` | `medium` | 생략(전체 상속) | `crud_implementer` |
| `implementer` | `opus` | `high` | 생략(전체 상속) | `implementer` |
| `bug-investigator` | `opus` | `xhigh` | `Read, Grep, Glob, Bash` | `bug_investigator` |
| `reviewer` | `opus` | `high` | `Read, Grep, Glob, Bash` | `reviewer` |
| `refactorer` | `opus` | `high` | 생략(전체 상속) | `refactorer` |
| `architect` | `fable` | `xhigh` | `Read, Grep, Glob, Bash` | `architect` |

- `model` 은 별칭(`fable` / `opus` / `sonnet` / `haiku`)으로 적어 그 계열의 최신 모델을 따라가게 한다. 현재 해석은 스킬 문서의 별칭 표에 있다.
- **`model` · `effort` 를 빠뜨리지 않는다.** 빠지면 세션 모델 · effort 를 상속해 비용이 튄다.
- **읽기 전용 역할은 `tools` 를 명시한다.** `tools` 를 생략하면 전체 도구(=쓰기)를 상속한다.
- `Agent` 호출에서 `model` 을 덮어쓰지 않는다. 계정에 그 모델이 없을 때만 예외로 덮어쓰고 보고에 적는다.

## 역할을 추가 · 변경할 때

한 역할은 네 곳에 걸쳐 있다. **같은 PR 에서 함께 고친다.**

1. `.claude/agents/<kebab-case>.md` — frontmatter `name` · `description` · `model` · `effort`, 읽기 전용이면 `tools`
2. `.codex/agents/<snake_case>.toml` — `name` · `model` · `model_reasoning_effort`(= Claude `effort`) · `sandbox_mode`. 본문은 Claude 역할 파일을 가리키기만 한다. 이름 · 모델이 바뀌면 [codex-agents.md](codex-agents.md) 의 역할 표도 고친다
3. `dev-orchestrator` 스킬의 역할 표 · 호스트별 역할 이름 표 (`.agents/skills/` 에서 고치고 `sh scripts/sync-agent-skills.sh --write`)
4. 루트 `CLAUDE.md` 의 "작업별 역할 · 모델 배정" 표, 이 문서의 역할 표

새 읽기 전용 역할이면 `scripts/check-claude-agents.sh` 의 `read_only_roles` 에도 넣는다.

사용자 전역(`~/.claude/agents/`)에 같은 이름의 에이전트가 있어도 이 저장소의 프로젝트 스코프 정의가 우선한다.

## 검증

```bash
sh scripts/check-claude-agents.sh
```

검사 항목: UTF-8 BOM 금지, frontmatter · `name`(= 파일명) · `description`, `model` · `effort` 존재와 허용값, 읽기 전용 역할의 `tools` 와 쓰기 도구 부재, Codex 대응 파일의 존재 · 이름 · 추론 강도 일치 · `sandbox_mode`. `agents-ci` 가 같은 스크립트를 돌린다.
