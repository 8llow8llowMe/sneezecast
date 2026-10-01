import type { Metadata } from 'next'
import { notFound } from 'next/navigation'

import { AlertBox } from '@/components/alert-box'
import { Badge } from '@/components/badge'
import { Button } from '@/components/button'
import { Checkbox } from '@/components/checkbox'
import { KakaoButton } from '@/components/kakao-button'
import { ListRow } from '@/components/list-row'
import { ProgressBar } from '@/components/progress-bar'
import { Section, SectionBand } from '@/components/section'
import { StatusGauge } from '@/components/status-gauge'
import { StatusWord } from '@/components/status-word'
import { TextField } from '@/components/text-field'
import { REGION_STATUSES } from '@/lib/status'

import { OverlayDemo } from './overlay-demo'

export const metadata: Metadata = {
  title: '공통 컴포넌트',
  robots: { index: false, follow: false },
}

/**
 * 공통 컴포넌트 미리보기. 시안(docs/design/screens/*.dc.html)과 나란히 놓고 간격·문구를 비교하는 용도다.
 *
 * **개발 서버에서만 열린다.** 프로덕션 빌드에서는 404 다. 데이터는 시안의 예시 값이다.
 */
export default function ComponentsPreviewPage() {
  if (process.env.NODE_ENV === 'production') notFound()

  return (
    <main className="flex flex-col pb-10">
      <h1 className="px-page-mobile pt-6 pb-2 text-screen-title font-bold text-fg">
        공통 컴포넌트
      </h1>

      {/* 헤더는 데스크톱 구성까지 봐야 하므로 폭을 제한하지 않는다 */}
      <h2 className="px-page-mobile pt-5 pb-2 text-section-title font-bold text-fg">
        헤더 · 모달 · 토스트 · 탭바
      </h2>
      <OverlayDemo />

      <SectionBand className="mt-6" />

      <div className="mx-auto flex w-full max-w-md flex-col">
        <Section title="상태 라벨 · 게이지">
          <div className="flex flex-col gap-3 pt-2">
            {REGION_STATUSES.map((status) => (
              <div key={status} className="flex flex-col gap-3 rounded-card bg-section p-5">
                <div className="flex items-end justify-between gap-2">
                  <div className="flex flex-col gap-1">
                    <span className="text-sub font-medium text-fg-sub">
                      우리 동네 이번 주 · 시민 자가보고
                    </span>
                    <StatusWord status={status} />
                  </div>
                  <StatusGauge status={status} />
                </div>
                {status === 'insufficient' && (
                  <div className="flex flex-col gap-2">
                    <div className="flex justify-between text-body-strong font-semibold text-fg">
                      <span>우리 동네 자료를 채우는 중</span>
                      <span>64 / 100명</span>
                    </div>
                    <ProgressBar value={64} max={100} label="우리 동네 참여 인원" />
                  </div>
                )}
              </div>
            ))}
          </div>
        </Section>

        <SectionBand />

        <div className="px-page-mobile">
          <ListRow
            kind="link"
            leading={<Badge kind="official" />}
            title="전국 인플루엔자 유행주의보"
            description="질병관리청 · 전국 · 주간 발표 기준"
          />
        </div>

        <SectionBand />

        <Section title="증상별 변화">
          <ListRow
            title="발열·기침·인후통"
            description={<span className="text-status-high-text">많이 늘었어요</span>}
            divider
          />
          <ListRow title="구토·설사" description="조금 줄었어요" divider />
        </Section>

        <SectionBand />

        <Section title="배지">
          <div className="flex gap-2 pt-2">
            <Badge kind="official" />
            <Badge kind="citizen" />
            <Badge kind="review" />
          </div>
        </Section>

        <SectionBand />

        <Section title="버튼">
          <div className="flex flex-col gap-2.5 pt-2">
            <Button fullWidth>우리 동네 변화 보기</Button>
            <Button variant="secondary" fullWidth>
              보고 수정하기
            </Button>
            <Button variant="text">우리 동네 자료 함께 채우기</Button>
            <Button variant="subtle">보고 없이 둘러보기</Button>
            <div className="flex gap-2">
              <Button size="sm">이번 주 건강 보고하기</Button>
              <Button size="sm" disabled>
                비활성
              </Button>
            </div>
          </div>
        </Section>

        <SectionBand />

        {/* 상태를 바꾸려면 클라이언트 상태가 필요해 켜짐 · 꺼짐 모양만 나란히 둔다 */}
        <Section title="체크 상자">
          <div className="flex flex-col pt-2">
            <Checkbox checked readOnly label="성인 본인의 건강 상태만 보고할게요" />
            <Checkbox checked={false} readOnly size="md" label="주간 보고 알림 받기" />
          </div>
        </Section>

        <SectionBand />

        <Section title="입력칸 · 알림 상자 · 카카오 버튼">
          <div className="flex flex-col gap-5 pt-2">
            <TextField label="이메일" type="email" defaultValue="dong@example.com" />
            <TextField label="비밀번호" type="password" defaultValue="dongne2026" />
            <TextField label="닉네임" hint="2~10자로 지어 주세요" />
            <TextField label="이메일" error="이메일 형식이 아니에요" defaultValue="dong@" />
            <AlertBox tone="danger">이메일 또는 비밀번호가 맞지 않아요.</AlertBox>
            <AlertBox tone="info" action={<Button fullWidth>이메일로 로그인</Button>}>
              이 카카오 계정의 이메일은 이미 이메일 회원으로 가입돼 있어요.
            </AlertBox>
            <AlertBox tone="neutral">
              로그인 시도가 많아 잠시 막혔어요. 10분 뒤 다시 시도해 주세요.
            </AlertBox>
            <KakaoButton>카카오로 계속하기</KakaoButton>
          </div>
        </Section>
      </div>
    </main>
  )
}
