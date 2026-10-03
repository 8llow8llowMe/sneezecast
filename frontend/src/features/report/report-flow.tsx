'use client'

import { type ReactNode, useEffect, useRef, useState } from 'react'
import Link from 'next/link'
import { useSearchParams } from 'next/navigation'

import { Button } from '@/components/button'
import { Callout } from '@/components/callout'
import { ChoiceButton } from '@/components/choice-button'
import { ChevronRightIcon, SuccessIcon } from '@/components/icons'
import { Modal } from '@/components/modal'
import { ToastRegion, useToast } from '@/components/toast'
import { retryMemberInfo } from '@/features/auth/member-info'
import { INSTALL_PATH, installSearch } from '@/features/install/install-entry'
import { useMemberRegionStatus } from '@/features/me/member-region'
import { clientEnv } from '@/lib/env.client'
import { navHref } from '@/lib/nav'
import { useDataSource } from '@/lib/use-data-source'
import { useModalParam } from '@/lib/use-modal-param'
import { usePushSupport } from '@/lib/use-push-support'

import { retryCurrentReport } from './current-report'
import { cancelReport, submitReport, updateReport } from './report-client'
import { buildShareLink } from './share-link'
import { ShareSheet } from './share-sheet'
import { summarizeAnswer, SYMPTOM_OPTIONS, toggleSymptom } from './symptoms'
import {
  REPORT_PARAM,
  REPORT_STEPS,
  type ReportAnswer,
  type ReportStep,
  type ReportSymptom,
  type ReportWeek,
  type SubmittedReport,
} from './types'
import { useSubmittedReport, useSubmittedReportStatus } from './use-submitted-report'

export type ReportFlowProps = {
  /** 이번 주 정보. 동네는 **보고 동네(회원의 내 동네)** 다 — 둘러보는 동네가 아니다 */
  week: ReportWeek
  /** 둘러보기 동네(화면이 확인한 코드). 홈 화면 추가 안내에서 주소로 바로 닫을 때 홈 주소에 남긴다 */
  regionCode: string | null
  /**
   * 보고 동네(회원의 내 동네, `useMemberRegion()` 의 코드) 코드. 모르면(목: 가입 없이 이메일 로그인 · 덮어쓰기, 실데이터: 미설정 ·
   * 읽는 중 · 읽지 못함 · 이름 모름) null 이다. 실데이터는 보고를 이 동네로 보내고, null 이면 보내지 않는다.
   * 함께 채우기 공유 링크가 이 동네를, 없으면 둘러보기 동네를, 그것도 없으면 동네 없는 홈을 싣는다
   */
  reportRegionCode?: string | null
  /**
   * 홈에서 내 동네와 다른 동네를 둘러보는 중인지(#141). 참이면 시작 단계에 보고 동네를 알리고 확인 단계의 동네 앞에 `보고 동네` 를 붙인다 —
   * 보고는 늘 내 동네로 집계되는데 화면에는 둘러보는 동네가 보이고 있다
   */
  reportingElsewhere?: boolean
}

