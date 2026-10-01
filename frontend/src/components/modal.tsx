'use client'

import {
  type MouseEvent,
  type ReactNode,
  type SyntheticEvent,
  useEffect,
  useId,
  useRef,
} from 'react'

import clsx from 'clsx'

import { IconButton } from './icon-button'
import { ChevronLeftIcon, CloseIcon } from './icons'

export type ModalProps = {
  open: boolean
  /** Esc · 바깥 누르기 · 닫기 버튼에서 부른다. 상태는 부모가 갖고, 이 함수에서 open 을 false 로 바꾼다 */
  onClose: () => void
  /** 제목. 대화상자의 이름(`aria-labelledby`)이 된다. 모바일에서만 줄을 바꾸려면 `<br className="tablet:hidden" />` */
  title: ReactNode
  /** 머리줄 가운데 단계 표시. 예: "1 / 2" */
  step?: string
  /** 있으면 머리줄 왼쪽에 이전 단계 버튼을 둔다 */
  onBack?: () => void
  /**
   * **모바일 시트에서만** 머리줄(이전 단계 · 단계 표시 · 닫기)을 숨긴다. 판단 기준처럼 읽고 아래
   * 확인 버튼으로 닫는 시트용이다 (Explain 시안). 태블릿 · 데스크톱 대화상자는 손잡이가 없어
   * 닫을 곳이 보여야 하므로 머리줄을 그대로 둔다 (Explain-T · -D 시안).
   * 숨겨도 Esc · 바깥 누르기로 닫힌다.
   */
  compactSheet?: boolean
  /**
   * 모바일 배치.
   * - sheet: 화면 아래에 붙는 바텀시트 (기본)
   * - screen: 화면 전체. 아이콘 · 제목 · 내용이 가운데, footer 가 아래에 붙는다 (보고 완료 — Report-done 시안).
   *   머리줄 · 손잡이가 없고 footer 의 버튼이나 Esc 로 닫는다
   *
   * 태블릿 · 데스크톱은 둘 다 가운데 대화상자다.
   */
  mobileLayout?: 'sheet' | 'screen'
  /** 제목 위 아이콘 (보고 완료 체크) */
  icon?: ReactNode
  /** 아래 버튼 영역. `screen` 배치의 모바일에서는 화면 아래에 붙는다 */
  footer?: ReactNode
  children: ReactNode
}

const LAYOUT = {
  sheet: {
    dialog: 'mx-0 mt-auto mb-0 max-h-modal w-full max-w-none rounded-t-sheet',
    inner: 'flex flex-col gap-4 px-5 pt-2.5 pb-sheet',
    body: 'flex flex-col gap-4',
    footer: 'flex flex-col gap-2.5',
    header: 'flex',
    title: 'text-sheet-title',
  },
  screen: {
    dialog: 'm-0 h-dvh max-h-none w-full max-w-none',
    inner: 'flex min-h-full flex-col pt-2.5',
    body: 'flex grow flex-col justify-center gap-4 px-5',
    // 되돌리기 토스트를 버튼 바로 위에 띄울 수 있게 기준 위치를 둔다 (Report-done-ok 시안)
    footer: 'relative flex flex-col gap-2.5 px-5 pt-3 pb-sheet',
    header: 'hidden tablet:flex',
    title: 'text-status',
  },
} as const

/**
 * 반응형 모달. **모바일은 바텀시트, 태블릿 · 데스크톱은 가운데 대화상자**다 (design-guide.md "디자인 규칙").
 *
 * 네이티브 `<dialog>` 의 `showModal()` 을 쓴다. 포커스 가두기 · 뒤 화면 비활성 · Esc 처리를 브라우저가
 * 맡으므로 직접 구현하지 않는다. 열린 동안 뒤 화면 스크롤은 globals.css 가 막는다.
 *
 * 시안: Report-start(시트) · Report-start-T(520) · Report-start-D(480) · Report-symptom(머리줄) · Explain(시트에서만 머리줄 없음) · Report-done(모바일 전체 화면)
 */
export function Modal({
  open,
  onClose,
  title,
  step,
  onBack,
  compactSheet = false,
  mobileLayout = 'sheet',
  icon,
  footer,
  children,
}: ModalProps) {
  const layout = LAYOUT[mobileLayout]
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()

  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  }, [open])

  // Esc 는 브라우저가 dialog 를 바로 닫는다. 그러면 부모 상태(open)와 어긋나므로 막고 부모에게 맡긴다
  function handleCancel(event: SyntheticEvent<HTMLDialogElement>) {
    event.preventDefault()
    onClose()
  }

  // 안쪽 상자가 dialog 를 꽉 채우고 dialog 자체에는 여백이 없다.
  // 그래서 클릭 대상이 dialog 자신이면 바깥(::backdrop)을 누른 것이다
  function handleClick(event: MouseEvent<HTMLDialogElement>) {
    if (event.target === event.currentTarget) onClose()
  }

  return (
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions, jsx-a11y/click-events-have-key-events -- 바깥 누르기는 마우스 · 터치 보조 수단이다. 키보드는 Esc(cancel)로 닫는다
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      onCancel={handleCancel}
      onClick={handleClick}
      className={clsx(
        'overflow-y-auto overscroll-contain bg-bg p-0 text-fg backdrop:bg-dim',
        layout.dialog,
        // 태블릿 · 데스크톱: 가운데 대화상자. 높이는 auto 가 아니라 fit-content 다 — 모달 dialog 는 위아래가
        // 0 에 고정돼 있어(브라우저 기본값) auto 면 화면 높이만큼 늘어난다
        'tablet:m-auto tablet:h-fit tablet:max-h-modal tablet:w-dialog-tablet tablet:max-w-dialog tablet:rounded-dialog',
        'desktop:w-dialog-desktop',
      )}
    >
      <div
        className={clsx(
          layout.inner,
          'tablet:min-h-0 tablet:gap-3.5 tablet:px-7 tablet:pt-4 tablet:pb-7',
        )}
      >
        {/* 손잡이 — 시트임을 알리는 장식. 대화상자 · 전체 화면에서는 숨긴다 */}
        {mobileLayout === 'sheet' && (
          <div
            aria-hidden="true"
            className="h-1 w-10 self-center rounded-bar bg-inactive-bar tablet:hidden"
          />
        )}

        <div
          className={clsx(
            'h-11 items-center justify-between',
            compactSheet ? 'hidden tablet:flex' : layout.header,
          )}
        >
          {onBack ? (
            <IconButton
              label="이전 단계"
              icon={<ChevronLeftIcon />}
              onClick={onBack}
              className="-ml-3"
            />
          ) : (
            <span className="w-11" />
          )}
          <span className="text-sub font-semibold text-fg-sub">{step}</span>
          <IconButton label="닫기" icon={<CloseIcon />} onClick={onClose} className="-mr-3" />
        </div>

        <div
          className={clsx(
            layout.body,
            'tablet:grow-0 tablet:justify-start tablet:gap-3.5 tablet:px-0',
          )}
        >
          {icon}
          <h2
            id={titleId}
            className={clsx(
              'leading-[1.35] font-bold text-fg tablet:text-dialog-title',
              layout.title,
              compactSheet && 'mt-1 tablet:mt-0',
            )}
          >
            {title}
          </h2>

          {children}
        </div>

        {footer != null && <div className={clsx(layout.footer, 'tablet:p-0')}>{footer}</div>}
      </div>
    </dialog>
  )
}
