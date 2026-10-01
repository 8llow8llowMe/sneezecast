/**
 * 기본 구성 확인용 빈 홈. S03 홈 화면을 구현할 때 교체한다 (docs/design-guide.md "구현 순서" 2단계).
 */
export default function HomePage() {
  return (
    <main className="mx-auto flex min-h-dvh max-w-xl flex-col justify-center gap-2 px-page-mobile tablet:px-page-tablet desktop:px-page-desktop">
      <h1 className="text-screen-title font-bold text-fg">우리동네체온계</h1>
      <p className="text-body text-fg-sub">화면을 준비하고 있어요.</p>
    </main>
  )
}
