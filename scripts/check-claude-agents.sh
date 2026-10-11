#!/usr/bin/env sh
#
# 역할 에이전트 정의를 검사한다 — Claude Code(.claude/agents/*.md)와 Codex 대응 파일(.codex/agents/*.toml).
#
# **왜 sh 인가** — Windows 개발 PC 에 Python 이 없을 수 있다(Microsoft Store 스텁만 있으면
# `python` 이 exit 49 로 죽는다). Git Bash 와 CI 의 sh 로 어느 PC 에서든 돈다.
#
# Claude Code 검사:
#   1. UTF-8 BOM 금지 — Windows 기본이 CP949 라 여기서 한글이 깨진다
#   2. YAML frontmatter 존재, `name` / `description` 필수
#   3. `name` 이 파일명과 일치 — 어긋나면 호출은 되는데 문서와 다른 이름이라 조용히 헷갈린다
#   4. `model` · `effort` 필수이고 허용값인지 — 빠뜨리면 세션 모델 · effort 를 상속해 비용이 튄다
#   5. 읽기 전용 역할이 실제로 읽기 전용인지 — `tools` 누락은 전체 도구 상속(=쓰기 가능)이다.
#      `tools` 는 허용 목록(Read · Grep · Glob · Bash) 안의 도구만 쓸 수 있다
#
# Codex 대응 검사:
#   6. 역할마다 `.codex/agents/<snake_case>.toml` 이 있고 BOM 이 없다
#   7. `name` 이 파일명과 같고 `model` · `model_reasoning_effort` · `sandbox_mode` 가 있다
#   8. `model_reasoning_effort` 가 Claude 역할의 `effort` 와 같다
#   9. 읽기 전용 역할은 `sandbox_mode = "read-only"`, 쓰기 역할은 `"workspace-write"`
#
# 실행:
#   sh scripts/check-claude-agents.sh

set -e

repo_root=$(git rev-parse --show-toplevel)
agents_dir="$repo_root/.claude/agents"
codex_dir="$repo_root/.codex/agents"

allowed_models="fable opus sonnet haiku"
allowed_efforts="low medium high xhigh max"

# `tools` 를 생략하면 전체 도구를 상속한다 = 파일을 고칠 수 있다.
# 읽기 전용이어야 하는 역할은 반드시 `tools` 를 명시해야 한다.
read_only_roles="explorer architect bug-investigator reviewer"
# 읽기 전용 역할의 `tools` 는 허용 목록 안에서만 고른다. 금지 목록이면 새 쓰기 도구가 생길 때 잘못 통과한다.
allowed_read_only_tools="Read Grep Glob Bash"

errors=0
count=0

fail() {
  echo "ERROR: $1" >&2
  errors=$((errors + 1))
}

contains() {
  # contains <haystack> <needle>
  for item in $1; do
    [ "$item" = "$2" ] && return 0
  done
  return 1
}

has_bom() {
  [ "$(head -c 3 "$1" | od -An -tx1 | tr -d ' \n')" = "efbbbf" ]
}

toml_string() {
  # toml_string <file> <key> — 최상위 한 줄짜리 `key = "value"` 의 값을 꺼낸다.
  # 첫 테이블 헤더 이후와 `"""` 여러 줄 문자열 안의 줄은 보지 않는다.
  awk '
    /^\[/ { exit }
    ml { if (index($0, "\"\"\"")) ml = 0; next }
    /"""/ { tmp = $0; n = gsub(/"""/, "", tmp); if (n % 2 == 1) ml = 1; next }
    { print }
  ' "$1" | sed -n "s/^$2[[:space:]]*=[[:space:]]*\"\([^\"]*\)\".*/\1/p" | head -n 1
}

if [ ! -d "$agents_dir" ]; then
  fail "Not a directory: .claude/agents"
  exit 1
fi

