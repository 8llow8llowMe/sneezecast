import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

import { THEME_COLOR } from './theme-color'

/**
 * docs/design/tokens.json(시안 원본)과 src/styles/tokens.css(코드 정본)가 같은 값을 갖는지 확인한다.
 *
 * 매핑에 없는 토큰이 tokens.json 에 새로 생기면 실패한다 — 시안에 토큰이 늘었는데
 * 코드로 옮기지 않은 채 지나가지 않게 하려는 것이다.
 */

const tokensJson = JSON.parse(
  readFileSync(new URL('../../docs/design/tokens.json', import.meta.url), 'utf8'),
) as Record<string, unknown>
const tokensCss = readFileSync(new URL('./tokens.css', import.meta.url), 'utf8')

/** tokens.json 경로 → tokens.css 변수 */
const MAPPING: Record<string, string> = {
  'color.text': '--fg',
  'color.textSub': '--fg-sub',
  'color.textMuted': '--fg-muted',
  'color.divider': '--divider',
  'color.section': '--section',
  'color.bg': '--bg',
  'color.brand': '--brand',
  'color.infoBg': '--info-bg',
  'color.danger': '--danger',
  'color.toast': '--toast',
  'color.toastAction': '--toast-action',
  'color.skeleton': '--skeleton',
  'color.inactiveBar': '--inactive-bar',
  'color.mutedBar': '--muted-bar',
  'status.normal.fill': '--status-normal-fill',
  'status.normal.text': '--status-normal-text',
  'status.slight.fill': '--status-slight-fill',
  'status.slight.text': '--status-slight-text',
  'status.high.fill': '--status-high-fill',
  'status.high.text': '--status-high-text',
  'status.insufficient.fill': '--status-insufficient-fill',
  'status.insufficient.text': '--status-insufficient-text',
  'effects.dim': '--dim',
  'effects.imageCover': '--image-cover',
  'fontSize.status': '--fs-status',
  'fontSize.statusDesktop': '--fs-status-desktop',
  'fontSize.screenTitle': '--fs-screen-title',
  'fontSize.sectionTitle': '--fs-section-title',
  'fontSize.body': '--fs-body',
  'fontSize.bodyStrong': '--fs-body-strong',
  'fontSize.sub': '--fs-sub',
  'fontSize.caption': '--fs-caption',
  'fontSize.tab': '--fs-tab',
  'radius.card': '--rounded-card',
  'radius.button': '--rounded-button',
  'radius.chip': '--rounded-chip',
  'radius.sheet': '--rounded-sheet',
  'radius.dialog': '--rounded-dialog',
  'radius.small': '--rounded-small',
  'space.unit': '--space-unit',
  'space.pagePaddingMobile': '--space-page-mobile',
  'space.pagePaddingTablet': '--space-page-tablet',
  'space.pagePaddingDesktop': '--space-page-desktop',
  'space.sectionBand': '--space-section-band',
  'size.touchMin': '--size-touch-min',
  'size.buttonHeight': '--size-button-height',
  'size.buttonHeightSmall': '--size-button-height-small',
  'size.tabBarMobile': '--size-tab-bar-mobile',
  'size.tabBarTablet': '--size-tab-bar-tablet',
  'size.headerDesktop': '--size-header-desktop',
  'size.headerTablet': '--size-header-tablet',
  'radius.bar': '--rounded-bar',
  'radius.progress': '--rounded-progress',
  'border.hairline': '--border-hairline',
  'border.emphasis': '--border-emphasis',
  'border.selected': '--border-selected',
  'opacity.disabled': '--opacity-disabled',
  'opacity.gaugeInactive': '--opacity-gauge-inactive',
  'fontSize.sheetTitle': '--fs-sheet-title',
  'fontSize.dialogTitle': '--fs-dialog-title',
  'fontSize.dialogBody': '--fs-dialog-body',
  'size.dialogWidthTablet': '--size-dialog-width-tablet',
  'size.dialogWidthDesktop': '--size-dialog-width-desktop',
  'letterSpacing.brand': '--letter-spacing-brand',
}

/**
 * CSS 로 옮기지 않는 tokens.json 항목.
 * - 라벨·설명 문구(status.*.label, status.*.rule, badges, rules.note)
 * - 값이 'none' 인 금지 규칙(effects.shadow, effects.gradient) — globals.css 가 기본값을 지워 강제한다
 * - 서체(font) — layout 에서 pretendard 패키지로 불러온다
 * - 반응형 경계(breakpoints) — 시안 캔버스 폭이라 경계값과 다르다 (globals.css 주석)
 * - 집계 기준(rules) — 화면 로직 상수라 스타일 토큰이 아니다
 */
const NOT_CSS = [
  /^status\.\w+\.(label|rule)$/,
  /^effects\.(shadow|gradient)$/,
  /^(font|badges|breakpoints|rules)\./,
]

function flatten(value: unknown, prefix = ''): [string, string | number][] {
  if (typeof value === 'string' || typeof value === 'number') return [[prefix, value]]
  if (value === null || typeof value !== 'object') return []
  return Object.entries(value).flatMap(([key, child]) =>
    flatten(child, prefix ? `${prefix}.${key}` : key),
  )
}

function cssVar(name: string): string | undefined {
  const match = new RegExp(`${name}:\\s*([^;]+);`).exec(tokensCss)
  return match?.[1]?.trim()
}

/** 단위 없는 숫자 토큰. 나머지 숫자는 px 다 */
const UNITLESS = /^opacity\./

/** 비교용 정규화: 색은 소문자, 숫자는 px 를 붙이고(단위 없는 토큰 제외), 공백을 지운다 */
function normalize(value: string | number, path = ''): string {
  if (typeof value === 'number') return UNITLESS.test(path) ? String(value) : `${value}px`
  return value.toLowerCase().replace(/\s+/g, '')
}

const leaves = flatten(tokensJson).filter(([path]) => !NOT_CSS.some((rule) => rule.test(path)))

describe('tokens.json ↔ tokens.css', () => {
  it('tokens.json 의 스타일 토큰이 모두 매핑에 있다', () => {
    const unmapped = leaves.map(([path]) => path).filter((path) => !(path in MAPPING))
    expect(unmapped).toEqual([])
  })

  it.each(leaves)('%s 값이 tokens.css 와 같다', (path, value) => {
    const name = MAPPING[path]
    expect(name, `${path} 매핑 없음`).toBeDefined()
    const actual = cssVar(name ?? '')
    expect(actual, `${name} 가 tokens.css 에 없음`).toBeDefined()
    expect(normalize(actual ?? '')).toBe(normalize(value, path))
  })

  it('THEME_COLOR 가 color.bg 와 같다', () => {
    const bg = flatten(tokensJson).find(([path]) => path === 'color.bg')?.[1]
    expect(bg).toBeDefined()
    expect(normalize(THEME_COLOR)).toBe(normalize(bg ?? ''))
  })
})