/**
 * S05 주간 건강 보고 · S06 보고 완료.
 *
 * | 단계 | 주소 | 내용 |
 * | --- | --- | --- |
 * | 시작 | `?report=start` | 증상 없었어요(바로 보내고 되돌리기 알림) · 증상이 있었어요 |
 * | 증상 고르기 1/2 | `?report=symptom` | 여러 개 고르기 |
 * | 확인 2/2 | `?report=confirm` | 보낼 내용 · 개인정보 안내 · 보내기 |
 * | 완료 | `?report=done` | 모바일 전체 화면 · 대화상자. 홈 화면 추가 안내(홈 화면 앱이어야 알림을 받는 기기만) · 변화 보기 · 수정하기 · 함께 채우기 |
 * | 함께 채우기 | `?report=share` | 완료 화면 위 공유 시트(`ShareSheet`). 닫기 · 뒤로 가기는 완료로 돌아간다 |
 *
 * 단계로 나아갈 때는 기록을 쌓아 뒤로 가기가 이전 단계로 이어지고, 보낸 뒤 완료는 기록 없이 바꾼다.
 * 대화상자 하나를 열어 둔 채 단계에 따라 내용만 바꾼다 — 단계마다 닫고 열면 화면이 깜빡인다.
 *
 * **함께 채우기 공유 시트는 별도 쿼리가 아니라 보고 흐름의 단계(`share`)다.** 완료 다음에만 열 수 있는 화면이라
 * 완료와 같은 가드(보낸 보고가 없으면 시작, `resolveStep`)와 회원 상태 가드(`guardReportEntry`)를 그대로 받고,
 * 완료와 동시에 있을 수 없어 쿼리가 남아 엉뚱하게 열리는 일이 없다(별도 키면 `?report=start&share=1` 이 남아 보낸 뒤 시트가 튀어나온다).
 * 시트를 여는 동안 완료 대화상자는 그대로 두고 그 위에 시트를 하나 더 연다(Flow 시안도 완료 위에 시트를 띄운다).
 *
 * 보낸 보고는 이 화면 · 홈의 상태가 아니라 `report-client`(목) · 이번 주 보고 저장소(실데이터)가 갖는다. 완료에서 홈 화면 추가
 * 안내(`/install`)로 갔다가 닫으면(`router.back()`) 홈이 새로 그려져도 `?report=done` 이 완료로 남는다.
 *
 * **실데이터(#165)** — 보내기 · 고치기 · 되돌리기는 보고 API 이고 보고 동네는 내 동네(`reportRegionCode`)다:
 * - 이번 주 보고를 읽는 중이면 시작 단계에 "확인하고 있어요" 를 보이고 고르기를 끈다. 읽지 못했으면 그 안내와 `다시 불러오기` 를 보이고
 *   보낼 수는 있다(같은 주는 하나라 보내면 이 내용으로 바뀐다)
 * - 되돌리기는 PUT 응답이 이번 주 첫 저장일 때만 준다(`firstSubmission`). 되돌리는 동안 수정하기 · 함께 채우기를 끄고,
 *   응답 뒤에는 완료를 보고 있을 때만 시작 단계로 바꾼다
 * - 내 동네를 읽는 중 · 읽지 못함 · 없음이면 보내지 않고 알린다(읽지 못했으면 다시 읽는다)
 * - 보고 권한 없음 → 세션 요약을 다시 맞춘다(`report-client`). 동의가 없다고 바뀌면 홈이 이 흐름을 내리고 건강정보 동의 시트로 바꾼다
 * - 동네를 쓸 수 없음(폐지 · 없음) → 내 동네를 다시 읽는다. 폐지면 홈의 회원 조건 가드가 동네 다시 고르기로 보낸다
 * - 되돌리기에 실패하면 완료에 남고 다시 시도할 수 있게 알린다
 *
 * 시안: Report-start · Report-symptom · Report-confirm · Report-edit · Report-done · Report-done-ok (+ -T · -D), Flow
 */
