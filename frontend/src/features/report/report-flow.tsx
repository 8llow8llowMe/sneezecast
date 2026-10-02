'use client'

import { type ReactNode, useState, useSyncExternalStore } from 'react'
import Link from 'next/link'
import { useSearchParams } from 'next/navigation'

import { Button } from '@/components/button'
import { Callout } from '@/components/callout'
import { ChoiceButton } from '@/components/choice-button'
import { ChevronRightIcon, SuccessIcon } from '@/components/icons'
import { Modal } from '@/components/modal'
import { ToastRegion, useToast } from '@/components/toast'
import { INSTALL_PATH, installSearch } from '@/features/install/install-entry'
import { navHref } from '@/lib/nav'
import { useModalParam } from '@/lib/use-modal-param'
import { usePushSupport } from '@/lib/use-push-support'

import {
  cancelReport,
  getSubmittedReport,
  submitReport,
  subscribeSubmittedReport,
  updateReport,
} from './report-client'
import { summarizeAnswer, SYMPTOM_OPTIONS, toggleSymptom } from './symptoms'
import {
  REPORT_STEPS,
  type ReportAnswer,
  type ReportStep,
  type ReportSymptom,
  type ReportWeek,
  type SubmittedReport,
} from './types'

/** 보고 흐름을 여는 주소 (docs/design/SCREENS.md S05 `/?report=start`) */
export const REPORT_PARAM = 'report'

export type ReportFlowProps = {
  week: ReportWeek
  /** 둘러보기 동네(화면이 확인한 코드). 홈 화면 추가 안내에서 주소로 바로 닫을 때 홈 주소에 남긴다 */
  regionCode: string | null
  /** 아직 없는 화면(함께 채우기)으로 가는 동작 */
  onNotReady: (screen: string) => void
}

// 서버는 보낸 보고를 모른다(목 모듈 메모리). 서버와 첫 그림(하이드레이션)은 보낸 보고 없이 그린다
const serverReportSnapshot = (): SubmittedReport | null => null

/**
 * 이번 주에 보낸 보고 (`report-client` 목). 홈 밖에 두어 홈을 떠났다 돌아와도(설치 안내 → 닫기) 완료 단계가 이어진다.
 * 새로고침하면 비고, 하이드레이션 첫 그림도 비어 있다 — 그때 `?report=done` 은 시작 단계로 보인다(`resolveStep`).
 */
function useSubmittedReport(): SubmittedReport | null {
  return useSyncExternalStore(subscribeSubmittedReport, getSubmittedReport, serverReportSnapshot)
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
 *
 * 단계로 나아갈 때는 기록을 쌓아 뒤로 가기가 이전 단계로 이어지고, 보낸 뒤 완료는 기록 없이 바꾼다.
 * 대화상자 하나를 열어 둔 채 단계에 따라 내용만 바꾼다 — 단계마다 닫고 열면 화면이 깜빡인다.
 *
 * 보낸 보고는 이 화면 · 홈의 상태가 아니라 `report-client` 가 갖는다. 완료에서 홈 화면 추가 안내(`/install`)로 갔다가
 * 닫으면(`router.back()`) 홈이 새로 그려져도 `?report=done` 이 완료로 남는다.
 *
 * 시안: Report-start · Report-symptom · Report-confirm · Report-edit · Report-done · Report-done-ok (+ -T · -D), Flow
 */
export function ReportFlow({ week, regionCode, onNotReady }: ReportFlowProps) {
  const param = useModalParam(REPORT_PARAM)
  const searchParams = useSearchParams()
  const submitted = useSubmittedReport()
  const pushSupport = usePushSupport()
  const { toast, show, dismiss } = useToast()
  const [selected, setSelected] = useState<ReportSymptom[]>([])
  const [pending, setPending] = useState(false)

  const step = resolveStep(param.value, { hasSelection: selected.length > 0, submitted })

  async function send(answer: ReportAnswer) {
    // 처음 보내는 "증상 없음" 만 되돌리기를 준다 (Report-done-ok). 수정은 이전 보고로 되돌릴 수 없어 주지 않는다
    const undoable = answer.kind === 'none' && submitted === null
    setPending(true)
    try {
      // 보낸 보고는 report-client 가 갖고 알린다(useSubmittedReport)
      await (submitted ? updateReport(answer) : submitReport(answer))
      // 고른 증상은 보낸 보고에 담겼다. 다시 고칠 때는 보낸 보고에서 채운다 (startSymptoms)
      setSelected([])
      param.replace('done')
      if (undoable) {
        show({ message: '증상 없음으로 보냈어요', action: { label: '되돌리기', onClick: undo } })
      }
    } catch {
      show({ message: '보내지 못했어요. 잠시 뒤 다시 보내 주세요.' })
    } finally {
      setPending(false)
    }
  }

  function undo() {
    void cancelReport().then(() => {
      setSelected([])
      param.replace('start')
    })
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
    <Modal
      open={step !== null}
      onClose={close}
      title={STEP_TITLE[step ?? 'start']}
      step={step === 'symptom' ? '1 / 2' : step === 'confirm' ? '2 / 2' : undefined}
      onBack={
        step === 'symptom'
          ? () => param.back('start')
          : step === 'confirm'
            ? () => param.back('symptom')
            : undefined
      }
      mobileLayout={step === 'done' ? 'screen' : 'sheet'}
      icon={step === 'done' ? <SuccessIcon /> : undefined}
      footer={
        step === 'done' ? (
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
                onClick={() => {
                  dismiss()
                  param.replace('start')
                }}
              >
                보고 수정하기
              </Button>
            </div>
            <Button variant="text" onClick={() => onNotReady('함께 채우기')}>
              우리 동네 자료 함께 채우기
            </Button>
          </>
        ) : undefined
      }
    >
      {step === 'start' && (
        <>
          {submitted && (
            <Callout>
              {submitted.reportedLabel}에 보고했어요. 수정하면 집계에는 마지막 보고만 반영돼요.
            </Callout>
          )}
          <p className="mb-2 text-body-strong text-fg-sub">
            {week.reportPeriodLabel} · 성인 본인의 상태만 알려주세요
          </p>
          <ChoiceButton
            label="증상 없었어요"
            disabled={pending}
            onClick={() => void send({ kind: 'none' })}
          />
          <ChoiceButton label="증상이 있었어요" disabled={pending} onClick={startSymptoms} />
        </>
      )}

      {step === 'symptom' && (
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

      {step === 'confirm' && symptomAnswer && (
        <>
          <div className="flex flex-col gap-1.5 rounded-button bg-section p-4.5">
            <span className="text-sub text-fg-sub">
              {week.weekRangeLabel} · {week.regionName}
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

      {step === 'done' && (
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
      {step !== 'done' && <ToastRegion toast={toast} onAction={dismiss} />}
    </Modal>
  )
}

const STEP_TITLE: Record<ReportStep, ReactNode> = {
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
 * - 완료: 이번 주에 보낸 보고가 없으면 시작
 * 모르는 값이거나 쿼리가 없으면 닫힌 상태(null)다.
 */
export function resolveStep(
  value: string | null,
  { hasSelection, submitted }: { hasSelection: boolean; submitted: SubmittedReport | null },
): ReportStep | null {
  const step = REPORT_STEPS.find((item) => item === value)
  if (!step) return null
  if (step === 'confirm' && !hasSelection) return 'symptom'
  if (step === 'done' && !submitted) return 'start'
  return step
}
