# Codex 역할별 에이전트 운영 가이드

## 목적

Claude Code 용으로 만든 역할 에이전트 7종을 Codex 에서 같은 규칙으로 쓴다. 역할 분류와 흐름은 호스트가 같고, 다른 것은 모델 ID 와 권한 표기다.

Codex 설정은 저장소의 `.codex/config.toml` 과 `.codex/agents/*.toml` 에 있어 이 저장소를 **신뢰(trust)하고 연** 세션에 같이 적용된다. 개인 인증 · 권한 설정은 저장소에 넣지 않는다.

## 운영 결론

**모든 대화에서 에이전트를 자동으로 병렬 실행하지 않는다.** 비단순 개발 작업은 `dev-orchestrator` 가 분류해 필요한 역할만 부른다.

- 명시 호출: `$dev-orchestrator 작업 내용`
- 직접 요청: `explorer 로 호출 흐름을 조사해줘` 처럼 역할을 지정

루트 `AGENTS.md` 는 진입 규칙만 두고, 분류와 실행 흐름은 [dev-orchestrator 스킬](../.agents/skills/dev-orchestrator/SKILL.md)과 [Codex 어댑터](../.agents/skills/dev-orchestrator/adapters/codex.md)에 둔다.

## 공통 설정

`.codex/config.toml`:

```toml
[agents]
enabled = true
max_concurrent_threads_per_session = 3
default_subagent_model = "gpt-6-luna"
default_subagent_reasoning_effort = "medium"
```

- 상한 3 은 **하위 에이전트 스레드 수**이며 메인 스레드는 제외된다. 읽기 전용 동시 실행 2~3개 상한(스킬 기본 원칙)과 맞춘 값이다.
- `default_subagent_*` 는 역할 파일 없이 만든 하위 에이전트에만 적용된다. 역할 파일은 자기 `model` · `model_reasoning_effort` 를 쓴다.

## 역할과 모델

| 역할 | 모델 | 추론 강도 | `sandbox_mode` | Claude Code 대응 |
|------|------|-----------|----------------|------------------|
| `explorer` | `gpt-6-luna` | `low` | `read-only` | `explorer` · Sonnet |
| `crud_implementer` | `gpt-6-luna` | `medium` | `workspace-write` | `crud-implementer` · Sonnet |
| `implementer` | `gpt-6.1-sol` | `high` | `workspace-write` | `implementer` · Opus |
| `bug_investigator` | `gpt-6.1-sol` | `xhigh` | `read-only` | `bug-investigator` · Opus |
| `reviewer` | `gpt-6.1-sol` | `high` | `read-only` | `reviewer` · Opus |
| `refactorer` | `gpt-6.1-sol` | `high` | `workspace-write` | `refactorer` · Opus |
| `architect` | `gpt-6-astra` | `xhigh` | `read-only` | `architect` · Fable |

- **이름은 snake_case 다.** Claude Code 의 kebab-case 이름에서 `-` 를 `_` 로 바꾼다.
- **추론 강도는 Claude 역할의 `effort` 와 같다.** sneezecast 는 강도를 작업 등급이 아니라 역할에 고정한다. 그래서 역할 파일에 `model_reasoning_effort` 를 적고, spawn 시 덮어쓰지 않는다.
- 모델 등급 대응: 탐색 · 단순 구현은 Luna(Sonnet 자리), 일반 구현 · 원인 분석 · 검토 · 리팩토링은 Sol(Opus 자리), 설계는 Astra(Fable 자리).
- **역할을 부를 때는 `fork_turns` 를 `"none"` 이나 양의 정수 문자열로 준다.** 기본값 `all` 은 부모 기록 전체를 넘기는 fork 라서 부모의 모델 · 강도를 물려받고 역할 파일 값이 적용되지 않는다.
- 역할 파일 값은 spawn 값보다 우선한다. 계정에 `gpt-6-astra` 나 `xhigh` 가 없을 때의 대체 절차는 [Codex 어댑터](../.agents/skills/dev-orchestrator/adapters/codex.md) 의 "모델이 없을 때" 를 따른다. 메인 세션 기본은 `gpt-6.1-sol` / `medium` 이다.
- **이 표가 Codex 모델의 유일한 문서 표다.** 어댑터 · `AGENTS.md` 는 이 표를 링크한다. 모델을 바꾸면 `.codex/agents/*.toml` 과 이 표를 함께 고친다.

## 역할 파일 구조

역할 프롬프트를 두 벌로 쓰지 않는다. **역할 본문의 정본은 `.claude/agents/<kebab-case>.md` 다.** Codex 파일의 `developer_instructions` 는 그 파일을 읽고 따르라는 지시와 Codex 고유 규칙(도구 이름 바꿔 읽기 · UTF-8 · 커밋 금지 · 하위 에이전트 금지)만 담는다.

```toml
name = "reviewer"
description = """..."""
model = "gpt-6.1-sol"
model_reasoning_effort = "high"
sandbox_mode = "read-only"
developer_instructions = """
이 역할의 정본은 .claude/agents/reviewer.md 다. ...
"""
```

역할 본문을 고치면 Claude 역할 파일만 고친다. 이름 · 모델 · 강도 · 권한을 바꾸면 [claude-agents.md](claude-agents.md) 의 "역할을 추가 · 변경할 때" 네 곳을 함께 고친다.

## 병렬화 규칙

병렬화해도 되는 작업: 저장소 탐색 · 의존성 분석, 로그 · 테스트 공백 조사, 서로 파일과 외부 상태를 바꾸지 않는 읽기 전용 검토.

병렬화하지 않는 작업: 같은 파일 · 같은 공개 계약 편집, DB 스키마 · 공유 도메인 객체 변경, 여러 구현자의 동시 수정, 커밋 · 푸시 · 이슈 · PR 생성.

하위 에이전트는 다른 하위 에이전트를 만들지 않는다. 추가 위임이 필요하면 메인 실행자가 작업 경계를 다시 나눈다.

## 검증

```bash
sh scripts/check-claude-agents.sh
sh scripts/sync-agent-skills.sh --check
git diff --check
```

`check-claude-agents.sh` 는 역할마다 Codex 대응 파일이 있는지, `name` 이 파일명과 같은지, `model_reasoning_effort` 가 Claude `effort` 와 같은지, `sandbox_mode` 가 읽기 전용 여부와 맞는지, BOM 이 없는지를 본다. TOML 문법 자체는 Codex 가 로드할 때 확인된다.

## 공식 문서

- [Codex Subagents](https://developers.openai.com/codex/subagents)
- [Codex Configuration Reference](https://developers.openai.com/codex/config-reference)