export function ReportFlow({
  week,
  regionCode,
  reportRegionCode = null,
  reportingElsewhere = false,
}: ReportFlowProps) {
  const param = useModalParam(REPORT_PARAM)
  const searchParams = useSearchParams()
  // 새로고침하면 비고 하이드레이션 첫 그림도 비어 있다 — 그때 `?report=done` 은 시작 단계로 보인다(`resolveStep`)
  const submitted = useSubmittedReport()
  const source = useDataSource()
  // 이번 주 보고를 읽었는지(실데이터). 목데이터는 늘 ready 다
  const reportStatus = useSubmittedReportStatus()
  const regionStatus = useMemberRegionStatus()
  const pushSupport = usePushSupport()
  const { toast, show, dismiss } = useToast()
  const [selected, setSelected] = useState<ReportSymptom[]>([])
  // 보내기 · 되돌리기를 기다리는 중. 그동안 선택지 · 수정하기 · 함께 채우기를 끈다
  const [pending, setPending] = useState(false)
  // 지금 주소의 단계. 되돌리기 응답이 왔을 때 사용자가 이미 다른 곳(닫기 · 공유)으로 갔으면 시작 단계로 끌어오지 않는다
  const paramValue = useRef(param.value)
  useEffect(() => {
    paramValue.current = param.value
  })

  const step = resolveStep(param.value, { hasSelection: selected.length > 0, submitted })
  // 공유 시트를 여는 동안 아래 대화상자는 완료를 그대로 보인다
  const shown = step === 'share' ? 'done' : step
  const shareLink = buildShareLink(clientEnv.siteUrl, reportRegionCode ?? regionCode)

  /** 실데이터에서 보고 동네를 보낼 수 있는지. 못 보내면 알리고 false 다 */
  function regionReady(): boolean {
    if (source !== 'api') return true
    if (regionStatus === 'loading') {
      show({ message: REGION_LOADING_MESSAGE })
      return false
    }
    if (regionStatus === 'failed') {
      retryMemberInfo()
      show({ message: REGION_FAILED_MESSAGE })
      return false
    }
    if (reportRegionCode === null) {
      show({ message: REGION_MISSING_MESSAGE })
      return false
    }
    return true
  }

  async function send(answer: ReportAnswer) {
    if (!regionReady()) return
    setPending(true)
    try {
      // 보낸 보고는 report-client(목) · 이번 주 보고 저장소(실데이터)가 갖고 알린다(useSubmittedReport)
      const result = await (submitted ? updateReport : submitReport)(
        answer,
        reportRegionCode,
        source,
      )
      if (result.status === 'region-changed') {
        show({ message: REGION_CHANGED_MESSAGE })
        return
      }
      // 동의가 없다고 바뀌었으면 홈이 이 흐름을 내리고 동의 시트로 바꾼다. 여기 남아 있으면 요약이 그대로다 — 잠시 뒤 다시
      if (result.status === 'consent-required') {
        show({ message: SEND_FAILED_MESSAGE })
        return
      }
      // 고른 증상은 보낸 보고에 담겼다. 다시 고칠 때는 보낸 보고에서 채운다 (startSymptoms)
      setSelected([])
      param.replace('done')
      // 처음 보내는 "증상 없음" 만 되돌리기를 준다 (Report-done-ok). 수정은 이전 보고로 되돌릴 수 없어 주지 않는다.
      // 처음인지는 이 탭 저장소가 아니라 **서버 응답**으로 정한다(`firstSubmission`) — 다른 기기 · 탭에서 보낸 보고를 이 탭이
      // 몰랐거나(읽는 중 · 실패) 그 사이 보냈으면 이번 요청은 수정이라, 되돌리면 그 보고까지 지운다
      if (answer.kind === 'none' && result.firstSubmission) {
        show({ message: '증상 없음으로 보냈어요', action: { label: '되돌리기', onClick: undo } })
      }
    } catch {
      show({ message: SEND_FAILED_MESSAGE })
    } finally {
      setPending(false)
    }
  }

  function undo() {
    setPending(true)
    void cancelReport(source)
      .then(
        () => {
          setSelected([])
          // 완료를 보고 있을 때만 시작으로 바꾼다. 그사이 닫았거나 공유 시트를 열었으면 그대로 둔다
          if (paramValue.current === 'done') param.replace('start')
        },
        // 보고는 그대로 남아 있다(완료에 머문다). 같은 알림에서 다시 되돌릴 수 있게 한다
        () => show({ message: UNDO_FAILED_MESSAGE, action: { label: '다시 시도', onClick: undo } }),
      )
      .finally(() => setPending(false))
  }

  function startSymptoms() {
    // 증상을 보냈던 주를 고칠 때는 이전에 고른 증상을 채워 둔다
    if (selected.length === 0 && submitted?.answer.kind === 'symptom') {
      setSelected([...submitted.answer.symptoms])
    }
    param.push('symptom')
  }

  // 닫으면 고르던 증상을 버린다. 다시 열었을 때 지난번 선택이 몰래 섞여 보내지지 않게 한다
  function close() {
    dismiss()
    setSelected([])
    param.close()
  }

  const [first, ...rest] = selected
  const symptomAnswer: ReportAnswer | null = first
    ? { kind: 'symptom', symptoms: [first, ...rest] }
    : null

  return (
    <>
      <Modal
        open={shown !== null}
        onClose={close}
        title={STEP_TITLE[shown ?? 'start']}
        step={shown === 'symptom' ? '1 / 2' : shown === 'confirm' ? '2 / 2' : undefined}
        onBack={
          shown === 'symptom'
            ? () => param.back('start')
            : shown === 'confirm'
              ? () => param.back('symptom')
              : undefined
        }
        mobileLayout={shown === 'done' ? 'screen' : 'sheet'}
        icon={shown === 'done' ? <SuccessIcon /> : undefined}
        footer={
          shown === 'done' ? (
            <>
              {/* 모바일은 버튼 바로 위에 띄우고, 태블릿 · 데스크톱은 흐름 안에 둔다 (Report-done-ok / -D) */}
              <ToastRegion
                toast={toast}
                onAction={dismiss}
                className="absolute inset-x-5 bottom-full tablet:static"
              />
              {/*
              모바일은 변화 보기가 위, 태블릿 · 데스크톱은 수정하기가 왼쪽이다.
              한 줄일 때는 두 버튼이 폭을 반씩 나눈다 — Button 은 기본이 shrink-0 이라 그대로 두면 각자 100% 로 넘친다
            */}
              <div className="flex flex-col gap-2.5 tablet:flex-row-reverse">
                <Button fullWidth onClick={close} className="tablet:flex-1">
                  우리 동네 변화 보기
                </Button>
                <Button
                  variant="secondary"
                  fullWidth
                  className="tablet:flex-1"
                  disabled={pending}
                  onClick={() => {
                    dismiss()
                    param.replace('start')
                  }}
                >
                  보고 수정하기
                </Button>
              </div>
              {/* 기록을 쌓아 휴대폰 뒤로 가기가 공유 시트를 닫고 완료로 돌아오게 한다 */}
              <Button variant="text" disabled={pending} onClick={() => param.push('share')}>
                우리 동네 자료 함께 채우기
              </Button>
            </>
          ) : undefined
        }
      >
        {shown === 'start' && (
          <>
            {reportingElsewhere && (
              <Callout>
                보고는 내 동네 {week.regionName} 기준이에요. 둘러보는 동네와 달라요.
              </Callout>
            )}
            {submitted && (
              <Callout>
                {submitted.reportedLabel}에 보고했어요. 수정하면 집계에는 마지막 보고만 반영돼요.
              </Callout>
            )}
            {reportStatus === 'loading' && (
              <Callout tone="neutral">이번 주 보고를 확인하고 있어요.</Callout>
            )}
            {reportStatus === 'failed' && (
              <>
                <Callout tone="neutral">
                  이번 주에 보낸 보고를 불러오지 못했어요. 지금 보내면 이번 주 보고가 이 내용으로
                  바뀌어요.
                </Callout>
                <Button variant="text" onClick={retryCurrentReport}>
                  다시 불러오기
                </Button>
              </>
            )}
            <p className="mb-2 text-body-strong text-fg-sub">
              {week.reportPeriodLabel} · 성인 본인의 상태만 알려주세요
            </p>
            <ChoiceButton
              label="증상 없었어요"
              disabled={pending || reportStatus === 'loading'}
              onClick={() => void send({ kind: 'none' })}
            />
            <ChoiceButton
              label="증상이 있었어요"
              disabled={pending || reportStatus === 'loading'}
              onClick={startSymptoms}
            />
          </>
        )}

        {shown === 'symptom' && (
          <>
            <p className="mb-2 text-body-strong text-fg-sub">여러 개 고를 수 있어요</p>
            {SYMPTOM_OPTIONS.map((option) => (
              <ChoiceButton
                key={option.key}
                label={option.label}
                size="md"
                selected={selected.includes(option.key)}
                onClick={() => setSelected((now) => toggleSymptom(now, option.key))}
              />
            ))}
            <Button
              fullWidth
              disabled={selected.length === 0}
              onClick={() => param.push('confirm')}
              className="mt-2"
            >
              다음
            </Button>
          </>
        )}

        {shown === 'confirm' && symptomAnswer && (
          <>
            <div className="flex flex-col gap-1.5 rounded-button bg-section p-4.5">
              <span className="text-sub text-fg-sub">
                {week.weekRangeLabel} · {reportingElsewhere && '보고 동네 '}
                {week.regionName}
              </span>
              <span className="text-section-title font-semibold text-fg">
                {summarizeAnswer(symptomAnswer)}
              </span>
            </div>
            <p className="text-sub leading-normal text-fg-sub">
              행정동 단위로만 집계돼요. 이름·주소·위치는 받지 않아요.
            </p>
            <Button
              fullWidth
              disabled={pending}
              onClick={() => void send(symptomAnswer)}
              className="mt-2"
            >
              보내기
            </Button>
          </>
        )}

        {shown === 'done' && (
          <>
            <p className="text-section-title leading-[1.55] text-fg-sub tablet:text-dialog-body">
              이번 주 안에는 언제든 고칠 수 있어요.
              <br className="tablet:hidden" /> 집계에는 한 번만 들어가요.
            </p>
            {/*
            홈 화면 앱이어야 알림을 받는 기기(needs-install)에만 설치 안내로 잇는다. 이미 받을 수 있는 기기(supported)는
            설치가 필요 없고 알림 켜기(푸시 구독)는 2단계라, 받을 수 없는 브라우저(unsupported)는 설치해도 받지 못해,
            판단 전(null)은 기기를 몰라 그리지 않는다 (docs/design/SCREENS.md "보고 완료").
            앱 안 이동이라 설치 안내의 닫기가 루트의 이동 기록(useNavTrail)을 보고 router.back() 으로 이 완료 화면에 돌아온다
          */}
            {pushSupport === 'needs-install' && (
              <Link
                href={navHref(INSTALL_PATH, installSearch(regionCode, searchParams))}
                className="mt-3 flex items-center justify-between gap-3 rounded-button bg-section p-4 text-left tablet:mt-0"
              >
                <span className="flex flex-col gap-0.5">
                  <span className="text-body font-semibold text-fg">
                    다음 주 월요일에 알려드릴까요?
                  </span>
                  <span className="text-sub text-fg-sub">
                    홈 화면에 추가하면 알림을 받을 수 있어요
                  </span>
                </span>
                <ChevronRightIcon className="shrink-0 text-fg-muted" />
              </Link>
            )}
          </>
        )}

        {/* 완료가 아닌 단계에서 보내기에 실패하면 여기에 알린다 */}
        {shown !== 'done' && <ToastRegion toast={toast} onAction={dismiss} />}
      </Modal>

      {/* 주소로 바로 연 공유 시트(쌓은 기록 없음)의 닫기는 사이트를 떠나지 않게 완료로 바꾼다 */}
      <ShareSheet open={step === 'share'} onClose={() => param.back('done')} link={shareLink} />
    </>
  )
}

