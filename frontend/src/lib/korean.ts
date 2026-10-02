/** 숫자를 읽을 때 받침이 있는지 (영 일 이 삼 사 오 육 칠 팔 구) */
const DIGIT_HAS_FINAL = [true, true, false, true, false, false, true, true, true, false]

/**
 * 낱말 끝 글자에 받침이 있는지. 한글 음절과 숫자만 판단하고, 그 밖의 글자(영문 · 기호)는 받침이 있다고 본다
 * — 시안 표기("과")와 같은 쪽으로 기운다.
 */
function hasFinalConsonant(word: string): boolean {
  const last = word.trim().at(-1)
  if (last === undefined) return true
  const code = last.charCodeAt(0)
  if (code >= 0xac00 && code <= 0xd7a3) return (code - 0xac00) % 28 !== 0
  if (last >= '0' && last <= '9') return DIGIT_HAS_FINAL[Number(last)] ?? true
  return true
}

/** "과/와" 를 받침에 맞춰 고른다. 예: 망원동 → 과, 역삼2 → 와 */
export function withGwaWa(word: string): '과' | '와' {
  return hasFinalConsonant(word) ? '과' : '와'
}

/** "이/가" 를 받침에 맞춰 고른다. 예: ○○1동 → 이, 역삼2 → 가 */
export function withIGa(word: string): '이' | '가' {
  return hasFinalConsonant(word) ? '이' : '가'
}

/** "을/를" 을 받침에 맞춰 고른다. 예: 서교동 → 을, 역삼2 → 를 */
export function withEulReul(word: string): '을' | '를' {
  return hasFinalConsonant(word) ? '을' : '를'
}
