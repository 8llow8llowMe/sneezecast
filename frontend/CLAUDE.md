# sneezecast Frontend Guide

프론트엔드 워크스페이스 엔트리 문서다. 규칙의 정본은 `docs/*.md` 이고, 이 문서는 지도 역할만 한다. 새 규칙은 `docs/` 를 먼저 고친다.

## 먼저 읽는다

1. [docs/design-guide.md](docs/design-guide.md) — 범위, 상태 단계, 디자인·문구 규칙, 구현 순서
2. [docs/design/README.md](docs/design/README.md) — 시안(`.dc.html`) 읽는 법
3. [docs/design/SCREENS.md](docs/design/SCREENS.md) — 화면 ID ↔ 시안 파일 ↔ 라우트
4. [docs/design/tokens.json](docs/design/tokens.json) — 디자인 토큰 원본
5. [docs/api-contract-draft.md](docs/api-contract-draft.md) — API 계약 초안 (백엔드와 맞추기 전)

도메인 불변식(보고·집계·자료 부족·운영자 검토·개인정보)은 루트 [`CLAUDE.md`](../CLAUDE.md) 가 정본이다.

## 문서 우선순위

화면 명세서(`docs/screen-spec.md`, 받는 대로 추가) > 시안(`docs/design/screens/*.dc.html`) > `docs/design/tokens.json`.
화면을 구현할 때는 해당 시안을 열어 구조·간격·문구를 그대로 옮긴다.

## 자주 어기는 것

- 위치 권한을 요청하지 않는다. 이름·연락처·정확한 주소·GPS·자유 입력을 받지 않는다.
- `자료 부족`(참여 100명 미만)이면 증상 비율·상태색을 숨기고 참여 진행 막대만 보인다.
- 공식 정보(질병관리청)와 시민 자가보고를 한 UI 요소에 섞지 않는다.
- 토큰에 없는 색·그림자·그라데이션을 쓰지 않는다. 상태는 색 + 글자 라벨로 보인다.
- 아이 대리 보고·학부모 그룹(S09)은 만들지 않는다.
