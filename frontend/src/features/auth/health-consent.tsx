import { Button } from '@/components/button'

import { ConsentRow } from './consent-row'

/**
 * 건강 · 증상 정보(민감정보) 처리 동의 내용. 증상 보고 동의 화면(S02-4)과 홈 위 시트(Consent-health-sheet)가 같이 쓴다.
 * 고지 표 · 체크 · 버튼을 따로 두어 화면은 본문과 아래 버튼 자리에, 시트는 한 덩어리로 놓을 수 있게 한다.
 *
 * 고지 문구는 개인정보 보호법 제23조(민감정보) 동의 고지다. 시안 문구를 그대로 두고 바꿀 때는 법무 검토를 거친다.
 * 개별 보고 보관 기간은 52주로 확정이다 (docs/design/auth/README.md).
 */
const NOTICE: { term: string; description: string }[] = [
  {
    term: '모으는 것',
    description: '증상 없음 또는 증상군(호흡기 · 장관), 보고 주차, 보고 동네(행정동)',
  },
  { term: '모으는 이유', description: '행정동 단위로 주간 증상 보고 비율을 집계해요.' },
  {
    term: '보관 기간',
    description:
      '개별 보고는 52주 뒤 지워지고 동네 집계값만 남아요. 동의를 철회하거나 탈퇴하면 바로 지워요.',
  },
  {
    term: '동의하지 않으면',
    description: '가입과 둘러보기는 할 수 있어요. 증상 보고만 할 수 없어요.',
  },
  { term: '모으지 않는 것', description: '이름, 연락처, 정확한 주소, GPS 위치, 자유 입력' },
]

/** 고지 표. 위아래 1px 구분선, 왼쪽 항목 104 */
export function HealthConsentNotice() {
  return (
    <dl className="flex flex-col border-t border-divider">
      {NOTICE.map((row) => (
        <div key={row.term} className="flex gap-3.5 border-b border-divider py-3">
          <dt className="w-26 shrink-0 text-body-strong font-bold text-fg">{row.term}</dt>
          <dd className="text-body-strong leading-[1.55] text-fg">{row.description}</dd>
        </div>
      ))}
    </dl>
  )
}

export function HealthConsentCheck({
  checked,
  onChange,
  onView,
}: {
  checked: boolean
  onChange: (checked: boolean) => void
  onView: () => void
}) {
  return (
    <ConsentRow
      title="건강·증상 정보(민감정보) 처리에 동의해요"
      detail="증상 보고에 필요해요"
      checked={checked}
      onChange={onChange}
      onView={onView}
    />
  )
}

/**
 * 동의 · 나중에 버튼. 동의는 체크해야 켜지고(`aria-disabled` — 포커스를 지킨다) 보내는 중에는 다시 누를 수 없다.
 * 문구는 놓이는 곳마다 다르다: 화면 "동의하고 시작하기" · 시트 "동의하고 보고하기".
 */
export function HealthConsentActions({
  agreeLabel,
  checked,
  pending,
  onAgree,
  onLater,
}: {
  agreeLabel: string
  checked: boolean
  pending: boolean
  onAgree: () => void
  onLater: () => void
}) {
  const off = !checked || pending
  return (
    <>
      <Button
        fullWidth
        aria-disabled={off || undefined}
        onClick={() => {
          if (!off) onAgree()
        }}
      >
        {agreeLabel}
      </Button>
      <Button variant="subtle" onClick={onLater} aria-disabled={pending || undefined}>
        나중에 할게요
      </Button>
    </>
  )
}
