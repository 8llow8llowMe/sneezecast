'use client'

import { useEffect, useRef } from 'react'

import { useOnboarding } from '@/features/onboarding/onboarding-context'
import { OnboardingLayout } from '@/features/onboarding/onboarding-layout'
import { KAKAO_LINK_PATH, SETUP_REGION_FROM_KAKAO_PATH } from '@/features/onboarding/paths'
import { readBrowserDataSource } from '@/lib/data-source'
import { restoreSession } from '@/lib/session/session-store'
import { useActiveRef } from '@/lib/use-active-ref'

import { kakaoLogin, type KakaoLoginResult } from './kakao-client'
import { kakaoFailPath } from './login-notice'
import { afterKakaoLoginPath, withSavedLoginReturn } from './login-return-store'

/**
 * 콜백 값이 든 부분. proxy 가 카카오가 붙인 쿼리를 fragment 로 옮겨 303 으로 다시 열게 하므로(`kakao-callback-redirect.ts`, #176)
 * **hash 가 먼저**다. hash 가 비었으면(proxy 를 거치지 않음) 쿼리를 읽는다. 앞의 `#` · `?` 는 뗀다
 */
export function kakaoCallbackParams({ search, hash }: { search: string; hash: string }): string {
  const fragment = hash.replace(/^#/, '')
  return fragment !== '' ? fragment : search.replace(/^\?/, '')
}

/**
 * 카카오가 붙여 보낸 콜백 값(`code=…&state=…`, 앞의 `?` 는 있어도 된다)에서 로그인에 쓸 값을 읽는다. 카카오가 `error`
 * (사용자 취소 `access_denied` 등)를 붙였거나 `code` · `state` 중 하나라도 없으면 null 이다 — 보내지 않고 실패로 본다
 */
export function readKakaoCallback(value: string): { code: string; state: string } | null {
  const params = new URLSearchParams(value)
  if (params.has('error')) return null
  const code = params.get('code')
  const state = params.get('state')
  return code && state ? { code, state } : null
}

/** 콜백 결과 → 보낼 곳 */
function targetOf(result: KakaoLoginResult): string {
  switch (result.status) {
    case 'logged-in':
      return afterKakaoLoginPath()
    case 'signup-required':
      return SETUP_REGION_FROM_KAKAO_PATH
    case 'link-required':
      return KAKAO_LINK_PATH
    case 'failed':
      return withSavedLoginReturn(kakaoFailPath(result.reason))
  }
}

/**
 * 카카오 콜백 (`/login/kakao/callback`, #167). 카카오 인가 화면이 `code` · `state`(또는 `error`)를 쿼리로 붙여 이 주소로 돌아오고,
 * proxy 가 그 쿼리를 fragment 로 옮겨 303 으로 다시 열게 한다(#176 — 서버가 그린 HTML 에 인가 코드가 실리지 않게). 화면은 hash 를 먼저 읽는다.
 *
 * 1. **값을 읽자마자 주소에서 지운다**(`history.replaceState`) — 인가 코드가 방문 기록 · 다음 화면의 리퍼러 · 공유 주소에 남지 않게.
 *    마운트 effect 에서 바로 바꾸면 하이드레이션 첫 커밋이라 Next 가 모르므로 `setTimeout(0)` 으로 미룬다(docs/conventions.md).
 *    지운 뒤에 요청 · 이동을 시작한다 — 라우터 이동이 대기 중일 때 history 를 바꾸면 Next 가 그 이동을 버린다
 * 2. `POST /api/v1/auth/kakao/login` 을 **한 번만** 보낸다. state 는 일회용이라 두 번 보내면 두 번째가 `AUTH_020` 이다.
 *    StrictMode 의 effect 두 번 실행은 타이머를 정리에서 취소해 한 번이 되고, 이 화면 안에서는 ref 로 한 번 더 막는다.
 *    새로고침은 주소에서 이미 지운 뒤라 값이 없어 실패로 끝난다(code 를 다시 쓰지 않는다)
 * 3. 결과로 기록을 바꿔 간다(이 주소가 기록에 남지 않게): 로그인됨 → 카카오로 떠나기 전에 둔 돌아갈 곳(`afterKakaoLoginPath` — 읽고 지운다,
 *    없으면 홈, #140) · 가입 필요 → 동네 선택 `?from=kakao`(S02-1 → S02-3, S02-1 이 가입 종류를 카카오로 둔다 — 돌아갈 곳은 가입 마무리까지
 *    그대로 둔다) · 연결 필요 → 계정 연결 확인(가린 이메일은 첫 진입 Provider 메모리로만 넘긴다) · 실패 · 취소 · 값 없음 →
 *    `/login?error=kakao-fail`(사유가 있으면 `&kakao=`, 둔 돌아갈 곳을 쿼리로 다시 싣는다 — `withSavedLoginReturn`)
 *
 * **실데이터면 새로고침 복원(`restoreSession`)이 끝난 뒤에 보낸다.** 콜백은 문서를 새로 연 직후라 세션 힌트가 있으면
 * `SessionBootstrap` 이 재발급을 보내 둔다. 카카오 로그인과 동시에 나가면 늦게 온 재발급 응답의 refresh `Set-Cookie` 가 카카오 로그인이 심은
 * refresh 쿠키를 덮어(다른 계정의 refresh) 다음 재발급에서 계정이 뒤섞일 수 있다. `restoreSession` 은 진행 중인 복원을 기다리기만 하고
 * (여러 번 불러도 한 번) 실패를 던지지 않는다 — 혹시 던져도 기다림만 끝내고 로그인을 보낸다.
 *
 * 출처는 훅 값이 아니라 **쿠키를 직접 읽는다**(`readBrowserDataSource`) — 이 화면은 문서를 새로 연 직후라 하이드레이션 커밋의
 * `useDataSource()` 가 서버 기본값이다(`SessionBootstrap` 과 같은 이유). 회원이 닿아도 첫 진입 가드는 이 화면을 보내지 않는다(`GUEST_ONLY_PATHS` 밖).
 * 응답 전에 화면을 떠나면 이동하지 않는다. 로그인 세션은 그래도 넣는다(`kakaoLogin` — 서버에는 이미 세션이 생겼다).
 *
 * 시안 없음 — 기다리는 동안 짧은 안내만 보인다(docs/design/SCREENS.md "카카오 로그인").
 */
export function KakaoCallbackScreen() {
  const { replace, setKakaoLinkEmail } = useOnboarding()
  const active = useActiveRef()
  const started = useRef(false)

  useEffect(() => {
    const timer = setTimeout(() => {
      if (started.current) return
      started.current = true
      const { pathname, search, hash } = window.location
      const callback = readKakaoCallback(kakaoCallbackParams({ search, hash }))
      // Next 가 자기 기록 상태를 덧붙이고 주소를 따라가게 state 는 null 로 넘긴다
      if (search || hash) window.history.replaceState(null, '', pathname)
      if (!callback) {
        replace(withSavedLoginReturn(kakaoFailPath(null)))
        return
      }
      const source = readBrowserDataSource()
      const restored =
        source === 'api' ? restoreSession().catch(() => undefined) : Promise.resolve()
      restored
        .then(() => kakaoLogin(callback.code, callback.state, source))
        .then(
          (result) => {
            if (!active.current) return
            if (result.status === 'link-required') setKakaoLinkEmail(result.maskedEmail)
            replace(targetOf(result))
          },
          () => {
            if (active.current) replace(withSavedLoginReturn(kakaoFailPath(null)))
          },
        )
    }, 0)
    return () => clearTimeout(timer)
  }, [replace, setKakaoLinkEmail, active])

  return (
    <OnboardingLayout
      panelTitle={
        <>
          보고는 회원만
          <br />할 수 있어요
        </>
      }
      footer={null}
    >
      <div role="status">
        <h1 className="text-setup-title leading-[1.4] font-bold text-fg">
          카카오 로그인을 확인하고 있어요
        </h1>
        <p className="mt-2 text-body leading-[1.6] text-fg-sub">잠시만 기다려 주세요.</p>
      </div>
    </OnboardingLayout>
  )
}
