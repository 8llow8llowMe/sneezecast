# Codex 어댑터

Codex 에서 `dev-orchestrator` 를 실행할 때의 역할 호출 방법이다. 작업 분류(SIMPLE ~ LARGE_FEATURE)와 흐름은 [SKILL.md](../SKILL.md) 를 따른다. 역할 · 모델 · 추론 강도 · 권한 표와 설정의 정본은 [docs/codex-agents.md](../../../../docs/codex-agents.md) 다. 표를 여기에 옮겨 적지 않는다.

## 호출

- 명시 호출: `$dev-orchestrator 작업 내용`
- 역할은 `.codex/agents/*.toml` 의 snake_case 이름으로 부른다 (`crud_implementer`, `bug_investigator`).
- 역할 파일이 `model` 과 `model_reasoning_effort` 를 고정한다. Claude Code 처럼 **역할을 고르면 모델 · 강도가 따라온다** — 단, 아래 `fork_turns` 조건을 지킬 때만이다.
- **역할을 부를 때는 반드시 `fork_turns` 를 `"none"` 이나 양의 정수 문자열로 준다.** `fork_turns` 의 기본값은 `all` 이다. 생략하거나 `all` 이면 부모 기록 전체를 넘기는 fork 가 되어 부모의 모델 · 추론 강도를 물려받고, 역할 파일 값이 적용되지 않는다. 필요한 맥락은 요약해서 프롬프트로 전달한다.
- 메인 세션의 기본은 `gpt-6.1-sol` / `medium` 이다. `high` 이상은 하위 역할에만 쓴다.

## 모델이 없을 때

**역할 파일의 `model` · `model_reasoning_effort` 가 spawn 값보다 우선한다.** 그래서 계정에 `gpt-6-astra` 나 `xhigh` 가 없을 때 같은 역할을 spawn 값으로 덮어써도 바뀌지 않는다.

이때는 역할 파일 없이 기본 에이전트를 부르면서 바로 아래 단계(`gpt-6-astra` → `gpt-6.1-sol`, `xhigh` → `high`)의 모델 · 강도와 `fork_turns = "none"` 을 지정하고, 프롬프트에 `.claude/agents/<역할>.md` 본문을 따르라고 적는다. 읽기 전용 역할이면 파일을 고치지 말라고도 적는다. 보고에 대체 사실을 남긴다.

## 예시

관심 동네 목록 응답에 필드 하나 추가, 위치와 계약이 분명할 때:

- `TYPE: SIMPLE`
- `explorer` 생략
- `crud_implementer` 가 DTO · 매퍼 · 테스트를 고친다
- 대상 모듈 테스트만 실행

주간 보고 수정이 간헐적으로 집계에 두 번 잡힐 때:

- `TYPE: BUG` (같은 사람 · 같은 주 1회 집계 불변식, 원인 미상)
- `bug_investigator` + `explorer` 를 `fork_turns = "none"` 으로 병렬로 부른다
- 메인 실행자가 원인을 확정한 뒤 `implementer` 가 최소 수정
- `reviewer` 로 최종 검토
