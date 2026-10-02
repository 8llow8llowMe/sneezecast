'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { KakaoButton } from '@/components/kakao-button'
import { Modal } from '@/components/modal'
import { HOME_PATH, LOGIN_EMAIL_PATH } from '@/features/onboarding/paths'

import { loginHref, type LoginReturn } from './login-return'
import { KAKAO_START_FAILURE_TEXT, type KakaoStartFailure, useKakaoStart } from './use-kakao-start'

/**
 * 로그인 안내 시트 (S03 Login-sheet). 비회원이 홈에서 보고를 누르면 홈 위에 뜬다.
 * 모바일은 바텀시트, 태블릿 · 데스크톱은 가운데 대화상자다(`Modal`). 닫기 · Esc · 바깥 누르기 · 휴대폰 뒤로 가기로 닫힌다.
 *
 * - 카카오로 계속하기: 실데이터는 카카오 인가 화면으로 문서를 옮기고, 목은 신규 회원으로 보아 동네 선택(`?from=kakao`)으로 간다
 *   (`useKakaoStart`, #167). 보내는 중에는 다시 누를 수 없다. 떠나기 전에 보고하려던 로그인(둘러보기 동네 포함)을 둔다(#140) —
 *   카카오 로그인 · 계정 연결 · 가입 마무리(S02-4)를 마치면 같은 동네 홈의 보고 진입으로 온다
 * - 이메일로 시작하기: 이메일 로그인(S13-5)으로 간다. 홈을 둘러보다 보고하려는 사람은 이미 회원인 경우가 많고,
 *   이메일 로그인 화면에 "이메일로 가입하기" 링크가 있어 처음인 사람도 한 번에 가입으로 갈 수 있다.
 *   보고하려던 로그인(`?intent=report`)과 둘러보기 동네를 넘겨 로그인 뒤 같은 동네 홈의 보고 진입으로 돌아온다(#136, `login-return.ts`)
 *
 * 시작하지 못하면(요청이 많음 `AUTH_028` 포함) 시트 안에 빨강 상자(`role="alert"`)로 알린다 — 시트가 뒤 화면을 막아 홈의 알림은 읽히지 않는다.
 * 내용은 열려 있을 때만 그린다. 닫으면 보내는 중 상태가 지워지고, 닫힌 뒤 늦게 온 응답은 버린다(`useActiveRef`).
 *
 * 시안: docs/design/auth/screens/ 의 Login-sheet (+ -T · -D)
 */
export function LoginSheet({
  open,
  onClose,
  regionCode = null,
}: {
  open: boolean
  onClose: () => void
  /** 둘러보기 동네 코드. 로그인 뒤 돌아올 홈에 남긴다 */
  regionCode?: string | null
}) {
  return (
    <Modal open={open} onClose={onClose} title="보고는 회원만 할 수 있어요">
      {open && <LoginSheetBody regionCode={regionCode} />}
    </Modal>
  )
}

function LoginSheetBody({ regionCode }: { regionCode: string | null }) {
  const router = useRouter()
  const kakao = useKakaoStart()
  const [failure, setFailure] = useState<KakaoStartFailure | null>(null)
  const loginReturn: LoginReturn = { next: HOME_PATH, region: regionCode, intent: 'report' }

  async function continueWithKakao() {
    setFailure(null)
    const failed = await kakao.start(loginReturn)
    if (failed) setFailure(failed)
  }

  return (
    <>
      <p className="text-body leading-[1.6] text-fg-sub">
        한 사람이 한 주에 한 번만 보고하도록 계정으로 확인해요.
      </p>
      {failure && <AlertBox tone="danger">{KAKAO_START_FAILURE_TEXT[failure]}</AlertBox>}
      <KakaoButton disabled={kakao.pending} onClick={() => void continueWithKakao()}>
        카카오로 계속하기
      </KakaoButton>
      <Button
        variant="secondary"
        fullWidth
        onClick={() => router.push(loginHref(LOGIN_EMAIL_PATH, loginReturn))}
      >
        이메일로 시작하기
      </Button>
      <p className="text-center text-sub text-fg-sub">
        동네 현황은 로그인 없이도 계속 볼 수 있어요.
      </p>
    </>
  )
}
