import type { ReportAnswer, ReportSymptom } from './types'

/** 증상 고르기 선택지 (Report-symptom 시안). 순서가 화면 순서다 */
export const SYMPTOM_OPTIONS: readonly { key: ReportSymptom; label: string }[] = [
  { key: 'respiratory', label: '발열·기침·인후통' },
  { key: 'gastrointestinal', label: '구토·설사' },
]

const LABEL: Record<ReportSymptom, string> = {
  respiratory: '발열·기침·인후통',
  gastrointestinal: '구토·설사',
}

/**
 * 증상 하나를 누른 뒤의 선택 목록. 이미 고른 것을 누르면 빼고, 아니면 넣는다.
 * 순서는 늘 선택지 순서로 맞춘다 — 요약 문구가 누른 순서에 따라 바뀌지 않게 한다.
 */
export function toggleSymptom(
  selected: readonly ReportSymptom[],
  key: ReportSymptom,
): ReportSymptom[] {
  const next = selected.includes(key) ? selected.filter((item) => item !== key) : [...selected, key]

  const order = SYMPTOM_OPTIONS.map((option) => option.key)
  return next.sort((a, b) => order.indexOf(a) - order.indexOf(b))
}

/** 확인 · 수정 화면의 한 줄 요약. 예: "증상 있음 · 발열·기침·인후통, 구토·설사" */
export function summarizeAnswer(answer: ReportAnswer): string {
  if (answer.kind === 'none') return '증상 없음'
  return `증상 있음 · ${answer.symptoms.map((key) => LABEL[key]).join(', ')}`
}
