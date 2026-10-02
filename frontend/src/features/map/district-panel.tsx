import { type MouseEvent, type PointerEvent, type Ref, useId, useRef } from 'react'
import Link from 'next/link'

import clsx from 'clsx'

import { ListRow } from '@/components/list-row'
import { ProgressBar } from '@/components/progress-bar'
import { GROUP_LABEL, scaleMax, TREND_LABEL, TREND_TEXT_CLASS } from '@/features/home/symptom'
import { TrendBars } from '@/features/home/symptom-trends'
import { formatCount } from '@/lib/format'
import { STATUS_LABEL, STATUS_TEXT_CLASS } from '@/lib/status'

import type { MapDistrict } from './types'

/** 위 · 아래로 이만큼(px) 밀면 펼치거나 접는다 */
const SWIPE_THRESHOLD = 24

/** 아래 두 동작. 높이 56 · 글자 15 (시안 — 공통 Button lg 는 글자 17 이라 따로 둔다) */
const ACTION_CLASS =
  'flex h-button flex-1 items-center justify-center rounded-button px-5 text-body font-semibold'

const TILE_CLASS = 'flex flex-1 basis-0 flex-col gap-1 rounded-button bg-section p-3.5'

/**
 * 고른 동네 정보 (Map-collapsed · Map-expanded · Map-nodata, -T · -D).
 *
 * | 폭 | 구성 |
 * | --- | --- |
 * | 모바일 | 지도 아래 시트(위 모서리 20, 지도를 20 덮음). 접힘: 이름 · 상태 · 한 줄 요약. 펼침: 자세한 정보 |
 * | 태블릿 | 지도 아래 늘 펼친 정보 |
 * | 데스크톱 | 지도 오른쪽 400 패널, 늘 펼친 정보 |
 *
 * 모바일 접힘 · 펼침은 손잡이 버튼(누름)과 위 · 아래로 밀기로 바꾼다. 상태는 부모가 갖는다.
 * **자료 부족이면 수치 · 상태색 없이 `자료 부족` 글자와 참여 진행 막대만** 보인다 — 증상 비율 · 기준선은 타입에 없다.
 * `이 동네 안내 보기` 는 운영자가 발행한 안내가 있는 동네에만 있다. `내 동네로 설정` 은 내 동네(`mine`)에는 없고,
 * 대신 이름 뒤 `(내 동네)` 로 이미 내 동네임을 보인다(Map-collapsed 시안 — 꺼진 버튼보다 상태를 알린다).
 */
