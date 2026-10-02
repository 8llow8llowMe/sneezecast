/**
 * 지도 자리 (태블릿 · 데스크톱 홈). 행정동 색칠 지도는 SGIS 경계 · 지도 라이브러리가 붙을 때 S04 지도(`features/map`)와 함께 만든다.
 *
 * 그 전까지 시안과 같은 틀(테두리 · 모서리 · 왼쪽 위 기준 표시)만 두고 빈 자리임을 글로 밝힌다.
 * 범례는 지도 색과 짝이라 지도가 생길 때 함께 단다.
 */
export function MapPlaceholder({ weekLabel }: { weekLabel: string }) {
  return (
    <div className="relative flex min-h-90 grow items-center justify-center overflow-hidden rounded-card border border-divider bg-section">
      <div className="absolute top-4 left-4 flex h-9 items-center gap-2 rounded-chip border border-divider bg-bg px-3.5 text-sub font-semibold text-fg">
        <span aria-hidden="true" className="block size-2 rounded-chip bg-fg-muted" />
        시민 자가보고 · {weekLabel}
      </div>
      <p className="text-body text-fg-muted">행정동 지도는 준비하고 있어요</p>
    </div>
  )
}
