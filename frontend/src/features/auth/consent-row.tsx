import { Checkbox } from '@/components/checkbox'

/**
 * 동의 한 줄 (Setup-3 · Setup-4 · Consent-health-sheet). 체크 상자 + 오른쪽 "보기".
 *
 * "보기" 는 약관 본문 화면이 아직 없어 누르면 `onView` 로 "준비하고 있어요" 알림을 띄운다 — 시안 모양을 그대로 두고
 * 본문이 준비 중임을 알린다. 본문 화면이 생기면 링크로 바꾼다. 이름이 같은 "보기" 가 여럿이라 항목 이름을 붙여 읽는다.
 */
export function ConsentRow({
  checked,
  onChange,
  tag,
  title,
  detail,
  onView,
}: {
  checked: boolean
  onChange: (checked: boolean) => void
  /** [필수] · [선택] */
  tag?: 'required' | 'optional' | undefined
  title: string
  /** 제목 아래 13px 설명 (예: "이메일, 닉네임, 행정동") */
  detail?: string | undefined
  onView?: (() => void) | undefined
}) {
  return (
    <div className="flex items-center gap-2">
      <Checkbox
        size="md"
        className="grow"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
        label={
          <>
            {tag === 'required' && <span className="text-sub font-bold text-brand">[필수] </span>}
            {tag === 'optional' && <span className="text-sub font-bold text-fg-sub">[선택] </span>}
            {title}
            {detail !== undefined && (
              <span className="mt-0.5 block text-sub font-normal text-fg-sub">{detail}</span>
            )}
          </>
        }
      />
      {onView && (
        <button
          type="button"
          aria-label={`${title} 보기`}
          onClick={onView}
          className="flex min-h-touch shrink-0 cursor-pointer items-center px-1 text-sub font-semibold text-fg-sub"
        >
          보기
        </button>
      )}
    </div>
  )
}

/** 본문이 아직 없는 "보기" 를 눌렀을 때 알림 문구 */
export const LEGAL_TEXT_NOT_READY = '약관 본문을 준비하고 있어요'
