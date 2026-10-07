import { Button } from '@/components/button'
import { Modal } from '@/components/modal'

import { InfoBody } from './info-body'
import { INFO_PAGES, type InfoPageKind } from './info-pages'

/**
 * 서비스 안내(S10, #193)를 화면 위 시트로 보인다(#229, 시안 없음). 가입 동의(S02-3)의 `개인정보 수집·이용` `보기` 가
 * `모으는 정보와 보관 기간` 을 연다.
 *
 * **안내 화면(`/me/privacy`)으로 옮겨 가지 않는 이유**: 가입 진행(고른 동네 · 성인 확인 · 가입 초안 · 마무리 진행)은 첫 진입 레이아웃의
 * Provider(`app/(onboarding)/layout.tsx`)에, 동의 체크는 가입 동의 화면 상태에 있다. `/me/*` 는 그 레이아웃 밖이라 옮겨 가면
 * Provider 가 내려가 값이 사라지고, 뒤로 돌아온 가입 동의는 값이 없어 동네 선택(S02-1)으로 돌려보낸다. 시트는 화면을 떠나지 않아
 * 체크까지 그대로다(docs/design/SCREENS.md "서비스 안내").
 *
 * 본문은 안내 화면과 같은 `InfoBody` 다. 모바일은 바텀시트(머리줄 닫기), 태블릿 · 데스크톱은 가운데 대화상자이고, 아래 `확인` 으로도 닫는다.
 */
export function InfoSheet({
  kind,
  open,
  onClose,
}: {
  kind: InfoPageKind
  open: boolean
  onClose: () => void
}) {
  const page = INFO_PAGES[kind]
  return (
    <Modal
      open={open}
      onClose={onClose}
      title={page.title}
      footer={
        <Button fullWidth onClick={onClose}>
          확인
        </Button>
      }
    >
      <InfoBody page={page} sectionHeading="h3" />
    </Modal>
  )
}
