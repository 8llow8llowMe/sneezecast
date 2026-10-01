# API 계약 초안

디자인 핸드오프와 함께 받은 **프론트엔드 쪽 초안**이다. 백엔드와 맞추기 전이므로 확정 계약이 아니다.
연동 전에 게이트웨이 Swagger(`https://api-dev.sneezecast.com`)와 대조하고, 확정되면 이 문서를 실제 계약으로 고친다.

```text
GET  /api/v1/districts?query=○○동         → 행정동 검색 [{ code, name, sigungu }]
GET  /api/regions/{admCd}/weekly?week=     → { status: normal|slight|high|insufficient,
                                                participants, publicThreshold,
                                                symptomRate?, baselineRate?,   // insufficient면 없음
                                                groups: [{ key, trend, series: number[] }] }
POST /api/reports                          → { admCd, week, answer: none|symptom, groups: [] }
PUT  /api/reports/{week}                   → 같은 주 수정
DELETE /api/reports/{week}                 → 되돌리기(직후)·삭제
GET  /api/notices/{admCd}?week=            → 발행된 안내 | null (정정·철회 이력 포함)
GET  /api/official/latest                  → 질병관리청 단계·기준 주·요약·원문 링크
GET  /api/admin/candidates?week=           → 후보 목록 (운영자)
POST /api/admin/candidates/{id}/publish|hold|correct|withdraw
```

- 행정동 검색의 경로 · 필드 이름(`code` · `name` · `sigungu`)은 프론트 목(`features/region`) 기준이다. 확정은 백엔드 #60 과 맞춘다.
- 로그인한 회원만 보고한다(카카오 · 이메일, 백엔드 #56~#61). 보고 데이터에는 회원 식별자 대신 가명 키(`reporter_key`)만 남는다.
- `insufficient` 응답에는 `symptomRate` · `baselineRate` 가 없다. 프론트는 이 값이 없을 때 수치·상태색을 그리지 않는다.
- 백엔드 설계 문서: [`backend/docs/entity-design.md`](../../backend/docs/entity-design.md), [`backend/docs/architecture-guide.md`](../../backend/docs/architecture-guide.md)
