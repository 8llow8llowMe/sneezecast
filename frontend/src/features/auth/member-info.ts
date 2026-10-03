import { ApiError } from '@/lib/api/api-error'
import { readBrowserDataSource } from '@/lib/data-source'
import {
  clearSession,
  getSessionSnapshot,
  type SessionSnapshot,
  subscribeMemberRegionChanged,
  subscribeSession,
} from '@/lib/session/session-store'

import { fetchMyInfo, fetchMyRegion, type MyInfo, type MyRegion } from './member-client'

/* ── 회원 정보 저장소 (실데이터 모드) ─────────────────────────────────────────────────────────────
 *
 * 세션 저장소(`src/lib/session/`)가 회원이 되면 내 정보(`GET /api/v1/members/me`)와 내 동네(`GET /api/v1/members/me/region`)를
 * 읽어 이 모듈 메모리에 둔다. 화면은 `use-member-info.ts` 의 훅으로 읽는다(목데이터 모드는 목 세션 프로필을 쓰고 이 저장소를 보지 않는다).
 *
 * - **회원이 바뀔 때만 읽는다.** 로그인 · 새로고침 복원 · 다른 탭 로그인으로 세션의 `memberId` 가 바뀌면 둘을 다시 읽고, 같은 회원의
 *   재발급(요약이 같거나 재동의 항목만 바뀜)은 다시 읽지 않는다. 비회원이 되면(로그아웃 · 만료 · 다른 탭 로그아웃) 바로 지운다 —
 *   다른 회원의 정보가 남지 않게 한다. 기다리던 응답이 그 뒤에 와도 버린다(`infoSeq` · `regionSeq`)
 * - **한 회원에 한 번만 보낸다(single-flight).** 요청은 `loading` 으로 바뀔 때만 나간다 — 세션 알림이 여러 번 와도, 다시 켜도
 *   (`startMemberInfo`) 같은 회원이면 다시 보내지 않는다. 다시 시도(`retryMemberInfo`)는 실패한 쪽만 다시 보낸다.
 *   예외는 보고가 동네로 거절됐을 때 · 다른 곳에서 바뀌었을 수 있을 때의 내 동네 다시 읽기(`reloadMemberRegion` · 다른 탭 알림, #165 · #190)와
 *   비밀번호 변경이 비밀번호 없는 계정으로 거절됐을 때의 내 정보 다시 읽기(`reloadMemberInfo`, #166)다
 * - **두 요청은 따로 실패한다.** 내 동네는 행정동 서비스 장애면 `REGION_004`(503)이고 내 정보와 무관하다. 각각 `loading` ·
 *   `ready` · `failed` 다
 * - 내 정보가 `MEMBER_004`(토큰은 유효한데 회원 행이 없음 — 탈퇴 뒤 파기)면 세션을 비운다. 사유는 `withdrawn` 이다 —
 *   `expired` 는 "다시 로그인해 주세요" 로 보내지만 다시 로그인할 계정이 없고, `logout` 은 사용자가 고른 동작이 아니다.
 *   `withdrawn` 은 만료 알림 없이 비회원이 되고 같은 refresh 쿠키를 쓰는 다른 탭에도 알린다(세션 저장소 `clearSession`).
 *   탈퇴 · 정지(`MEMBER_002` · `003`)는 재발급과 같게 `expired` 로 끝낸다(`MEMBER_ENDED`)
 * - 내 동네를 저장하면(`saveRegion`) 응답을 바로 넣는다(`setMemberRegion`). 그보다 먼저 보낸 조회 응답이 늦게 와도 덮어쓰지 않는다
 *   — 가입 마무리는 로그인 직후(조회가 나간 사이) 동네를 저장한다
 * - **내 동네가 다른 곳에서 바뀌었을 수 있으면 다시 읽는다(#190).** 보고는 이 탭이 들고 있는 내 동네 코드로 나가므로, 낡은 값이면
 *   사용자가 고르지 않은 동네의 집계에 들어간다. 그래서
 *   - 같은 브라우저의 다른 탭이 저장하면 탭 사이 알림(`region-changed`, 세션 저장소가 같은 회원일 때만 넘긴다)을 받아 `loading` 으로
 *     다시 읽는다. 지금 값이 낡았음이 확정이라 두지 않는다 — 읽는 동안 · 실패(`failed`)면 보고 흐름이 보내기를 막는다.
 *     처음 읽는 중이면 그 조회가 저장보다 먼저 나갔을 수 있어 새로 보낸다(앞 응답은 버린다)
 *   - 다른 기기에서 바꾼 경우는 화면이 다시 보일 때(`visibilitychange` → visible · `focus`) 실데이터 모드면 다시 읽는다. 마지막으로
 *     내 동네를 읽은(조회를 보낸 · 저장 응답을 넣은) 때부터 `REGION_REFRESH_INTERVAL_MS`(60초)가 지났을 때만이다 — 탭을 오가며
 *     매번 요청하지 않게 한다. `reloadMemberRegion` 규칙을 따른다(낡았는지 모르니 읽는 동안 · 실패해도 지금 값을 둔다, 마지막 응답만 넣는다)
 *   - 같은 회원이 두 탭에서 거의 동시에 저장하면 늦게 응답받은 탭이 서버 값과 잠깐 어긋날 수 있다(자기 저장 응답이 상대의 알림보다
 *     늦게 오면 그 응답이 다시 읽기를 덮는다). 화면이 다시 보일 때(60초 제한) 바로잡힌다
 *
 * 브라우저 저장소 · 주소 · 로그에 남기지 않는다(새로고침하면 다시 읽는다).
 */

