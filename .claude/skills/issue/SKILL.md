---
name: issue
description: "sneezecast GitHub 이슈 초안을 한국어 기능/버그 이슈 템플릿으로 작성할 때 사용한다. /issue 또는 $issue 요청, issue template, feature issue, bug issue draft, [BE]/[FE] feat: ... 제목 생성이 트리거다."
argument-hint: "[이슈로 만들 기능 또는 버그 설명]"
---

# Issue Draft

sneezecast 이슈 초안을 한국어 템플릿으로 작성한다. 템플릿 정본은 `.github/ISSUE_TEMPLATE/` 이다.

이슈는 **화면/기능 단위**로 쪼갠다. "명세 작성"처럼 단계로 쪼개지 않고, 명세·구현·테스트를 한 이슈에 묶는다.
하나의 이슈가 하나의 PR 이 되며 **30파일 이내**를 목표로 한다.

이슈를 만든 뒤 브랜치명을 함께 제안한다: `<type>/<영역>/<이슈번호>-<요약>`

| 자리 | 값 |
|------|-----|
| `type` | `feature` \| `fix` \| `chore` \| `refactor` \| `docs` \| `test` \| `style` |
| `영역` | `fe` \| `be` \| `ai` \| `infra` \| `common` (여러 영역에 걸치면 `common`) |
| `요약` | 영문 kebab-case, 2~4단어 |

## Workflow

1. 이슈 종류를 판단한다. 기본은 기능 이슈이고, 재현·기대 동작이 있는 결함이면 버그 이슈다.
2. 제목은 `[영역] type: 요약` 형식으로 만든다.
3. 본문은 GitHub issue body에 바로 붙여넣을 수 있는 Markdown만 출력한다.
4. GitHub issue template 파일을 만들라는 요청이 아니면 YAML frontmatter는 포함하지 않는다.
5. 할 일은 체크박스 형태로 2~6개 작성한다.
6. 사용자가 이슈 생성까지 요청하면 `gh issue create --title "<제목>" --body-file <본문> --assignee @me` 로 만든다.

## Feature Body Template

```markdown
## 어떤 기능인가요? ✏

- [추가하려는 기능을 간결하게 설명]

## 작업 상세 내용 📝

- [ ] [해야 할 일]
- [ ] [해야 할 일]
- [ ] [해야 할 일]

## 참고할만한 자료(선택)

- [참고 자료 또는 관련 맥락]
```

## Bug Body Template

```markdown
## 어떤 버그인가요? 🐞

- [발생한 버그를 간결하게 설명]

## 재현 방법 📝

1. [재현 절차]

## 예상 동작 / 실제 동작

- 예상: [정상적으로 기대한 동작]
- 실제: [실제로 발생한 동작]

## 환경(선택)

- [브라우저/OS/버전 등]

## 참고할만한 자료(선택)

- [로그, 스크린샷, 관련 이슈]
```

## GitHub Issue Template File

사용자가 `.github/ISSUE_TEMPLATE`용 템플릿 파일을 요청하면 `.github/ISSUE_TEMPLATE/feature-issue.md` / `bug-issue.md` 와 같은 frontmatter 형식을 사용한다.

```markdown
---
name: 기능 이슈 생성 템플릿
about: 해당 기능 이슈 생성 템플릿을 사용하여 기능 관련 이슈를 생성해주세요.
title: ''
labels: ''
assignees: ''
---
```

## Rules

- 한국어로 쓴다.
- 범위가 크면 기능을 나누는 제안을 먼저 한다.
- 제목은 `[BE] feat: ...`, `[FE] feat: ...`, `[AI] feat: ...`, `[INFRA] chore: ...`, `[DOCS] docs: ...` 형식을 우선한다.
- secret, token, private key, password는 포함하지 않는다.
