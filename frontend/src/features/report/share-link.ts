import { APP_NAME } from '@/lib/app-info'

/*
 * 보고 완료의 `우리 동네 자료 함께 채우기` 공유 링크 (#151, docs/design/SCREENS.md "보고 완료 · 함께 채우기").
 *
 * 링크에는 **홈 주소와 동네 코드(`?region=`)만** 싣는다. 이름 · 건강 · 보고 내용은 물론 추적 파라미터(utm 등) ·
 * QA 덮어쓰기(`mock-*`) · 데이터 출처도 싣지 않는다 — 받은 사람이 같은 동네 홈을 둘러보기로 여는 데 그것 말고는 필요 없다.
 */

/** 메신저로 보낼 때(기기 공유 창)의 제목. 앱 이름이다 */
export const SHARE_TITLE = APP_NAME

/**
 * 메신저로 보낼 때(기기 공유 창) 링크와 함께 가는 문구. 보낸 사람의 건강 상태를 암시하지 않는다.
 * Flow 시안 공유 미리보기의 `우리 동네 건강을 같이 살펴요` 를 따른다
 */
export const SHARE_TEXT = '우리 동네 건강을 같이 살펴요'

/**
 * 공유 링크. `siteUrl`(`clientEnv.siteUrl`) 끝의 `/` 는 하나로 맞추고, 동네가 없으면(모름) 쿼리 없는 홈이다.
 * 동네 코드는 쿼리 값으로 감싸 다른 쿼리가 끼어들지 않게 한다.
 */
export function buildShareLink(siteUrl: string, regionCode: string | null): string {
  const base = siteUrl.replace(/\/+$/, '')
  const query = regionCode ? `?${new URLSearchParams({ region: regionCode }).toString()}` : ''
  return `${base}/${query}`
}
