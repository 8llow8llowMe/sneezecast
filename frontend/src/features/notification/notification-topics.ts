/* 알림 항목과 그 이름 · 설명 (#195). 내 정보 알림 행 · 알림 설정 화면 · 알림 설정 클라이언트가 같이 쓴다.
 * 내 정보가 화면 모듈 · 클라이언트(목 세션 구독)를 끌어오지 않게 다른 모듈을 불러오지 않는다 */

/** 알림 항목. 시안(Settings)의 알림 섹션에 있는 둘뿐이고 이 순서로 그린다 */
export const NOTIFICATION_TOPICS = ['weeklyReport', 'regionNotice'] as const
export type NotificationTopic = (typeof NOTIFICATION_TOPICS)[number]

/** 항목의 이름 · 설명. 시안(Settings) 알림 섹션의 글자 그대로다 */
export const NOTIFICATION_LABELS: Readonly<
  Record<NotificationTopic, { title: string; description: string }>
> = {
  weeklyReport: { title: '주간 보고 요청', description: '월요일 아침' },
  regionNotice: { title: '검토를 마친 동네 안내', description: '운영자가 발행했을 때' },
}
