import { ProgressBar } from '@/components/progress-bar'
import { StatusGauge } from '@/components/status-gauge'
import { StatusWord } from '@/components/status-word'
import { formatCount } from '@/lib/format'

import type { HomeWeekly } from './types'

/**
 * 홈 상태 카드. 화면에서 유일한 카드다 (design-guide.md "카드는 홈 상태 카드 하나").
 *
 * - 수치가 있는 주: 상태 글자 · 게이지 · 요약 · "참여 N명 · 증상 보고 N%"
 * - 자료 부족: 회색 게이지 · 요약 · **참여 진행 막대만**. 증상 비율은 타입에 없어 그릴 수 없다
 *
 * 시안: Home(모바일 여백 16/20/20 · 안쪽 20) · Tablet(안쪽 24) · Desktop(안쪽 20)
 */
export function StatusCard({ week }: { week: HomeWeekly }) {
  return (
    <section
      aria-label="우리 동네 이번 주 상태"
      className="mx-5 mt-4 mb-5 flex flex-col gap-3 rounded-card bg-section p-5 tablet:m-0 tablet:p-6 desktop:p-5"
    >
      <div className="flex items-end justify-between gap-2">
        <div className="flex flex-col gap-1">
          <span className="text-sub font-medium text-fg-sub">
            우리 동네 이번 주 · 시민 자가보고
          </span>
          <StatusWord status={week.status} />
        </div>
        <StatusGauge status={week.status} />
      </div>

      <p className="text-section-title leading-[1.45] font-semibold text-fg">{week.summary}</p>

      {week.status === 'insufficient' ? (
        <div className="flex flex-col gap-2">
          <div className="flex justify-between text-body-strong font-semibold text-fg">
            <span>우리 동네 자료를 채우는 중</span>
            <span>
              {formatCount(week.participants)} / {formatCount(week.publicThreshold)}명
            </span>
          </div>
          <ProgressBar
            value={week.participants}
            max={week.publicThreshold}
            label="우리 동네 참여 인원"
            valueText={`${formatCount(week.participants)}명 참여, 공개 기준 ${formatCount(week.publicThreshold)}명`}
          />
          <span className="text-sub leading-normal text-fg-sub">
            참여가 공개 기준을 넘으면 증상 변화를 보여드려요
          </span>
        </div>
      ) : (
        <p className="text-body-strong font-semibold text-fg">
          참여 {formatCount(week.participants)}명 · 증상 보고 {week.symptomRate}%
        </p>
      )}
    </section>
  )
}