export function DistrictPanel({
  district,
  mine,
  expanded,
  onExpandedChange,
  onSetMine,
  onExplain,
  navSearch,
  nameRef,
}: {
  district: MapDistrict
  /** 내 동네인지. 회원은 프로필의 내 동네, 비회원은 처음 고른 동네다(`MapScreen`) */
  mine: boolean
  expanded: boolean
  onExpandedChange: (expanded: boolean) => void
  onSetMine: () => void
  onExplain: () => void
  /** 동네 안내 링크 뒤에 붙일 둘러보기 동네(`region=<코드>`) */
  navSearch?: string | undefined
  /** 동네 이름 제목. 내 동네로 설정한 뒤 사라진 버튼 대신 포커스를 받는다(`MapScreen`) */
  nameRef?: Ref<HTMLHeadingElement> | undefined
}) {
  const { week } = district
  const detailsId = useId()
  const swipe = useRef<{ y: number; moved: boolean } | null>(null)

  const onPointerDown = (event: PointerEvent) => {
    swipe.current = { y: event.clientY, moved: false }
  }
  const onPointerUp = (event: PointerEvent) => {
    const start = swipe.current
    if (!start) return
    const delta = event.clientY - start.y
    if (Math.abs(delta) < SWIPE_THRESHOLD) return
    start.moved = true
    onExpandedChange(delta < 0)
  }
  // 민 뒤 따라오는 누름은 밀기가 이미 정했으므로 손잡이 바꾸기로 세지 않는다. 키보드 누름(detail 0)은 늘 바꾼다
  const onHandleClick = (event: MouseEvent) => {
    const swiped = swipe.current?.moved === true
    swipe.current = null
    if (swiped && event.detail > 0) return
    onExpandedChange(!expanded)
  }

  const noticeHref = week.notice ? withSearch(week.notice.href, navSearch) : null

  return (
    <section
      aria-labelledby={`${detailsId}-name`}
      className="relative -mt-5 flex shrink-0 flex-col gap-2.5 rounded-t-sheet bg-bg px-5 pt-2.5 pb-4 tablet:mt-0 tablet:gap-3.5 tablet:rounded-none tablet:p-0 desktop:w-100"
    >
      {/* 밀기는 시트 윗부분(손잡이 · 이름 · 요약)에서만 받는다. 아래 정보에서는 화면 스크롤과 겹친다 */}
      <div onPointerDown={onPointerDown} onPointerUp={onPointerUp} className="contents">
        <button
          type="button"
          aria-expanded={expanded}
          aria-controls={detailsId}
          aria-label={expanded ? '동네 정보 접기' : '동네 정보 자세히 보기'}
          onClick={onHandleClick}
          className="-mt-2.5 -mb-3.5 flex h-touch cursor-pointer items-center justify-center tablet:hidden"
        >
          <span aria-hidden="true" className="block h-1 w-10 rounded-bar bg-inactive-bar" />
        </button>

        <div className="flex items-baseline gap-2">
          {/* 탭 순서에는 들지 않고 스크립트로만 포커스를 받는다(내 정보의 섹션 바로가기와 같다) */}
          <h2
            ref={nameRef}
            id={`${detailsId}-name`}
            tabIndex={-1}
            className="text-screen-title font-bold text-fg focus:outline-none"
          >
            {week.regionName}
            {mine && ' (내 동네)'}
          </h2>
          <span className={clsx('text-section-title font-bold', STATUS_TEXT_CLASS[week.status])}>
            {STATUS_LABEL[week.status]}
          </span>
        </div>

        {!expanded && (
          <p className="text-body leading-normal text-fg-sub tablet:hidden">
            {week.summary} · 위로 밀어 자세히 보기
          </p>
        )}
      </div>

      <div
        id={detailsId}
        className={clsx(
          'flex-col gap-2.5 tablet:flex tablet:gap-3.5',
          expanded ? 'flex' : 'hidden',
        )}
      >
        <span className="text-sub text-fg-sub">시민 자가보고 · {week.weekRangeLabel}</span>

        {week.status === 'insufficient' ? (
          <>
            <p className="text-body leading-normal text-fg-sub">
              이번 주 보고가 아직 적어요. 표본이 적을 때는 수치를 표시하지 않아요.
            </p>
            <div className="flex flex-col gap-2">
              <div className="flex justify-between text-body-strong font-semibold text-fg">
                <span>자료를 채우는 중</span>
                <span>
                  {formatCount(week.participants)} / {formatCount(week.publicThreshold)}명
                </span>
              </div>
              <ProgressBar
                value={week.participants}
                max={week.publicThreshold}
                label={`${week.regionName} 참여 인원`}
                valueText={`${formatCount(week.participants)}명 참여, 공개 기준 ${formatCount(week.publicThreshold)}명`}
              />
            </div>
          </>
        ) : (
          <>
            <div className="flex gap-2">
              <Tile label="참여" value={`${formatCount(week.participants)}명`} />
              <Tile label="증상 보고" value={`${week.symptomRate}%`} />
              <Tile label="지난 4주 평균" value={`${week.baselineRate}%`} />
            </div>
            <div className="flex flex-col">
              {week.groups.map((group) => (
                <ListRow
                  key={group.key}
                  title={GROUP_LABEL[group.key]}
                  description={
                    <span className={TREND_TEXT_CLASS[group.trend]}>
                      {TREND_LABEL[group.trend]}
                    </span>
                  }
                  trailing={<TrendBars group={group} max={scaleMax(week.groups)} />}
                  divider
                />
              ))}
            </div>
            <button
              type="button"
              onClick={onExplain}
              className="min-h-touch cursor-pointer self-start text-sub font-semibold text-brand underline"
            >
              왜 이렇게 보나요?
            </button>
          </>
        )}

        {(!mine || noticeHref) && (
          <div className="mt-1 flex gap-2.5">
            {!mine && (
              <button
                type="button"
                onClick={onSetMine}
                className={clsx(ACTION_CLASS, 'cursor-pointer bg-section text-fg')}
              >
                내 동네로 설정
              </button>
            )}
            {noticeHref && (
              <Link href={noticeHref} className={clsx(ACTION_CLASS, 'bg-brand text-bg')}>
                이 동네 안내 보기
              </Link>
            )}
          </div>
        )}
      </div>
    </section>
  )
}

function Tile({ label, value }: { label: string; value: string }) {
  return (
    <div className={TILE_CLASS}>
      <span className="text-caption text-fg-sub">{label}</span>
      <span className="text-screen-title font-bold text-fg">{value}</span>
    </div>
  )
}

/** 주소 뒤에 쿼리를 잇는다. 이미 쿼리(목 `?mock=`)가 있으면 `&` 로 잇는다 */
function withSearch(href: string, search: string | undefined): string {
  if (!search) return href
  return `${href}${href.includes('?') ? '&' : '?'}${search}`
}
