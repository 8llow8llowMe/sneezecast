'use client'

import { useState } from 'react'

import { AppHeader } from '@/components/app-header'
import { Button } from '@/components/button'
import { Modal } from '@/components/modal'
import { TabBar } from '@/components/tab-bar'
import { ToastRegion, useToast } from '@/components/toast'

/** 상호작용이 필요한 오버레이 · 내비게이션 미리보기. 데이터는 시안 예시 값이다 */
export function OverlayDemo() {
  const [modal, setModal] = useState<'report' | 'symptom' | 'explain' | null>(null)
  const { toast, show, dismiss } = useToast()

  const close = () => setModal(null)

  return (
    <div className="flex flex-col gap-6">
      <div className="border border-divider">
        <AppHeader
          regionName="○○동"
          current="home"
          onRegionClick={() => show({ message: '동네 바꾸기를 눌렀어요' })}
          onNotificationClick={() => show({ message: '알림 설정을 눌렀어요' })}
          onReportClick={() => setModal('report')}
        />
      </div>

      <div className="flex flex-col gap-2.5 px-page-mobile">
        <Button fullWidth onClick={() => setModal('report')}>
          보고 시트 열기 (머리줄)
        </Button>
        <Button variant="secondary" fullWidth onClick={() => setModal('symptom')}>
          보고 2단계 열기 (이전 단계 · 단계 표시)
        </Button>
        <Button variant="secondary" fullWidth onClick={() => setModal('explain')}>
          판단 기준 시트 열기 (시트만 머리줄 없음)
        </Button>
        <Button
          variant="text"
          onClick={() =>
            show({
              message: '증상 없음으로 보냈어요',
              action: { label: '되돌리기', onClick: () => show({ message: '보고를 되돌렸어요' }) },
            })
          }
        >
          토스트 띄우기
        </Button>
      </div>

      <ToastRegion toast={toast} onAction={dismiss} className="px-page-mobile" />

      <div className="border border-divider">
        <TabBar current="home" />
      </div>

      <Modal
        open={modal === 'report'}
        onClose={close}
        title={
          <>
            지난 7일 동안
            <br className="tablet:hidden" /> 건강은 어땠나요?
          </>
        }
      >
        <p className="text-body-strong text-fg-sub">
          11월 17일(월)~23일(일) · 성인 본인의 상태만 알려주세요
        </p>
        <Button fullWidth onClick={() => setModal('symptom')}>
          증상이 있었어요
        </Button>
      </Modal>

      <Modal
        open={modal === 'symptom'}
        onClose={close}
        onBack={() => setModal('report')}
        step="1 / 2"
        title="어떤 증상이었나요?"
      >
        <p className="text-body-strong text-fg-sub">여러 개 고를 수 있어요</p>
      </Modal>

      <Modal open={modal === 'explain'} onClose={close} compactSheet title="이렇게 판단했어요">
        <p className="text-body-strong leading-[1.55] text-fg-sub">
          ○○1동 · 11월 17일~23일 · 시민 자가보고
        </p>
      </Modal>
    </div>
  )
}
