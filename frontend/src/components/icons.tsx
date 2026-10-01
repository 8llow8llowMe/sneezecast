import type { ReactNode, SVGProps } from 'react'

/**
 * 시안의 선 아이콘 (24×24 viewBox). 경로는 시안 .dc.html 에서 그대로 옮겼다.
 *
 * 색은 `currentColor` 라 부모의 글자색(`text-fg`, `text-brand` …)을 따른다.
 * 장식이므로 항상 `aria-hidden` 이다. 아이콘만 있는 버튼은 IconButton 의 `label` 로 이름을 준다.
 */
type IconProps = Omit<SVGProps<SVGSVGElement>, 'children'> & { size?: number }

function Icon({
  size = 24,
  strokeWidth = 2,
  children,
  ...rest
}: IconProps & { children: ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...rest}
    >
      {children}
    </svg>
  )
}

/** 동네 바꾸기 (Home 헤더) */
export function ChevronDownIcon({ size = 20, ...rest }: IconProps) {
  return (
    <Icon size={size} {...rest}>
      <path d="M6 9l6 6 6-6" />
    </Icon>
  )
}

/** 이동 행 끝 (공식 정보 행) */
export function ChevronRightIcon({ size = 20, ...rest }: IconProps) {
  return (
    <Icon size={size} {...rest}>
      <path d="M9 6l6 6-6 6" />
    </Icon>
  )
}

/** 이전 단계 (보고 시트) */
export function ChevronLeftIcon({ size = 24, ...rest }: IconProps) {
  return (
    <Icon size={size} {...rest}>
      <path d="M15 18l-6-6 6-6" />
    </Icon>
  )
}

/** 닫기 (시트 · 대화상자) */
export function CloseIcon({ size = 22, ...rest }: IconProps) {
  return (
    <Icon size={size} {...rest}>
      <path d="M6 6l12 12" />
      <path d="M18 6L6 18" />
    </Icon>
  )
}

/** 선택됨 (보고 증상 항목) */
export function CheckIcon({ size = 20, strokeWidth = 2.4, ...rest }: IconProps) {
  return (
    <Icon size={size} strokeWidth={strokeWidth} {...rest}>
      <path d="M5 12.5l4.5 4.5L19 7.5" />
    </Icon>
  )
}

/** 검색 (동네 선택 검색칸) */
export function SearchIcon({ size = 20, ...rest }: IconProps) {
  return (
    <Icon size={size} {...rest}>
      <circle cx="11" cy="11" r="7" />
      <path d="M20 20l-3.5-3.5" />
    </Icon>
  )
}

/** 안내 (Home 상단 알림 줄) */
export function InfoIcon({ size = 18, ...rest }: IconProps) {
  return (
    <Icon size={size} {...rest}>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 8v5" />
      <path d="M12 16h.01" />
    </Icon>
  )
}

/** 알림 설정 (헤더) */
export function BellIcon({ size = 24, strokeWidth = 1.75, ...rest }: IconProps) {
  return (
    <Icon size={size} strokeWidth={strokeWidth} {...rest}>
      <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
      <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
    </Icon>
  )
}

/** 탭바 · 홈 */
export function HomeIcon({ size = 24, strokeWidth = 1.75, ...rest }: IconProps) {
  return (
    <Icon size={size} strokeWidth={strokeWidth} {...rest}>
      <path d="M3 10.5L12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z" />
    </Icon>
  )
}

/** 탭바 · 지도 */
export function MapIcon({ size = 24, strokeWidth = 1.75, ...rest }: IconProps) {
  return (
    <Icon size={size} strokeWidth={strokeWidth} {...rest}>
      <path d="M9 4L3 6v14l6-2 6 2 6-2V4l-6 2-6-2z" />
      <path d="M9 4v14" />
      <path d="M15 6v14" />
    </Icon>
  )
}

/** 탭바 · 내 정보 */
export function UserIcon({ size = 24, strokeWidth = 1.75, ...rest }: IconProps) {
  return (
    <Icon size={size} strokeWidth={strokeWidth} {...rest}>
      <circle cx="12" cy="8" r="4" />
      <path d="M4 21c1-4 4.2-6.5 8-6.5s7 2.5 8 6.5" />
    </Icon>
  )
}

/**
 * 보고 완료 (Report-done). 네이비 원 안의 흰 체크, 64px.
 * 다른 아이콘과 달리 면을 채우므로 색은 글자색이 아니라 토큰 클래스로 고정한다.
 */
export function SuccessIcon({ size = 64, ...rest }: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 64 64"
      fill="none"
      aria-hidden="true"
      focusable="false"
      {...rest}
    >
      <circle cx="32" cy="32" r="30" className="fill-brand" />
      <path
        d="M20 33l8 8 16-17"
        className="stroke-bg"
        strokeWidth={4}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}