export type LoadStatus = 'loading' | 'ready' | 'failed'

export type Load<T> = { status: 'loading' } | { status: 'ready'; value: T } | { status: 'failed' }

export type MemberInfoSnapshot = Readonly<{
  /** 이 값을 읽은 회원. 세션의 `memberId` 와 다르면(바뀌는 중) 쓰지 않는다 */
  memberId: string
  info: Load<MyInfo>
  /** 내 동네. 아직 고르지 않았으면 `ready` 에 null 이다 */
  region: Load<MyRegion | null>
}>

/** 회원 행이 없다(파기됨). 화면은 로그아웃 상태로 바꾼다(backend `MemberErrorCode.MEMBER_NOT_FOUND`) */
const MEMBER_NOT_FOUND = 'MEMBER_004'

/**
 * 탈퇴(`MEMBER_002`) · 정지(`MEMBER_003`, 403). 세션 저장소의 재발급이 같은 코드를 세션 종료로 보고(`endsSession`)
 * `clearSession('expired')` 로 끝내므로 같은 사유로 맞춘다 — 이 탭에 세션이 있었으면 로그인 화면(만료 안내)으로 가고 다른 탭에도 알린다
 */
const MEMBER_ENDED: ReadonlySet<string> = new Set(['MEMBER_002', 'MEMBER_003'])

/** 화면이 다시 보일 때 내 동네를 다시 읽는 최소 간격(#190) */
export const REGION_REFRESH_INTERVAL_MS = 60_000

const LOADING: Load<never> = Object.freeze({ status: 'loading' })
const FAILED: Load<never> = Object.freeze({ status: 'failed' })

let snapshot: MemberInfoSnapshot | null = null
/** 내 정보 · 내 동네 요청의 차례. 회원이 바뀌거나 지울 때(둘 다) · 내 동네를 저장할 때(동네) 늘려 늦은 응답을 버린다 */
let infoSeq = 0
let regionSeq = 0
/** 마지막으로 내 동네를 읽은 때(조회를 보낸 · 저장 응답을 넣은 `Date.now()`). 화면이 다시 보일 때의 간격을 잰다 */
let regionReadAt = 0
const listeners = new Set<() => void>()

function publish(next: MemberInfoSnapshot | null): void {
  if (next === snapshot) return
  snapshot = next
  listeners.forEach((listener) => listener())
}

function patch(memberId: string, change: Partial<Omit<MemberInfoSnapshot, 'memberId'>>): void {
  if (snapshot?.memberId !== memberId) return
  publish(Object.freeze({ ...snapshot, ...change }))
}

/** 지금 저장소 값. 바뀔 때만 새 객체다(`useSyncExternalStore` 의 getSnapshot). 비회원 · 시작 전이면 null */
export function getMemberInfoSnapshot(): MemberInfoSnapshot | null {
  return snapshot
}

