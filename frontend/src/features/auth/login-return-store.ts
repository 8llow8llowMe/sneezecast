import { HOME_PATH, PRESET_REGION_PARAM } from '@/features/onboarding/paths'
import { navHref } from '@/lib/nav'

import { afterLoginHref, loginHref, type LoginReturn, NO_LOGIN_RETURN } from './login-return'
import { safeNextPath } from './required-steps'

/* ── 페이지를 새로 열어도 이을 돌아갈 곳 (#140) ───────────────────────────────────────────────────
 *
 * 로그인 방법 고르기 · 이메일 로그인 · 로그인 안내 시트가 받은 돌아갈 곳(`next` · 둘러보기 `region` · `intent=report`, `login-return.ts`)을
 * 가입(이메일 · 카카오) · 카카오 로그인 · 비밀번호 재설정을 거치는 동안 들고 간다. 이 흐름들은 단계마다 주소 쿼리를 넘기지 않고,
 * 카카오는 문서를 카카오 인가 화면으로 옮겼다 돌아와(콜백) 메모리(첫 진입 Provider)가 비기 때문이다.
 *
 * - **떠날 때 쓴다**(`saveLoginReturn`): 카카오로 계속하기(`useKakaoStart` — 로그인 방법 고르기 · 로그인 안내 시트 · 다른 카카오 계정),
 *   이메일로 가입하기 · 비밀번호를 잊었어요(로그인 방법 고르기 · 이메일 로그인). 돌아갈 곳이 없으면 지운다 — 앞서 그만둔 흐름의 값이 끼어들지 않게
 * - **마칠 때 읽고 지운다**(`takeLoginReturn`): 가입 마무리(S02-4), 카카오 로그인됨 · 계정 연결 성공, 비밀번호 재설정 성공.
 *   카카오 실패 · 가입된 이메일로 로그인처럼 로그인 화면으로 돌려보낼 때는 지우지 않고 그 주소에 쿼리로 다시 싣는다(`withSavedLoginReturn`) —
 *   로그인 화면은 주소 쿼리로 받고, 거기서 다시 떠나면 새로 쓴다
 * - 이메일 로그인에 성공하면 지운다(그 화면은 주소 쿼리로 받았다)
 *
 * **저장 위치**: 이 모듈 변수 + `sessionStorage`. 모듈 변수는 같은 문서 안 이동을 잇는다 — 첫 진입 레이아웃 밖(홈의 로그인 안내 시트)에서
 * 들어와도 남고, 저장소가 막혀도 된다. `sessionStorage` 는 새로고침 · 카카오 왕복을 잇는다. 그 탭에만 살고 탭을 닫으면 사라져 다른 탭 ·
 * 다음 방문에 새지 않고, 서버가 읽을 일이 없어 쿠키로 두지 않는다. 읽을 때는 모듈 변수(이 문서에서 마지막으로 쓴 값)가 먼저다.
 *
 * - 키 `sc_login_return`, 값 `{"v":1,"next":"/me","region":"11680640"|null,"intent":"report"|null,"savedAt":<ms>}`.
 *   **허용 목록 경로 · 행정동 코드 · `report` 만 담는다** — 이메일 · 토큰 · 건강 정보는 담지 않는다
 * - 수명 30분(`LOGIN_RETURN_TTL_MS`): 카카오 가입표 · 이메일 인증 표시의 서버 수명(30분)과 같다. 그보다 오래 걸린 가입은 어차피 처음부터 다시 한다.
 *   지난 값 · 앞으로의 시각은 버린다
 * - 읽을 때 다시 검증한다 — 저장소 값은 누구든 고칠 수 있다. `next` 는 `safeNextPath`(정확히 같은 허용 목록 경로, 아니면 홈), 동네는 8자리 숫자,
 *   `intent` 는 `report` 이고 돌아갈 곳이 홈일 때만. 모양 · 판이 다르면 통째로 버린다
 * - 저장소가 없거나 막히거나(사생활 보호 · 쿼터) 예외가 나면 모듈 변수만 쓴다. 그것도 없으면(새로고침 + 저장소 없음) 홈이다 — 흐름은 그대로 된다
 */

/** 저장 키. 테스트가 페이지를 새로 연 것(모듈 변수 없이 저장소만 있음)을 흉내 낼 때도 쓴다 */
export const LOGIN_RETURN_STORAGE_KEY = 'sc_login_return'
const STORAGE_KEY = LOGIN_RETURN_STORAGE_KEY
const VERSION = 1
/** 돌아갈 곳을 들고 있는 시간. 카카오 가입표 · 이메일 인증 표시의 서버 수명(30분)과 같다 */
export const LOGIN_RETURN_TTL_MS = 30 * 60 * 1000
/** 행정동 코드 모양(`features/region/region-client.ts` 와 같다). 다르면 동네를 버린다 */
const DISTRICT_CODE_PATTERN = /^\d{8}$/

type Saved = LoginReturn & { savedAt: number }

let memory: Saved | null = null

function sessionStore(): Storage | null {
  try {
    // eslint-disable-next-line no-restricted-globals -- 돌아갈 곳(허용 목록 경로 · 행정동 코드 · report)만 30분 둔다. 근거는 위 주석
    return typeof window === 'undefined' ? null : sessionStorage
  } catch {
    // 쿠키 · 사이트 데이터를 막은 브라우저는 읽기만 해도 SecurityError 를 던진다
    return null
  }
}

