'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { KakaoButton } from '@/components/kakao-button'
import { Modal } from '@/components/modal'
import { LOGIN_EMAIL_PATH } from '@/features/onboarding/paths'
import { useActiveRef } from '@/lib/use-active-ref'

import { startKakaoLogin } from './auth-client'

/**
 * 로그인 안내 시트 (S03 Login-sheet). 비회원이 홈에서 보고를 누르면 홈 위에 뜬다.
 * 모바일은 바텀시트, 태블릿 · 데스크톱은 가운데 대화상자다(`Modal`). 닫기 · Esc · 바깥 누르기 · 휴대폰 뒤로 가기로 닫힌다.
 *
 * - 카카오로 계속하기: `startKakaoLogin` 이 돌려준 주소로 간다. 보내는 중에는 다시 누를 수 없다
 * - 이메일로 시작하기: 이메일 로그인(S13-5)으로 간다. 홈을 둘러보다 보고하려는 사람은 이미 회원인 경우가 많고,
 *   이메일 로그인 화면에 "이메일로 가입하기" 링크가 있어 처음인 사람도 한 번에 가입으로 갈 수 있다
 *
 * 시작하지 못하면 시트 안에 빨강 상자(`role="alert"`)로 알린다 — 시트가 뒤 화면을 막아 홈의 알림은 읽히지 않는다.
 * 내용은 열려 있을 때만 그린다. 닫으면 보내는 중 상태가 지워지고, 닫힌 뒤 늦게 온 응답은 버린다(`useActiveRef`).
 *
 * 시안: docs/design/auth/screens/ 의 Login-sheet (+ -T · -D)
 */
export function LoginSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <Modal open={open} onClose={onClose} title="보고는 회원만 할 수 있어요">
      {open && <LoginSheetBody />}
    </Modal>
  )
}

function LoginSheetBody() {
  const router = useRouter()
  const active = useActiveRef()
  const [pending, setPending] = useState(false)
  const [failed, setFailed] = useState(false)

  async function continueWithKakao() {
    if (pending) return
    setPending(true)
    setFailed(false)
    try {
      const { redirectTo } = await startKakaoLogin()
      if (!active.current) return
      // 이동하는 동안은 꺼진 채로 둔다 — 다시 눌러 두 번 시작하지 않게 한다
      router.push(redirectTo)
    } catch {
      if (!active.current) return
      setFailed(true)
      setPending(false)
    }
  }

  return (
    <>
      <p className="text-body leading-[1.6] text-fg-sub">
        한 사람이 한 주에 한 번만 보고하도록 계정으로 확인해요.
      </p>
      {failed && (
        <AlertBox tone="danger">
          카카오 로그인을 시작하지 못했어요. 잠시 뒤 다시 시도해 주세요.
        </AlertBox>
      )}
      <KakaoButton disabled={pending} onClick={() => void continueWithKakao()}>
        카카오로 계속하기
      </KakaoButton>
      <Button variant="secondary" fullWidth onClick={() => router.push(LOGIN_EMAIL_PATH)}>
        이메일로 시작하기
      </Button>
      <p className="text-center text-sub text-fg-sub">
        동네 현황은 로그인 없이도 계속 볼 수 있어요.
      </p>
    </>
  )
}
