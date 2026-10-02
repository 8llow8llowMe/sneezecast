import { ApiError } from '@/lib/api/api-error'
import {
  clearSession,
  getSessionSnapshot,
  type SessionSnapshot,
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
 *   (`startMemberInfo`) 같은 회원이면 다시 보내지 않는다. 다시 시도(`retryMemberInfo`)는 실패한 쪽만 다시 보낸다
 * - **두 요청은 따로 실패한다.** 내 동네는 행정동 서비스 장애면 `REGION_004`(503)이고 내 정보와 무관하다. 각각 `loading` ·
 *   `ready` · `failed` 다
 * - 내 정보가 `MEMBER_004`(토큰은 유효한데 회원 행이 없음 — 탈퇴 뒤 파기)면 세션을 비운다. 사유는 `withdrawn` 이다 —
 *   `expired` 는 "다시 로그인해 주세요" 로 보내지만 다시 로그인할 계정이 없고, `logout` 은 사용자가 고른 동작이 아니다.
 *   `withdrawn` 은 만료 알림 없이 비회원이 되고 같은 refresh 쿠키를 쓰는 다른 탭에도 알린다(세션 저장소 `clearSession`).
 *   탈퇴 · 정지(`MEMBER_002` · `003`)는 재발급과 같게 `expired` 로 끝낸다(`MEMBER_ENDED`)
 * - 내 동네를 저장하면(`saveRegion`) 응답을 바로 넣는다(`setMemberRegion`). 그보다 먼저 보낸 조회 응답이 늦게 와도 덮어쓰지 않는다
 *   — 가입 마무리는 로그인 직후(조회가 나간 사이) 동네를 저장한다
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

const LOADING: Load<never> = Object.freeze({ status: 'loading' })
const FAILED: Load<never> = Object.freeze({ status: 'failed' })

let snapshot: MemberInfoSnapshot | null = null
/** 내 정보 · 내 동네 요청의 차례. 회원이 바뀌거나 지울 때(둘 다) · 내 동네를 저장할 때(동네) 늘려 늦은 응답을 버린다 */
let infoSeq = 0
let regionSeq = 0
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

function loadInfo(memberId: string): void {
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
      patch(memberId, { info: FAILED })
    },
  )
}

function loadRegion(memberId: string): void {
  const sent = regionSeq
  fetchMyRegion().then(
    (region) => {
      if (regionSeq === sent) {
        patch(memberId, { region: Object.freeze({ status: 'ready', value: region }) })
      }
    },
    () => {
      if (regionSeq === sent) patch(memberId, { region: FAILED })
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
  sync()
  return unsubscribe
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
 * 내 동네를 저장한 결과를 넣는다(`saveRegion`). 저장을 보낸 회원(`memberId`)이 지금 저장소의 회원일 때만 넣고,
 * 그보다 먼저 보낸 조회 응답은 버린다
 */
export function setMemberRegion(memberId: string, region: MyRegion): void {
  if (snapshot?.memberId !== memberId) return
  regionSeq += 1
  patch(memberId, { region: Object.freeze({ status: 'ready', value: region }) })
}

/** 테스트 정리용. 저장소를 비우고 기다리던 응답을 버린다 — 구독은 건드리지 않는다 */
export function resetMemberInfoForTests(): void {
  infoSeq += 1
  regionSeq += 1
  publish(null)
}
