import { afterEach } from 'vitest'

/**
 * 모든 테스트 파일 앞에서 돈다 (vitest.config.mts `setupFiles`).
 *
 * `globals: false` 라 Testing Library 의 자동 정리가 걸리지 않는다. 그대로 두면 앞 테스트에서
 * 렌더한 DOM 이 남아 `getByRole` 이 여러 개를 찾는다. jsdom 을 켠 파일에서만 정리를 등록한다 —
 * node 환경 파일에서 react-dom 을 불러올 이유가 없다.
 */
if (typeof window !== 'undefined') {
  const { cleanup } = await import('@testing-library/react')
  afterEach(cleanup)

  installDialogStub()
}

/**
 * jsdom 은 `<dialog>` 의 `showModal()` · `close()` 를 구현하지 않는다 (jsdom 27 기준).
 *
 * Modal 컴포넌트가 열고 닫는 흐름만 확인할 수 있게 `open` 속성과 `close` 이벤트만 흉내 낸다.
 * 포커스 가두기 · 뒤 화면 비활성 · Esc 처리는 브라우저 몫이라 여기서 검증하지 않는다 —
 * 그 동작은 /dev/components 를 실제 브라우저로 열어 확인한다.
 */
function installDialogStub() {
  const proto = HTMLDialogElement.prototype as Partial<HTMLDialogElement>
  if (typeof proto.showModal === 'function') return

  proto.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  proto.close = function close(this: HTMLDialogElement) {
    if (!this.hasAttribute('open')) return
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}
