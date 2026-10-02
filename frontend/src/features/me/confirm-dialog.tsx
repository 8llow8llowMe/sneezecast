'use client'

import type { ReactNode } from 'react'

import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'
import { Modal } from '@/components/modal'

import { CONFIRM_FAILURE, type ConfirmKind } from './confirm'

/** 대화상자별 문구 (Confirm-logout · Confirm-consent-withdraw · Confirm-withdraw 시안). 실패 문구는 `CONFIRM_FAILURE` */
const CONTENT: Record<
  ConfirmKind,
  { title: string; items: ReactNode[]; action: string; danger: boolean }
> = {
  logout: {
    title: '로그아웃할까요?',
    items: ['이 기기에서만 로그아웃돼요.', '다시 보고하려면 로그인해야 해요.'],
    action: '로그아웃',
    danger: false,
  },
  'consent-withdraw': {
    title: '건강정보 동의를 철회할까요?',
    items: [
      '지금까지 보낸 보고를 모두 지워요.',
      '다시 동의하기 전까지 보고할 수 없어요.',
      <strong key="logout">모든 기기에서 로그아웃돼요.</strong>,
    ],
    action: '동의 철회하기',
    danger: true,
  },
  withdraw: {
    title: '회원 탈퇴할까요?',
    items: ['보낸 보고는 바로 지워요.', '계정은 30일 뒤 완전히 지워요.'],
    action: '탈퇴하기',
    danger: true,
  },
}

/**
 * 내 정보의 확인 대화상자 (Confirm-logout · Confirm-consent-withdraw · Confirm-withdraw, + -T · -D).
 * 모양은 `ConfirmModal` 이고, 이 컴포넌트는 종류별 문구 · 실패 문구만 고른다.
 *
 * 보내는 상태는 부모(`MeScreen`)가 갖는다 — 성공한 뒤 이동할 때까지, 응답 전에 대화상자가 닫혀도 결과를 잃지 않게 한다.
 * 탈퇴 사유 같은 입력은 받지 않는다(자유 서술 금지 — 루트 CLAUDE.md "개인정보").
 */
export function ConfirmDialog({
  kind,
  open,
  pending,
  failed,
  onConfirm,
  onClose,
}: {
  kind: ConfirmKind
  open: boolean
  pending: boolean
  failed: boolean
  onConfirm: () => void
  onClose: () => void
}) {
  const content = CONTENT[kind]
  return (
    <ConfirmModal
      open={open}
      title={content.title}
      items={content.items}
      action={content.action}
      danger={content.danger}
      pending={pending}
      failure={failed ? CONFIRM_FAILURE[kind] : null}
      onConfirm={onConfirm}
      onClose={onClose}
    />
  )
}

/**
 * 확인 대화상자의 모양. 내 정보의 확인 대화상자와 지도의 `내 동네로 설정` 확인(#145)이 같이 쓴다.
 * 모바일은 바텀시트, 태블릿 · 데스크톱은 가운데 대화상자다(`Modal`). 회색 상자 안 점 목록 · 취소 + 동작 버튼 두 개.
 *
 * - `pending`: 두 버튼이 꺼진다(`aria-disabled` — 포커스를 지킨다). 닫기 · Esc · 바깥 누르기는 부모가 막는다
 * - `failure`: 있으면 대화상자 안 빨강 상자(`role="alert"`)로 알리고 다시 누를 수 있다
 */
export function ConfirmModal({
  open,
  title,
  items,
  action,
  danger = false,
  pending,
  failure,
  onConfirm,
  onClose,
}: {
  open: boolean
  title: ReactNode
  items: ReactNode[]
  /** 동작 버튼 글자 */
  action: string
  /** 되돌릴 수 없는 동작이면 빨강 버튼이다 */
  danger?: boolean
  pending: boolean
  failure: string | null
  onConfirm: () => void
  onClose: () => void
}) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      title={title}
      // 시안은 머리줄이 없다. 모바일 시트는 숨기고, 태블릿 · 데스크톱은 공통 Modal 대로 닫기 버튼을 둔다
      compactSheet
      footer={
        <div className="flex gap-2.5">
          <Button
            variant="secondary"
            className="flex-1"
            aria-disabled={pending || undefined}
            onClick={() => {
              if (!pending) onClose()
            }}
          >
            취소
          </Button>
          <Button
            variant={danger ? 'danger' : 'primary'}
            className="flex-1"
            aria-disabled={pending || undefined}
            onClick={() => {
              if (!pending) onConfirm()
            }}
          >
            {action}
          </Button>
        </div>
      }
    >
      <ul className="flex flex-col gap-2.5 rounded-button bg-section px-4.5 py-4">
        {items.map((item, index) => (
          <li key={index} className="flex gap-2.5 text-body leading-[1.55] text-fg">
            <span aria-hidden="true" className="mt-2.25 size-1.5 shrink-0 rounded-chip bg-fg-sub" />
            <span>{item}</span>
          </li>
        ))}
      </ul>
      {failure && <AlertBox tone="danger">{failure}</AlertBox>}
    </Modal>
  )
}