for file in "$agents_dir"/*.md; do
  [ -e "$file" ] || continue
  count=$((count + 1))
  base=$(basename "$file" .md)
  rel=".claude/agents/$(basename "$file")"

  # 1. BOM
  if has_bom "$file"; then
    fail "UTF-8 BOM is not allowed: $rel"
    continue
  fi

  # 2. frontmatter — 1행이 `---` 이고 닫는 `---` 이 있어야 한다
  if [ "$(head -n 1 "$file" | tr -d '\r')" != "---" ]; then
    fail "Missing YAML frontmatter: $rel"
    continue
  fi
  fm_end=$(tail -n +2 "$file" | tr -d '\r' | grep -n '^---$' | head -n 1 | cut -d: -f1)
  if [ -z "$fm_end" ]; then
    fail "Unterminated YAML frontmatter: $rel"
    continue
  fi
  fm=$(tail -n +2 "$file" | head -n $((fm_end - 1)) | tr -d '\r')

  field() {
    printf '%s\n' "$fm" | sed -n "s/^$1:[[:space:]]*//p" | head -n 1 | tr -d '"'"'"
  }

  # 3. name
  name=$(field name)
  if [ -z "$name" ]; then
    fail "Missing name: $rel"
  elif [ "$name" != "$base" ]; then
    fail "Name does not match filename: $rel: $name != $base"
  fi

  if [ -z "$(field description)" ]; then
    fail "Missing description: $rel"
  fi

  # 4. model · effort
  model=$(field model)
  if [ -z "$model" ]; then
    fail "Missing model (omitting it inherits the session model): $rel"
  elif ! contains "$allowed_models" "$model"; then
    fail "Unknown model: $rel: $model (allowed: $allowed_models)"
  fi
  effort=$(field effort)
  if [ -z "$effort" ]; then
    fail "Missing effort (omitting it inherits the session effort): $rel"
  elif ! contains "$allowed_efforts" "$effort"; then
    fail "Unknown effort: $rel: $effort (allowed: $allowed_efforts)"
  fi

  # 5. 읽기 전용 역할의 도구 권한
  read_only=false
  if contains "$read_only_roles" "$base"; then
    read_only=true
    tools=$(field tools)
    if [ -z "$tools" ]; then
      fail "Read-only role must declare tools (omitting it inherits write access): $rel"
    else
      for tool in $(printf '%s' "$tools" | tr ',' ' '); do
        if ! contains "$allowed_read_only_tools" "$tool"; then
          fail "Read-only role has non-read-only tool '$tool': $rel (allowed: $allowed_read_only_tools)"
        fi
      done
    fi
  fi

  # 6~9. Codex 대응 파일
  codex_name=$(printf '%s' "$base" | tr '-' '_')
  codex_file="$codex_dir/$codex_name.toml"
  codex_rel=".codex/agents/$codex_name.toml"
  if [ ! -f "$codex_file" ]; then
    fail "Missing Codex counterpart: $codex_rel (for $rel)"
    continue
  fi
  if has_bom "$codex_file"; then
    fail "UTF-8 BOM is not allowed: $codex_rel"
    continue
  fi
  if [ "$(toml_string "$codex_file" name)" != "$codex_name" ]; then
    fail "Codex name does not match filename: $codex_rel"
  fi
  if [ -z "$(toml_string "$codex_file" model)" ]; then
    fail "Missing model: $codex_rel"
  fi
  codex_effort=$(toml_string "$codex_file" model_reasoning_effort)
  if [ "$codex_effort" != "$effort" ]; then
    fail "Effort differs from Claude role: $codex_rel: '$codex_effort' != '$effort' ($rel)"
  fi
  sandbox=$(toml_string "$codex_file" sandbox_mode)
  if [ "$read_only" = true ]; then
    expected_sandbox="read-only"
  else
    expected_sandbox="workspace-write"
  fi
  if [ "$sandbox" != "$expected_sandbox" ]; then
    fail "Wrong sandbox_mode: $codex_rel: '$sandbox' (expected '$expected_sandbox')"
  fi
done

if [ "$count" -eq 0 ]; then
  fail "No agent definitions found under .claude/agents"
fi

# Claude 역할이 없는 Codex 파일 — 한쪽에서만 지운 흔적이다
for codex_file in "$codex_dir"/*.toml; do
  [ -e "$codex_file" ] || continue
  claude_name=$(basename "$codex_file" .toml | tr '_' '-')
  if [ ! -f "$agents_dir/$claude_name.md" ]; then
    fail "Codex-only role without Claude counterpart: .codex/agents/$(basename "$codex_file")"
  fi
done

if [ "$errors" -gt 0 ]; then
  echo "" >&2
  echo "check-claude-agents: $errors error(s)" >&2
  exit 1
fi

echo "check-claude-agents: OK ($count roles, Codex counterparts in sync)"