export function subscribeMemberInfo(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** 이 세션의 회원 정보. 세션이 회원이 아니거나 저장소가 다른(바뀌기 전) 회원 것이면 null 이다 */
export function memberInfoOf(
  session: SessionSnapshot,
  info: MemberInfoSnapshot | null,
): MemberInfoSnapshot | null {
  return session.status === 'member' && info?.memberId === session.summary.memberId ? info : null
}

/** 내 정보를 읽는다. `keepOnFailure` 면(다시 읽기) 실패해도 지금 값을 그대로 둔다(세션을 끝내는 오류는 그대로 끝낸다) */
function loadInfo(memberId: string, keepOnFailure = false): void {
  const sent = infoSeq
  fetchMyInfo().then(
    (info) => {
      if (infoSeq === sent)
        patch(memberId, { info: Object.freeze({ status: 'ready', value: info }) })
    },
    (error: unknown) => {
      if (infoSeq !== sent) return
      const code = error instanceof ApiError ? error.code : null
      if (code === MEMBER_NOT_FOUND || (code !== null && MEMBER_ENDED.has(code))) {
        // 세션을 비우면 세션 알림(sync)이 저장소도 지운다. 알림을 받기 전(시작 전)이어도 지우게 한 번 더 맞춘다
        if (getSessionSnapshot().status === 'member') {
          clearSession(code === MEMBER_NOT_FOUND ? 'withdrawn' : 'expired')
        }
        sync()
        return
      }
      if (!keepOnFailure) patch(memberId, { info: FAILED })
    },
  )
}

/** 내 동네를 읽는다. `keepOnFailure` 면(다시 읽기) 실패해도 지금 값을 그대로 둔다 */
function loadRegion(memberId: string, keepOnFailure = false): void {
  const sent = regionSeq
  regionReadAt = Date.now()
  fetchMyRegion().then(
    (region) => {
      if (regionSeq === sent) {
        patch(memberId, { region: Object.freeze({ status: 'ready', value: region }) })
      }
    },
    () => {
      if (regionSeq === sent && !keepOnFailure) patch(memberId, { region: FAILED })
    },
  )
}

/** 세션에 맞춘다. 회원이 바뀌었으면 둘을 다시 읽고, 비회원이 되면 지운다 */
function sync(): void {
  const session = getSessionSnapshot()
  if (session.status !== 'member') {
    if (snapshot === null) return
    infoSeq += 1
    regionSeq += 1
    publish(null)
    return
  }
  const { memberId } = session.summary
  if (snapshot?.memberId === memberId) return
  infoSeq += 1
  regionSeq += 1
  publish(Object.freeze({ memberId, info: LOADING, region: LOADING }))
  loadInfo(memberId)
  loadRegion(memberId)
}

/**
 * 세션 알림을 받기 시작한다(지금 세션에도 바로 맞춘다). 돌려준 함수로 그만 받는다 — 읽은 값은 지우지 않는다.
 * 루트 레이아웃의 `SessionBootstrap` 이 부른다. 여러 번 켜도 같은 회원이면 다시 읽지 않는다
 */
export function startMemberInfo(): () => void {
  const unsubscribe = subscribeSession(sync)
  const unsubscribeRegion = subscribeMemberRegionChanged(onRegionChangedElsewhere)
  window.addEventListener('focus', refreshRegionIfStale)
  document.addEventListener('visibilitychange', onVisibilityChange)
  sync()
  return () => {
    unsubscribe()
    unsubscribeRegion()
    window.removeEventListener('focus', refreshRegionIfStale)
    document.removeEventListener('visibilitychange', onVisibilityChange)
  }
}

/**
 * 다른 탭이 같은 회원의 내 동네를 저장했다(#190). 지금 값이 **낡았음이 확정**이므로 `reloadMemberRegion` 과 달리 지금 값을 두지 않는다 —
 * `loading` 으로 바꿔 다시 읽고, 실패하면 `failed` 다. 그동안 보고 흐름은 보내기를 막고(읽는 중 · 실패 안내, `report-flow.tsx`
 * `regionReady`) 옛 동네로 보내지 않는다. 실패하면 다시 시도(`retryMemberInfo`)로 다시 읽는다.
 * 처음 읽는 중이어도 새로 보내고 앞 응답은 버린다 — 그 조회가 다른 탭의 저장보다 먼저 나갔을 수 있다(가입 마무리: 로그인 소식으로
 * 이 탭이 읽기 시작한 사이 다른 탭이 동네를 저장한다). 알림은 저장 응답 뒤에 오므로 새 조회는 저장된 값을 읽는다
 */
function onRegionChangedElsewhere(): void {
  const current = snapshot
  if (!current) return
  patch(current.memberId, { region: LOADING })
  regionSeq += 1
  loadRegion(current.memberId)
}

/**
 * 화면이 다시 보일 때 내 동네가 낡았을 수 있으면 다시 읽는다(다른 기기에서 바꾼 경우, #190). 실데이터 모드의 회원이고 마지막으로
 * 읽은 지 `REGION_REFRESH_INTERVAL_MS` 가 지났을 때만이다. 목데이터 모드 · 비회원 · 처음 읽는 중이면 아무 일도 없다
 */
function refreshRegionIfStale(): void {
  const current = memberInfoOf(getSessionSnapshot(), snapshot)
  if (!current || current.region.status === 'loading') return
  if (Date.now() - regionReadAt < REGION_REFRESH_INTERVAL_MS) return
  // 출처 토글은 세션을 지우지 않는다 — 목데이터로 바꾼 탭에서는 요청하지 않는다(쿠키를 직접 읽는다, SessionBootstrap 과 같다)
  if (readBrowserDataSource() !== 'api') return
  reloadMemberRegion()
}

function onVisibilityChange(): void {
  if (document.visibilityState === 'visible') refreshRegionIfStale()
}

/** 실패한 쪽(내 정보 · 내 동네)만 다시 읽는다. 읽는 중이거나 이미 읽었으면 아무 일도 없다 */
export function retryMemberInfo(): void {
  const current = snapshot
  if (!current) return
  const { memberId } = current
  const infoFailed = current.info.status === 'failed'
  const regionFailed = current.region.status === 'failed'
  if (!infoFailed && !regionFailed) return
  patch(memberId, {
    ...(infoFailed ? { info: LOADING } : {}),
    ...(regionFailed ? { region: LOADING } : {}),
  })
  if (infoFailed) {
    infoSeq += 1
    loadInfo(memberId)
  }
  if (regionFailed) {
    regionSeq += 1
    loadRegion(memberId)
  }
}

/**
 * 이미 읽은 내 동네를 서버에서 다시 읽는다. 보고가 폐지 · 없는 동네로 거절됐을 때(`REPORT_003` · `002`, #165) 부른다 — 고른 뒤
 * 폐지됐거나 다른 탭에서 바꿔 이 탭 값이 낡았을 수 있다. 화면이 다시 보일 때(#190)도 이 함수로 다시 읽는다(다른 탭의 저장 알림은
 * 낡았음이 확정이라 `loading` 으로 다시 읽는다 — `onRegionChangedElsewhere`). 다시 읽은 값이 폐지(`abolished`)면 회원 조건(`sessionRequirements`)이
 * 동네 다시 고르기로 보낸다.
 *
 * - 읽는 동안 지금 값을 그대로 둔다(`loading` 으로 바꾸지 않는다) — 머리줄 동네 이름 · 회원 조건이 깜빡이지 않게 한다.
 *   다시 읽지 못해도 지금 값을 둔다(더 나은 값을 모른다)
 * - 여러 번 부르면 마지막 요청의 응답만 넣는다(늦은 응답은 버린다)
 * - 처음 읽는 중이면 그 응답을 기다린다(보내지 않는다). 읽지 못한 상태면 `retryMemberInfo` 처럼 `loading` 으로 다시 읽는다
 */
export function reloadMemberRegion(): void {
  const current = snapshot
  if (!current || current.region.status === 'loading') return
  const { memberId } = current
  const failed = current.region.status === 'failed'
  if (failed) patch(memberId, { region: LOADING })
  regionSeq += 1
  loadRegion(memberId, !failed)
}

/**
 * 이미 읽은 내 정보를 서버에서 다시 읽는다. 비밀번호 변경이 `MEMBER_007`(비밀번호 없는 계정)로 거절됐을 때(#166) 부른다 —
 * 읽은 `hasPassword` 가 서버와 어긋났다. `reloadMemberRegion` 과 같은 규칙이다: 읽는 동안 · 다시 읽지 못해도 지금 값을 둔다,
 * 마지막 요청의 응답만 넣는다, 처음 읽는 중이면 보내지 않는다, 읽지 못한 상태면 `loading` 으로 다시 읽는다.
 * 회원 없음 · 탈퇴 · 정지면 처음 읽을 때처럼 세션을 끝낸다
 */
export function reloadMemberInfo(): void {
  const current = snapshot
  if (!current || current.info.status === 'loading') return
  const { memberId } = current
  const failed = current.info.status === 'failed'
  if (failed) patch(memberId, { info: LOADING })
  infoSeq += 1
  loadInfo(memberId, !failed)
}

/**
 * 내 동네를 저장한 결과를 넣는다(`saveRegion`). 저장을 보낸 회원(`memberId`)이 지금 저장소의 회원일 때만 넣고,
 * 그보다 먼저 보낸 조회 응답은 버린다
 */
export function setMemberRegion(memberId: string, region: MyRegion): void {
  if (snapshot?.memberId !== memberId) return
  regionSeq += 1
  regionReadAt = Date.now()
  patch(memberId, { region: Object.freeze({ status: 'ready', value: region }) })
}

/** 테스트 정리용. 저장소를 비우고 기다리던 응답을 버린다 — 구독은 건드리지 않는다 */
export function resetMemberInfoForTests(): void {
  infoSeq += 1
  regionSeq += 1
  regionReadAt = 0
  publish(null)
}
