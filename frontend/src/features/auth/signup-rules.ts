/**
 * 이메일 가입 입력 규칙 (Signup-email · Signup-account 시안). 화면은 제출할 때 이 결과로 오류를 보인다.
 * 서버도 같은 규칙으로 다시 검사한다 — 여기 규칙은 미리 알려 주는 용도다.
 */

export const PASSWORD_MIN_LENGTH = 8
/** 백엔드 상한(BCrypt 72바이트 한도 안에 두는 값). 입력칸은 이 길이로 자르지 않는다 — 붙여 넣은 값이 조용히 잘리지 않게 */
export const PASSWORD_MAX_LENGTH = 20
export const NICKNAME_MIN_LENGTH = 2
export const NICKNAME_MAX_LENGTH = 10

/** `이름@도메인.최상위` 꼴인지. 실제로 받을 수 있는지는 인증 코드가 확인한다 */
export function isEmailFormat(value: string): boolean {
  return /^[^\s@]+@[^\s@.]+(\.[^\s@.]+)+$/.test(value.trim())
}

/**
 * 8~20자 · 영문과 숫자를 함께 · 공백 없이 썼는지 (백엔드 #56 규칙, 특수문자는 선택).
 * 공백은 띄어쓰기뿐 아니라 탭 · 줄바꿈 · 전각 공백 같은 모든 공백 문자를 막는다.
 */
export function passwordProblem(password: string): 'rule' | null {
  const ok =
    password.length >= PASSWORD_MIN_LENGTH &&
    password.length <= PASSWORD_MAX_LENGTH &&
    /[A-Za-z]/.test(password) &&
    /[0-9]/.test(password) &&
    !/\s/.test(password)
  return ok ? null : 'rule'
}

export function confirmProblem(password: string, confirm: string): 'mismatch' | null {
  return password === confirm ? null : 'mismatch'
}

/** 닉네임 글자 수. 앞뒤 공백은 세지 않고, 한글 · 이모지도 한 글자로 센다 */
export function nicknameLength(nickname: string): number {
  return [...nickname.trim()].length
}

export function nicknameProblem(nickname: string): 'length' | null {
  const length = nicknameLength(nickname)
  return length >= NICKNAME_MIN_LENGTH && length <= NICKNAME_MAX_LENGTH ? null : 'length'
}