/** 저장한 값을 돌아갈 곳으로 검증한다. 모양 · 판이 다르거나 수명이 지났으면 null 이다 */
function validate(value: unknown, now: number): Saved | null {
  if (typeof value !== 'object' || value === null) return null
  const { v, next, region, intent, savedAt } = value as Record<string, unknown>
  if (v !== VERSION || typeof savedAt !== 'number' || !Number.isFinite(savedAt)) return null
  const age = now - savedAt
  if (age < 0 || age > LOGIN_RETURN_TTL_MS) return null
  const safeNext = safeNextPath(typeof next === 'string' ? next : null)
  return {
    next: safeNext,
    region: typeof region === 'string' && DISTRICT_CODE_PATTERN.test(region) ? region : null,
    intent: safeNext === HOME_PATH && intent === 'report' ? 'report' : null,
    savedAt,
  }
}

function parseJson(raw: string): unknown {
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

function readStored(now: number): Saved | null {
  const store = sessionStore()
  if (!store) return null
  try {
    const raw = store.getItem(STORAGE_KEY)
    if (raw === null) return null
    const saved = validate(parseJson(raw), now)
    // 지났거나 오염된 값은 남기지 않는다
    if (!saved) store.removeItem(STORAGE_KEY)
    return saved
  } catch {
    return null
  }
}

/** 담을 것이 있는 돌아갈 곳인지. 홈 · 동네 없음 · 보고 아님이면 지운다 */
const hasReturn = ({ next, region, intent }: LoginReturn) =>
  next !== HOME_PATH || region !== null || intent !== null

/**
 * 돌아갈 곳을 들고 떠난다(카카오 · 이메일 가입 · 비밀번호 재설정으로 갈 때). 들고 갈 것이 없으면 지운다.
 * 넣는 값도 같은 규칙으로 검증한다(목록 밖 경로는 홈, 모양이 다른 동네는 버림)
 */
export function saveLoginReturn(loginReturn: LoginReturn, now: number = Date.now()): void {
  const saved = validate({ v: VERSION, ...loginReturn, savedAt: now }, now)
  if (!saved || !hasReturn(saved)) {
    clearLoginReturn()
    return
  }
  memory = saved
  const store = sessionStore()
  if (!store) return
  const { next, region, intent } = saved
  try {
    store.setItem(STORAGE_KEY, JSON.stringify({ v: VERSION, next, region, intent, savedAt: now }))
  } catch {
    // 쿼터 등으로 쓰지 못했으면 앞선 값이 남지 않게 지운다. 이 문서 안에서는 모듈 변수로 잇는다
    try {
      store.removeItem(STORAGE_KEY)
    } catch {
      // 지우지도 못하면 읽을 때 모듈 변수가 먼저라 이 문서에서는 낡은 값을 쓰지 않는다
    }
  }
}

/** 들고 있는 돌아갈 곳. 지우지 않는다. 없으면 홈(`NO_LOGIN_RETURN`)이다 */
export function peekLoginReturn(now: number = Date.now()): LoginReturn {
  const saved = (memory && validate({ v: VERSION, ...memory }, now)) ?? readStored(now)
  if (!saved) return NO_LOGIN_RETURN
  const { next, region, intent } = saved
  return { next, region, intent }
}

/** 들고 있는 돌아갈 곳을 지운다 */
export function clearLoginReturn(): void {
  memory = null
  try {
    sessionStore()?.removeItem(STORAGE_KEY)
  } catch {
    // 저장소가 막혔으면 쓰지도 못했다
  }
}

/** 흐름을 마칠 때 들고 있던 돌아갈 곳을 읽고 지운다 */
export function takeLoginReturn(now: number = Date.now()): LoginReturn {
  const loginReturn = peekLoginReturn(now)
  clearLoginReturn()
  return loginReturn
}

/**
 * 로그인 화면으로 돌려보낼 주소에 들고 있던 돌아갈 곳을 쿼리로 싣는다(지우지 않는다). 카카오 실패(`/login?error=kakao-fail`) ·
 * 가입된 이메일로 로그인(`/login/email`)처럼 흐름을 마치지 못하고 로그인 화면으로 갈 때 쓴다 — 거기서 다시 시작해도 돌아갈 곳이 남는다
 */
export function withSavedLoginReturn(path: string): string {
  return loginHref(path, peekLoginReturn())
}

/**
 * 가입 동네 고르기(S02-1)로 갈 주소에 들고 있던 둘러보기 동네를 처음 선택으로 싣는다(#227, `?region=`, 지우지 않는다).
 * 이메일 가입(S13-4 → S02-1) · 카카오 콜백(가입 필요) · 목 카카오 시작이 쓴다. 동네가 없으면(저장소를 못 써 카카오 왕복에서 잃음 포함)
 * 주소를 그대로 둔다 — S02-1 은 지금처럼 빈 선택으로 시작한다. 코드는 모양만 확인된 값이라 서버 페이지가 행정동으로 다시 확인한다
 */
export function withSavedRegion(path: string): string {
  const { region } = peekLoginReturn()
  if (!region) return path
  return navHref(path, new URLSearchParams({ [PRESET_REGION_PARAM]: region }).toString())
}

/**
 * 카카오 로그인 · 계정 연결을 마친 뒤 갈 곳 (#167 · #140). 카카오로 떠나기 전에 둔 돌아갈 곳을 읽고 지운다 —
 * 보고하려던 로그인이면 같은 동네 홈의 보고 진입(`/?region=…&report=start`)이다. 없으면 홈이다.
 * 부르는 곳은 콜백(`LOGGED_IN`)과 계정 연결 확인(연결 성공)이다. 카카오 가입은 가입 마무리(S02-4)가 같은 값을 읽는다
 */
export function afterKakaoLoginPath(): string {
  return afterLoginHref(takeLoginReturn())
}