const SEND_FAILED_MESSAGE = '보내지 못했어요. 잠시 뒤 다시 보내 주세요.'
const UNDO_FAILED_MESSAGE = '되돌리지 못했어요. 잠시 뒤 다시 시도해 주세요.'
const REGION_CHANGED_MESSAGE = '내 동네 정보가 바뀌었어요. 확인한 뒤 다시 보내 주세요.'
const REGION_LOADING_MESSAGE = '내 동네를 불러오고 있어요. 잠시 뒤 다시 보내 주세요.'
const REGION_FAILED_MESSAGE = '내 동네를 불러오지 못해 보내지 못했어요. 잠시 뒤 다시 보내 주세요.'
const REGION_MISSING_MESSAGE = '내 동네를 고르면 보낼 수 있어요. 내 정보의 내 동네에서 골라 주세요.'

const STEP_TITLE: Record<Exclude<ReportStep, 'share'>, ReactNode> = {
  start: (
    <>
      지난 7일 동안
      <br className="tablet:hidden" /> 건강은 어땠나요?
    </>
  ),
  symptom: '어떤 증상이었나요?',
  confirm: '이렇게 보낼게요',
  done: '이번 주 보고를 받았어요',
}

/**
 * 주소의 단계를 화면에 보일 단계로 바꾼다. 앞 단계를 거치지 않으면 보일 수 없는 단계는 돌려보낸다.
 * - 확인: 고른 증상이 없으면(새로고침 · 주소로 바로 들어옴) 증상 고르기
 * - 완료 · 함께 채우기: 이번 주에 보낸 보고가 없으면 시작
 * 모르는 값이거나 쿼리가 없으면 닫힌 상태(null)다.
 */
export function resolveStep(
  value: string | null,
  { hasSelection, submitted }: { hasSelection: boolean; submitted: SubmittedReport | null },
): ReportStep | null {
  const step = REPORT_STEPS.find((item) => item === value)
  if (!step) return null
  if (step === 'confirm' && !hasSelection) return 'symptom'
  if ((step === 'done' || step === 'share') && !submitted) return 'start'
  return step
}
