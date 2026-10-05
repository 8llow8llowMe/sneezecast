import { apiRequest } from '@/lib/api/client'

/* ── 회원 API (`/api/v1/members/me`, 실데이터) ─────────────────────────────────────────────
 *
 * 내 정보(`GET` · `PATCH /me`)와 내 동네(`GET` · `PUT /me/region`)를 부른다(docs/api-contract-draft.md "회원"). 모두 access 를 싣는다 —
 * 만료가 가까우면 API 계층이 먼저 재발급한다. 응답은 화면이 쓰는 필드만 골라 옮긴다.
 *
 * 내 동네는 `/me` 와 따로 읽는다. 백엔드가 이름 · 폐지 여부를 그때 행정동 서비스(surveillance)에서 읽어, 그 서비스가 멈추면
 * 내 동네만 `REGION_004`(503)이고 `/me` 는 그대로 온다(backend/docs/modules.md "내 동네").
 * 요청을 언제 보낼지 · 결과를 어디 둘지는 회원 정보 저장소(`member-info.ts`)와 `saveRegion` · `updateNickname`(`auth-client.ts`)이 정한다.
 */

export const MY_INFO_PATH = '/api/v1/members/me'
export const MY_REGION_PATH = '/api/v1/members/me/region'

/** `GET` · `PATCH /api/v1/members/me` 의 `dataBody` (backend `MemberMyInfoResponse`) */
type MyInfoResponse = {
  memberId: string
  email: string
  nickname: string
  /** `EMAIL` · `KAKAO` — 카카오를 연결한 이메일 계정도 `KAKAO` 다 */
  provider: string
  hasPassword: boolean
  role: string
  pendingConsents: string[]
  reportWritable: boolean
}

/** 내 정보(화면 모델). 이름 · 연락처 · 주소는 받지 않는다 */
export type MyInfo = Readonly<{
  memberId: string
  email: string
  nickname: string
  provider: 'email' | 'kakao'
  /** 이메일 + 비밀번호로도 로그인할 수 있는지 */
  hasPassword: boolean
  /** 다시 동의해야 하는 필수 항목(ConsentType 이름). 로그인 · 재발급 응답과 같은 계산이다 */
  pendingConsents: readonly string[]
}>

/** `GET` · `PUT /api/v1/members/me/region` 의 `dataBody` (backend `MemberRegionResponse`) */
type MyRegionResponse = {
  code: string
  name: string | null
  sigungu: string | null
  abolished: boolean
}

/**
 * 내 동네(화면 모델). 이름 · 시군구는 행정동 서비스에 코드가 없으면 null 이다(그때 `abolished` 는 true).
 * `abolished` 가 true 면 고른 뒤 폐지된 동네라 다시 골라야 한다(서버는 저장 값을 자동으로 바꾸지 않는다)
 */
export type MyRegion = Readonly<MyRegionResponse>

function toMyInfo(response: MyInfoResponse): MyInfo {
  return Object.freeze({
    memberId: response.memberId,
    email: response.email,
    nickname: response.nickname,
    // 백엔드는 DB 값이 없으면 EMAIL 로 준다. 모르는 값도 이메일로 본다
    provider: response.provider === 'KAKAO' ? 'kakao' : 'email',
    hasPassword: response.hasPassword,
    pendingConsents: Object.freeze([...response.pendingConsents]),
  })
}

function toMyRegion(response: MyRegionResponse): MyRegion {
  return Object.freeze({
    code: response.code,
    name: response.name,
    sigungu: response.sigungu,
    abolished: response.abolished,
  })
}

/** 내 정보. 실패는 거부한다(`MEMBER_004` 회원 없음 포함 — 저장소가 세션을 비운다) */
export async function fetchMyInfo(): Promise<MyInfo> {
  return toMyInfo(await apiRequest<MyInfoResponse>(MY_INFO_PATH))
}

/**
 * 닉네임 수정(`PATCH /me {nickname}`). 응답은 내 정보 조회와 같은 모양이다(서버가 앞뒤 공백을 지우고 저장한 값).
 * 오류 해석(검증 `MEMBER_101` · `102`)은 `updateNickname` 이 한다
 */
export async function patchMyNickname(nickname: string): Promise<MyInfo> {
  return toMyInfo(
    await apiRequest<MyInfoResponse>(MY_INFO_PATH, { method: 'PATCH', body: { nickname } }),
  )
}

/** 내 동네. 아직 고르지 않았으면 null 이다(200 + `dataBody: null`). 실패(`REGION_004` 503 등)는 거부한다 */
export async function fetchMyRegion(): Promise<MyRegion | null> {
  const region = await apiRequest<MyRegionResponse | null>(MY_REGION_PATH)
  return region ? toMyRegion(region) : null
}

/**
 * 내 동네 저장. **코드만 보낸다**(`{ code }` — GPS · 주소는 보내지 않는다). 응답은 서버가 방금 확인한 동네다(`abolished` 는 늘 false).
 * 오류 해석(없는 코드 · 폐지 · 경합 재시도)은 `saveRegion` 이 한다
 */
export async function putMyRegion(code: string): Promise<MyRegion> {
  return toMyRegion(
    await apiRequest<MyRegionResponse>(MY_REGION_PATH, { method: 'PUT', body: { code } }),
  )
}
