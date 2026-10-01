#!/usr/bin/env sh
#
# 변경 파일 목록을 보고 프론트엔드 검사를 얼마나 돌릴지 정한다.
#
# **판정 기준은 이 파일 한 곳에 둔다.** `.github/workflows/frontend-ci.yml` 의 `changes` 잡이 쓰고,
# 로컬 pre-push 훅을 만들게 되면 그것도 이 스크립트를 쓴다. 기준이 두 곳에 있으면 한쪽만 고쳐진다.
#
# 입력(stdin): 저장소 루트 기준 경로, 한 줄에 하나
# 출력(stdout): 셋 중 하나
#   code — 아래 docs 가 아닌 `frontend/` 파일이나 frontend-ci 워크플로가 바뀌었다 → 전부 돈다
#   docs — `frontend/docs/**` 와 그 밖의 `frontend/**/*.md` 만 바뀌었다 → `format:check` 만 돈다
#   none — 프론트엔드와 무관하다
#
# **`frontend/docs/design/tokens.json` 은 문서 폴더에 있지만 `code` 다.**
# `src/styles/token-sync.test.ts` 가 이 파일을 읽어 tokens.css 와 대조한다. 시안 토큰만 고친 PR 이
# 테스트를 건너뛰면 둘이 어긋난 채 머지된다. 테스트가 읽는 문서가 늘면 여기에 더한다.
#
# **문서만 바뀌어도 `format:check` 는 남긴다.** `.md` 도 prettier 대상이다.
#
# 실행:
#   git -c core.quotepath=off diff --name-only origin/develop...HEAD | sh scripts/classify-frontend-changes.sh
#
# **`core.quotepath=off` 를 꼭 준다.** 기본값이면 git 이 한글 경로를 따옴표로 감싸 내보내
# 아래 패턴에 걸리지 않는다. 그렇게 들어온 경로는 무엇인지 모르므로 `code` 로 본다.

set -e

kind=none
# 마지막 줄에 줄바꿈이 없어도 읽는다 — `printf %s` 로 넘기면 끝 줄이 버려진다
while IFS= read -r path || [ -n "$path" ]; do
  case "$path" in
    .github/workflows/frontend-ci.yml | scripts/classify-frontend-changes.sh | frontend/docs/design/tokens.json)
      kind=code
      ;;
    frontend/docs/* | frontend/*.md)
      [ "$kind" = none ] && kind=docs
      ;;
    frontend/* | \"frontend/*)
      kind=code
      ;;
  esac
done

echo "$kind"
