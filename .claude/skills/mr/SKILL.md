---
name: mr
description: "sneezecast Merge Request 본문을 한국어 템플릿으로 작성할 때 사용한다. /mr 또는 $mr 요청, MR body, merge request template, MR description, [BE]/[FE] feat: ... 제목 생성이 트리거다."
argument-hint: "[이슈 번호 또는 MR 요약]"
---

# MR Draft

sneezecast Merge Request 본문과 제목을 한국어 템플릿으로 작성한다. GitHub PR 이 아니라 GitLab 류 MR 본문이 필요할 때 쓴다 (GitHub PR 은 `/pr`).

## Workflow

1. 변경 범위를 확인한다. `git status`, `git diff --stat origin/develop...HEAD`, `git log --oneline origin/develop..HEAD`를 참고한다.
2. 제목은 `[영역] type: 요약` 형식으로 만든다. 예: `[BE] feat: 회원 가입 API 구현`, `[FE] feat: 메인 화면 레이아웃 구현`
3. 본문은 바로 복사 가능한 Markdown만 출력한다.
4. 실제 확인하지 않은 체크박스는 체크하지 않는다.
5. 이슈 번호가 없으면 `Issue Number: #`로 둔다.

## Template

```markdown
## 📝 작업 내용

[작업 내용을 2~4문장으로 요약]

### 주요 변경 사항

1. [주요 변경 1]
2. [주요 변경 2]
3. [주요 변경 3]

## 타입

- [ ] feat: 새로운 기능 추가
- [ ] fix: 버그 수정
- [ ] chore: 빌드 업무 수정, 패키지 매니저 수정
- [ ] refactor: 코드 리펙토링
- [ ] style: 코드 포맷팅, 세미콜론 누락, 코드 변경이 없는 경우
- [ ] docs: 문서 수정
- [ ] test: 테스트 코드, 리펙토링 테스트 코드 추가

## MR 하기 전에 확인해주세요

- [ ] 코딩 컨벤션을 지켰나요?
- [ ] local ci test를 진행하셨나요?
- [ ] 팀원들에게 공지하셨나요?

## 검증 내역

- [검증 명령 또는 확인 내용]

## 참고 사항

- [리뷰어가 알아야 할 점]

## 연관된 이슈

Issue Number: #
```

## Rules

- 한국어로 쓴다.
- 제목 prefix는 `[BE]` / `[FE]` / `[AI]` / `[DOCS]` / `[INFRA]` 중 변경 범위에 맞는 것을 쓴다.
  - 백엔드 런타임 코드 → `[BE]`
  - 프론트엔드 런타임 코드 → `[FE]`
  - AI·모델 서빙 코드 → `[AI]`
  - 문서만 → `[DOCS]` / 빌드·CI·GitHub·Claude 설정 → `[INFRA]`
- 기능 단위로 묶고 파일 나열식 changelog를 피한다.
- 검증 내역은 실제로 돌린 명령과 결과를 적는다.
- 검증 실패나 미실행은 숨기지 않는다.
- secret, token, private key, password는 포함하지 않는다.
