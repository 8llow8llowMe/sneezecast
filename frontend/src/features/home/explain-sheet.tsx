import { Button } from '@/components/button'
import { Modal } from '@/components/modal'
import { formatCount } from '@/lib/format'
import { STATUS_LABEL } from '@/lib/status'

import type { MeasuredHomeWeekly } from './types'

/**
 * S11 판단 기준. 상태 카드의 판정을 어떤 숫자와 기준으로 냈는지 보여 준다.
 *
 * **수치가 있는 주에만 연다.** 자료 부족이면 보일 숫자가 없고, 타입(`MeasuredHomeWeekly`)이 막는다.
 * 모바일은 머리줄 없는 시트(아래 확인 버튼으로 닫는다), 태블릿 · 데스크톱은 닫기 버튼이 있는 대화상자다.
 *
 * 시안: Explain · Explain-T · Explain-D
 */
export function ExplainSheet({
  week,
  open,
  onClose,
}: {
  week: MeasuredHomeWeekly
  open: boolean
  onClose: () => void
}) {
  const { slightDeltaPp, highDeltaPp } = week.thresholds

  const rows = [
    {
      term: '이번 주 증상 보고',
      value: `${week.symptomRate}%`,
      note: `참여 ${formatCount(week.participants)}명 중 ${formatCount(week.symptomReports)}명`,
    },
    { term: '기준선', value: `${week.baselineRate}%`, note: '지난 4주 평균' },
    {
      term: '판정',
      value: STATUS_LABEL[week.status],
      note: `+${slightDeltaPp}%p 이상 조금 · +${highDeltaPp}%p 이상 많이`,
    },
    {
      term: '공개 조건',
      value: `참여 ${formatCount(week.publicThreshold)}명 이상`,
      note: '미만이면 자료 부족으로 표시',
    },
    {
      term: '집계 방식',
      value: '1인 1주 1회',
      note: '같은 주 수정은 마지막 보고만 · 이상 보고는 제외',
    },
  ]

  return (
    <Modal open={open} onClose={onClose} title="이렇게 판단했어요" compactSheet>
      <p className="text-body-strong leading-[1.55] text-fg-sub">
        {week.regionName} · {week.weekRangeLabel} · 시민 자가보고
      </p>

      <dl className="flex flex-col">
        {rows.map((row) => (
          <div
            key={row.term}
            className="flex items-start justify-between gap-4 border-b border-divider py-3"
          >
            <dt className="shrink-0 text-body-strong text-fg-sub">{row.term}</dt>
            <dd className="flex flex-col items-end gap-0.5 text-right">
              <span className="text-body font-semibold text-fg">{row.value}</span>
              <span className="text-caption text-fg-sub">{row.note}</span>
            </dd>
          </div>
        ))}
      </dl>

      <p className="text-caption leading-[1.55] text-fg-sub">
        시민이 스스로 보고한 자료라 진단이나 공식 유행 판단이 아니에요.
      </p>

      <Button fullWidth onClick={onClose}>
        확인
      </Button>
    </Modal>
  )
}
