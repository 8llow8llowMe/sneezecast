import { ApiError } from '@/lib/api/api-error'

/**
 * 카카오 가입표 · 연결 확인표를 쓰는 요청(`POST /kakao/signup` · `/kakao/link`)이 실패했을 때, 표를 **이미 잃었는지** (#167).
 *
 * 백엔드는 유스케이스를 부르기 **전에** 표 쿠키를 지우는 `Set-Cookie` 를 응답에 넣고, 서버의 표는 원자 소비(GET+DEL)한다
 * (backend/docs/modules.md "카카오 로그인 · 가입 · 연결"). 그래서 auth 서비스가 업무 오류 봉투(`AUTH_0xx` · `MEMBER_0xx` — 저장소 장애
 * `AUTH_006` · `017` 포함)로 답했으면 표가 남아 있지 않아 다시 눌러도 소용없다 — 카카오 로그인부터 다시 해야 한다.
 *
 * 그 밖은 표가 남았을 수 있어 다시 누를 수 있게 둔다:
 * - 요청 검증 오류(`AUTH_1xx` — 필수 동의 등): 본문까지 오지 않아 쿠키를 건드리지 않는다(백엔드 설명)
 * - 일시 장애 `UNAVAILABLE`(네트워크 · 타임아웃 · 봉투 없는 프록시 오류)와 게이트웨이 오류(`GATEWAY_*`): 응답을 받지 못했거나
 *   서비스에 닿았는지 모른다. 이미 소비됐으면 다시 누를 때 `AUTH_025` · `026` 으로 다시 시작하게 된다
 */
export function kakaoTicketLost(error: unknown): boolean {
  return error instanceof ApiError && /^(AUTH|MEMBER)_0\d\d$/.test(error.code)
}
