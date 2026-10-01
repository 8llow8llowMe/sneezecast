import { notFound } from 'next/navigation'

/**
 * 라우트 오류 화면(`app/error.tsx`, State-error) 확인용. 개발 서버에서 그릴 때마다 오류를 던진다 — `다시 시도` 를 눌러도 같은 화면이다.
 *
 * **개발 서버에서만 열린다.** 프로덕션에서는 `notFound()` 가 먼저라 빌드의 정적 생성이 오류 없이 404 페이지를 만든다.
 */
export default function ThrowPage() {
  if (process.env.NODE_ENV === 'production') notFound()
  throw new Error('dev: 오류 화면 확인용')
}
