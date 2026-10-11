#!/usr/bin/env sh
#
# 공용 스킬 정본(.agents/skills/)을 Claude Code 미러(.claude/skills/)로 동기화하고 검사한다.
#
# **왜 sh 인가** — Windows 개발 PC 에 Python 이 없을 수 있다(Microsoft Store 스텁만 있으면
# `python` 이 exit 49 로 죽는다). Git Bash 와 CI 의 sh 로 어느 PC 에서든 돈다.
#
# 검사 항목:
#   1. 정본 · 미러의 파일 집합과 내용이 byte 단위로 같다
#   2. 모든 .md 에 UTF-8 BOM 이 없고 상대 링크가 실제 파일을 가리킨다
#   3. SKILL.md 에 YAML frontmatter, `name` · `description` 이 있고 `name` 이 디렉터리명과 같다
#
# 실행:
#   sh scripts/sync-agent-skills.sh --write   # 정본 → 미러 복사 후 검사
#   sh scripts/sync-agent-skills.sh --check   # 파일을 바꾸지 않고 검사만 (기본값)

set -e

# sort · comm 의 정렬 기준을 맞춘다. 로캘이 다르면 comm 이 "not in sorted order" 로 실패한다.
export LC_ALL=C

repo_root=$(git rev-parse --show-toplevel)
canonical=".agents/skills"
mirror=".claude/skills"
mode="${1:---check}"

case "$mode" in
  --write | --check) ;;
  *)
    echo "usage: sh scripts/sync-agent-skills.sh [--write|--check]" >&2
    exit 2
    ;;
esac

cd "$repo_root"

errors=0

fail() {
  echo "ERROR: $1" >&2
  errors=$((errors + 1))
}

list_files() {
  # list_files <root> — root 기준 상대 경로를 정렬해 출력한다
  (cd "$1" && find . -type f ! -path '*/__pycache__/*' | sed 's|^\./||' | sort)
}

if [ ! -d "$canonical" ]; then
  echo "ERROR: Not a directory: $canonical" >&2
  exit 1
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# 파일 목록은 임시 파일로 넘기고 `while read` 로 읽는다. 공백이 든 경로가 쪼개지지 않고,
# 파이프가 아니라 리다이렉트라 루프가 현재 셸에서 돌아 errors 가 유지된다.
if [ "$mode" = "--write" ]; then
  mkdir -p "$mirror"
  list_files "$canonical" > "$tmp/canonical"
  list_files "$mirror" > "$tmp/mirror"
  # 미러에만 있는 파일은 지우지 않는다. 누군가 미러를 직접 고친 흔적일 수 있다.
  comm -13 "$tmp/canonical" "$tmp/mirror" > "$tmp/extras"
  if [ -s "$tmp/extras" ]; then
    while IFS= read -r path; do
      fail "Refusing to delete mirror-only file: $mirror/$path"
    done < "$tmp/extras"
    exit 1
  fi
  while IFS= read -r path; do
    mkdir -p "$(dirname "$mirror/$path")"
    cp "$canonical/$path" "$mirror/$path"
  done < "$tmp/canonical"
fi

# 1. 파일 집합과 내용
list_files "$canonical" > "$tmp/canonical"
list_files "$mirror" > "$tmp/mirror" 2>/dev/null || : > "$tmp/mirror"

comm -23 "$tmp/canonical" "$tmp/mirror" > "$tmp/missing"
while IFS= read -r path; do
  fail "Missing Claude mirror file: $mirror/$path"
done < "$tmp/missing"
comm -13 "$tmp/canonical" "$tmp/mirror" > "$tmp/extras"
while IFS= read -r path; do
  fail "Claude mirror-only file: $mirror/$path"
done < "$tmp/extras"
comm -12 "$tmp/canonical" "$tmp/mirror" > "$tmp/common"
while IFS= read -r path; do
  if ! cmp -s "$canonical/$path" "$mirror/$path"; then
    fail "Mirror content differs: $path (edit $canonical, then run --write)"
  fi
done < "$tmp/common"

# 2. 모든 .md — BOM 과 상대 링크
validate_markdown() {
  file="$1"
  dir=$(dirname "$file")

  if [ "$(head -c 3 "$file" | od -An -tx1 | tr -d ' \n')" = "efbbbf" ]; then
    fail "UTF-8 BOM is not allowed: $file"
    return 1
  fi

  # 상대 링크 — [텍스트](경로). 외부 URL · 앵커 · 코드 블록 안의 예시는 건너뛴다.
  awk '/^```/ { code = !code; next } !code' "$file" \
    | { grep -o '\]([^)]*)' || true; } | sed 's/^](//; s/)$//; s/#.*$//' > "$tmp/links"
  while IFS= read -r target; do
    case "$target" in
      http://* | https://* | mailto:* | '') continue ;;
    esac
    if [ ! -e "$dir/$target" ]; then
      fail "Broken local link: $file: $target"
    fi
  done < "$tmp/links"
}

# 3. SKILL.md — frontmatter 와 name · description
validate_skill() {
  file="$1"
  dir=$(dirname "$file")

  if [ "$(head -n 1 "$file" | tr -d '\r')" != "---" ]; then
    fail "Missing YAML frontmatter: $file"
    return
  fi
  fm_end=$(tail -n +2 "$file" | tr -d '\r' | grep -n '^---$' | head -n 1 | cut -d: -f1)
  if [ -z "$fm_end" ]; then
    fail "Unterminated YAML frontmatter: $file"
    return
  fi
  fm=$(tail -n +2 "$file" | head -n $((fm_end - 1)) | tr -d '\r')

  name=$(printf '%s\n' "$fm" | sed -n 's/^name:[[:space:]]*//p' | head -n 1 | tr -d '"'"'")
  if [ -z "$name" ]; then
    fail "Missing name: $file"
  elif [ "$name" != "$(basename "$dir")" ]; then
    fail "Name does not match directory: $file: $name != $(basename "$dir")"
  fi
  if [ -z "$(printf '%s\n' "$fm" | sed -n 's/^description:[[:space:]]*//p' | head -n 1)" ]; then
    fail "Missing description: $file"
  fi
}

skill_count=0
for root in "$canonical" "$mirror"; do
  find "$root" -type f -name '*.md' | sort > "$tmp/markdown"
  while IFS= read -r file; do
    validate_markdown "$file" || continue
    if [ "$(basename "$file")" = "SKILL.md" ]; then
      skill_count=$((skill_count + 1))
      validate_skill "$file"
    fi
  done < "$tmp/markdown"
done

if [ "$skill_count" -eq 0 ]; then
  fail "No SKILL.md files found under $canonical"
fi

if [ "$errors" -gt 0 ]; then
  echo "" >&2
  echo "sync-agent-skills: $errors error(s)" >&2
  exit 1
fi

echo "sync-agent-skills: OK ($(wc -l < "$tmp/canonical" | tr -d ' ') files, mirror in sync)"
