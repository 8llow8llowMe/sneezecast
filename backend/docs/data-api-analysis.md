# 외부 데이터 원천 분석

> batch-service 가 적재하는 외부 원천의 형식 · 호출 조건 · 우리 테이블 매핑. 테이블 컬럼의 정본은 [entity-design.md §3](entity-design.md#3-surveillance--외부-원천-적재), 잡 · 주기는 [§4](entity-design.md#4-batch-service--적재-잡과-스케줄).

## 0. 검증 상태 (2026-09-30)

| 원천 | 명세 확인 | 실호출 | 남은 일 |
|------|-----------|--------|---------|
| SGIS 오픈API (§1) | 개발자 문서 | **안 함** — SGIS 는 공공데이터포털 키가 아니라 SGIS 개발지원센터의 `consumer_key` / `consumer_secret` 가 필요하다 | SGIS 키 발급 후 실호출 |
| 전수신고 감염병 발생현황 API (§2) | 포털 등록 swagger 로 엔드포인트 · 파라미터 · 응답 필드 확인 | **실패** — `SERVICE_KEY_IS_NOT_REGISTERED_ERROR`(30). 같은 키로 다른 API(TourAPI)는 정상 응답했으므로 키는 유효하고, **이 데이터셋의 활용신청이 안 된 상태**다 | 공공데이터포털에서 15139178 활용신청(자동승인) 후 실호출 |
| 표본감시 (§3) | 포털 화면 스크립트 | **성공** — 인플루엔자 · 급성호흡기 · 장관감염증 파일 다운로드와 화면 데이터 요청 모두 | 몇 주 적재하며 공표 요일 · 값 수정 빈도 관찰 |

- 인증키 · SGIS 키는 저장소에 두지 않는다. Vault `kv/sneezecast/batch` 에서 주입한다 (키 이름만 문서에 적는다).

## 1. SGIS 오픈API (통계청) — 행정동 마스터

- 기본 주소: `https://sgisapi.mods.go.kr/OpenAPI3/`
- 문서: [기본 API](https://sgis.kostat.go.kr/developer/html/newOpenApi/api/dataApi/basics.html), [행정구역 경계](https://sgis.kostat.go.kr/developer/html/newOpenApi/api/dataApi/addressBoundary.html)
- 필요한 설정: `SGIS_CONSUMER_KEY`, `SGIS_CONSUMER_SECRET`

| 오퍼레이션 | 요청 | 응답 (우리가 쓰는 필드) |
|------------|------|-------------------------|
| `auth/authentication.json` | `consumer_key`, `consumer_secret` | `accessToken`, `accessTimeout`(epoch 초, 만료 시각으로 보고 갱신) |
| `addr/stage.json` | `accessToken`, `cd`(생략 = 시도, 2자리 = 시군구, 5자리 = 읍면동), `pg_yn` | `cd`, `addr_name`, `full_addr`, `x_coor`, `y_coor` |
| `boundary/hadmarea.geojson` | `accessToken`, `year`(필수), `adm_cd`(생략 · 2 · 5 · 8자리), `low_search`(0 · 1 · 2) | `features[].properties{adm_cd, adm_nm, x, y}`, `geometry` |

- **코드 체계**: 시도 2 · 시군구 5 · 읍면동 **8자리** (예: 송파구 가락1동 `11240660`). 행안부 행정동 코드(10자리)와 번호 자체가 다르다 (송파구 SGIS `11240` ↔ 행안부 `11710`).
- **개편**: 경계 API 의 `year` 스냅샷으로만 드러난다. 신 · 구 코드 연계표 API 는 확인하지 못했다.
- **좌표계**: UTM-K (EPSG:5179). 프론트 정적 GeoJSON 은 4326 으로 변환해서 싣는다.
- `stage.json` 에는 기준 연도 파라미터가 없다 (현행 코드만). 마스터 적재는 `hadmarea` 의 `year` 를 기준으로 삼는다.

| 미확인 | 확인 방법 |
|--------|-----------|
| accessToken 유효 시간 | 실호출 |
| 경계 GeoJSON 의 좌표계 명시(`crs`) | 실호출 |
| 이용 한도 · 라이선스 | 개발지원센터 이용 약관 |

## 2. 질병관리청_전수신고 감염병 발생현황 (공공데이터포털 15139178)

- 엔드포인트: **`https://apis.data.go.kr/1790387/EIDAPIService/{오퍼레이션}`** (포털 등록 swagger 의 `host` 로 확인)
- REST, JSON · XML. 자동승인. 개발계정 트래픽 1,000 (운영계정은 활용사례 등록 후 증량 신청).
- **라이선스: 공공누리 제4유형 (출처표시 · 상업적 이용금지 · 변경금지)**
- 설정: `KDCA_API_SERVICE_KEY` (공공데이터포털 인증키, Decoding 값을 넣고 요청 시 URL 인코딩한다)
- 오류 응답은 HTTP 403 + XML `OpenAPI_ServiceResponse.cmmMsgHeader{errMsg, returnReasonCode}` 다. JSON 을 요청해도 오류는 XML 로 온다 → 파서는 본문이 `<` 로 시작하면 오류로 처리한다.

| 오퍼레이션 | 요청 (공통: `serviceKey`, `resType`=2, `pageNo`, `numOfRows`) | 응답 `body.items.item[]` | 1단계 |
|------------|------------------------------------------------------------------|---------------------------|-------|
| `/PeriodBasic` | `searchPeriodType`(1 연 · 2 월 · **3 주**), `searchStartYear`, `searchEndYear` | `period`, `icdGroupNm`, `icdNm`, `resultVal` | **사용** (주) |
| `/PeriodPtnt` | 〃 | + `ptntVal`, `dbtptntVal`, `pthgnHolderVal` | — |
| `/PeriodRegion` | 〃 | + `dmstcVal`, `outnatnVal` | — |
| `/Region` | `searchType`(1 발생 수 · 2 10만 명당), `searchYear`, `searchSidoCd` | `year`, `sidoCd`, `sidoNm`, `icdGroupNm`, `icdNm`, `resultVal` | **사용** (시도 · 연) |
| `/Age` | `searchType`, `searchYear` | `year`, `ageRange`, … | — |
| `/Gender` | `searchType`, `searchYear` | `year`, `sex`, … | — |
| `/Disease` | `searchType`, `searchYear`, `patntType`(1 전체 · 2 환자분류별) | `year`, `patntType`, … | — |
| `/death` | `searchStartYear`, `searchEndYear` | `year`, `icdGroupNm`, `icdNm`, `resultVal` | — |

- 응답 공통: `header{resultCode, resultMsg}`, `body{pageNo, numOfRows, totalCount}`. 전체를 받으려면 `totalCount` 까지 페이지를 넘긴다.
- **집계 단위의 한계**: 주 단위는 **전국**만, 시도는 **연 단위**만 있다. 행정동 · 시군구 공식 자료는 없다.
- **감염병 코드가 없다.** 이름(`icdNm`)이 `disease_key` 다.
- **시도 코드가 SGIS 와 다르다** (질병관리청 `01` 서울 ↔ SGIS `11`). 원천 코드를 그대로 저장한다.

| 미확인 (활용신청 후 확인) | 영향 |
|---------------------------|------|
| 주 단위 `period` 값 형식 (swagger 설명은 "년도" 뿐) | `period_year` · `period_week` 파싱 |
| `resultVal` 이 빈 값 · 문자열로 오는 경우 | `value` null 처리 |
| `numOfRows` 상한 | 실행당 호출 수 |

## 3. 표본감시 (감염병포털)

공식 오픈 API 가 없다. 공공데이터포털 **15053801 "질병관리청_법정감염병 표본감시 통계"** 는 파일을 올려 두지 않고 "기관자체에서 다운로드(제공데이터URL기재)" 로 감염병포털 화면을 가리킨다 (확장자 CSV, 업데이트 주기 "수시(자동 갱신)", 공공누리 제4유형). 포털 `robots.txt` 는 `/pot/` 경로를 막지 않는다.

- 화면: `https://dportal.kdca.go.kr/pot/is/st/{influ|ari|gstrnftn}.do`
- 조회 폼: `POST /pot/is/st/searchView.do` (`selectId`: influ 0 · gstrnftn 6 · ari 7)

### 3-1. 받는 방법 두 가지 (실호출로 확인)

| 방식 | 요청 | 응답 |
|------|------|------|
| **화면 데이터** | `POST /pot/is/st/{icdNm}ListAjax.do` (`X-Requested-With: XMLHttpRequest`) | JSON `value{headerList, captionList, data, paramMap}` — **열 제목이 들어 있다** |
| CSV 파일 | ① `POST /pot/is/st/csvFileDown.do` (헤더 `ABS_FILE_AJAX: Y`) → `value{status, filePath, …}` ② `POST /pot/is/fileCm/fileCmmnDownloadJob.do` (`filePath`, `downFileName`, `deleteYn`) → 파일 | **헤더 행이 없다** |

두 방식 모두 먼저 화면(`{icdNm}.do`)을 열어 세션 쿠키를 받은 뒤 요청한다.

### 3-2. 1단계는 화면 데이터(JSON)를 쓴다

CSV 를 받아 보니 자동 적재에 그대로 쓰기 어려웠다.

| 확인한 사실 | 영향 |
|-------------|------|
| **헤더 행이 없다** — 열이 어느 병원체 · 어느 주인지 파일만으로 알 수 없다 | 열 순서가 바뀌면 조용히 다른 병원체에 값이 들어간다 |
| **CSV 와 화면 값이 다르다** — 급성호흡기 2026년 37주: 화면 `계 1,520 … 코로나19 308`, CSV `계 1212 … (코로나19 빈 칸)`. CSV 는 코로나19 열이 비고 합계도 코로나19 를 뺀 값이다 | CSV 만 보면 합계 정의가 화면과 달라진다 |
| **인코딩이 파일마다 다르다** — 인플루엔자 CP949, 급성호흡기 · 장관 UTF-8 (둘 다 BOM 없음) | 인코딩 추정 로직이 필요하다 |
| 요청 두 번 + 임시 파일 경로가 매번 새로 생긴다 | 변경 감지 키가 없다 |

화면 데이터는 `captionList` 로 열을 스스로 설명하므로, **열 제목을 기대 목록과 대조해 형식 변경을 잡을 수 있다.** 그래서 적재(`channel = PORTAL_JSON`)는 화면 데이터로만 하고 **CSV 는 쓰지 않는다** — 포털 장애 때 CSV 를 수동으로 넣으면 합계 정의가 바뀌고 코로나19 값이 빈 칸으로 덮인다.

### 3-3. 프로그램별 형식

**인플루엔자 (`icdNm=influ`)** — 의사환자 분율 (외래 1,000명당)

- 요청: `startYear`, `endYear`(= 절기 시작 · 끝 연도), `age`(''=전체), `intoDivi=1`, `sido`(''=전국, 시도 코드 01~18 — 18 전남광주, 05 광주 · 13 전남은 "현재 미사용")
- **절기 단위**다: `startYear` 36주 ~ `endYear` 35주. 2025–2026 절기는 52열이었고 `headerList[].TITLE` 이 `36주` … `52주` · `01주` … `35주` 다. 53주가 있는 해는 열이 하나 늘 수 있다 (§5).
- 36 ~ 52(53)주 열은 `startYear`, 01 ~ 35주 열은 `endYear` 의 주다.
- 행은 **연령대별 7행**(0세 · 1-6세 · 7-12세 · 13-18세 · 19-49세 · 50-64세 · 65세 이상)이고, 연령 전체 합계 행은 없다.
- 시도 선택이 있다 — 시도 단위 적재는 1단계 범위 밖이다.

**급성호흡기감염증 (`icdNm=ari`)** · **장관감염증 (`icdNm=gstrnftn`)** — 병원체별 신고 수

- 요청: `startYear`, `startWeek`, `endYear`, `endWeek`, `dayCheck`(1 주별 · 3 연도별), `infectiousGubun`(''=전체), `subInfectious`(''=전체), `age`(''=전체)
- 행 = 주 (`TITLE`=연도, `SUBTITLE`=주차), 열 = `계` + 병원체.
- 병원체에는 **포털 코드**가 있다 (폼 `subInfectious` 값) → `disease_key`

| 프로그램 | 열 (화면 `captionList` 순서) |
|----------|-------------------------------|
| ari (12열) | 계 · 마이코플라즈마균(ND0708) · 클라미디아균(ND0709) · 아데노바이러스(ND0701) · 사람 보카바이러스(ND0702) · 파라인플루엔자바이러스(ND0703) · 호흡기세포융합바이러스(ND0704) · 리노바이러스(ND0705) · 사람 메타뉴모바이러스(ND0706) · 사람 코로나바이러스(ND0707) · 인플루엔자 바이러스(ND0001) · 코로나19 바이러스(ND0022) |
| gstrnftn (21열) | 계 · 세균 11종(ND0601~ND0611) · 바이러스 5종(그룹 A형 로타 ND0612 · 아스트로 ND0613 · 장내 아데노 ND0614 · 노로 ND0615 · 사포 ND0616) · 원충 4종(ND0617~ND0620) |

### 3-4. 파싱 규칙

- 값은 **천 단위 쉼표가 들어간 문자열**이다 (`"1,520"`). 쉼표를 지우고 숫자로 바꾼다.
- 화면 스크립트는 **음수와 빈 값을 `-` 로 표시한다** → 둘 다 결측으로 보고 `value = null`.
- `captionList` 가 설정의 기대 목록과 한 글자라도 다르면 `SENTINEL_SCHEMA_CHANGED` 로 실패한다. 원천 표기 오타(`인를루엔자`)도 있는 그대로 기대 목록에 넣는다.
- 모든 값은 **잠정 통계**다 (화면 안내: "매해 통계 자료는 변동 가능한 잠정통계입니다").

### 3-5. 호출 예절

- 짧은 시간에 요청을 연달아 보내자 연결이 타임아웃으로 끊겼다 (약 10여 건 뒤). 잠시 뒤에는 다시 됐다.
- 요청 간격 ≥ 3초, 실행당 요청 상한, 주 1회 실행을 지킨다. 실패 시 즉시 재시도하지 않고 다음 주기를 기다린다 (수동 재실행은 가능).
- 공표 시점: 2026-09-30(수) 기준 38주(09-13 ~ 09-19)까지 공개, 39주(09-20 ~ 09-26)는 아직이었다. 공표 요일은 관찰 후 확정한다.

## 4. 기타 확인한 원천

| 원천 | 판단 |
|------|------|
| 행정안전부_기간별감염병발생현황 (data.go.kr 15154063, 재난안전데이터공유플랫폼) | 질병관리청 기간별 자료의 재제공. 1차 원천(15139178)을 쓴다 |
| 질병관리청_최근 5년간 바이러스성 장관감염증 환자 현황 (15148439, XLSX) | 2020~2024 연령별 1회성 파일. 기준선 참고용으로만 검토 |

## 5. 주차 정의

- 우리 자가보고는 **ISO 주(월 ~ 일)** 다 ([architecture-guide.md §9](architecture-guide.md#9-처음부터-고정하는-계약)). 질병관리청 주차와 섞지 않는다 — 공식 자료는 `iso_week` 가 아니라 원천 연도 · 주차(`period_year` · `period_week`)와 날짜로 저장한다.
- 질병관리청 감시 주차는 **일요일 시작, 1월 1일이 들어 있는 주가 1주**다. 원문 정의는 찾지 못했고, 표본감시 데이터의 연도 경계로 확인했다 (2026-09-30).

| 연도 | 데이터의 마지막 주 | 이 규칙 | 참고: "1월 4일이 든 주가 1주" (MMWR 방식) |
|------|--------------------|---------|-------------------------------------------|
| 2020 | 52주 | 52 ✅ | 53 ✗ |
| 2022 | **53주** | 53 ✅ | 52 ✗ |
| 2025 | 52주 (다음 행이 2026년 1주) | 52 ✅ | 53 ✗ |

- 예: 2025년 1주 = 2024-12-29 ~ 2025-01-04, 2026년 1주 = 2025-12-28 ~ 2026-01-03, 2026년 38주 = 2026-09-13 ~ 09-19. 같은 기간의 ISO 주(2026-W38 = 09-14 ~ 09-20)와 하루 어긋난다.
- 53주가 있는 해가 있다 → `period_week` 는 1~53 을 받는다. 인플루엔자 절기 열 수도 52 가 아닐 수 있으므로 열 수가 아니라 `headerList` 로 주를 읽는다.
- 이 규칙은 `period_start` · `period_end` 계산에만 쓰고 UK 에는 들어가지 않는다. 전수신고 API 의 주 단위 `period` 도 같은 규칙인지는 활용신청 후 확인한다.
- 화면에는 공식 자료의 기준 기간을 **날짜로** 보인다 ("2026-09-13 ~ 09-19, 질병관리청 38주").
