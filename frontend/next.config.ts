import type { NextConfig } from 'next'

// 빌드 설정 파일이라 `@/` 별칭 대신 상대 경로로 부른다
import { SECURITY_HEADERS } from './src/lib/security/security-headers'

const nextConfig: NextConfig = {
  // 배포는 이 산출물(.next/standalone)만 컨테이너에 복사하는 방식을 전제로 한다.
  // 컨테이너 안에서 install / build 를 돌리지 않아 이미지가 작고 배포가 빠르다.
  output: 'standalone',

  // 추적 루트를 이 디렉터리로 고정한다. 비워 두면 Next 가 lock 파일을 찾아 루트를 추론하는데,
  // 레포 최상단에 lock 파일이 생기면 backend/ 까지 훑게 된다.
  // 빌드는 항상 frontend/ 에서 실행하므로 cwd 가 곧 프로젝트 루트다.
  outputFileTracingRoot: process.cwd(),

  // dev 서버는 /_next/* 에 대한 cross-origin 요청을 기본 차단한다.
  // localhost 가 아닌 호스트(127.0.0.1, LAN 주소)로 접근하면 청크가 403 이 되고
  // **SSR HTML 은 정상인데 하이드레이션만 죽는다** — 조용해서 진단이 어렵다.
  allowedDevOrigins: ['127.0.0.1'],

  // `X-Powered-By: Next.js` 를 보내지 않는다 — 프레임워크 · 버전 단서를 덜 준다(#176)
  poweredByHeader: false,

  // 보안 헤더(#176). 모든 경로에 nosniff · Referrer-Policy · Permissions-Policy · X-Frame-Options 를 붙인다(src/lib/security/security-headers.ts).
  // CSP 는 요청마다 nonce 가 바뀌어 여기 두지 않고 proxy.ts 가 화면 요청에 붙인다. 근거는 docs/conventions.md "보안 헤더 · CSP".
  //
  // 카카오 콜백 문서는 주소에 인가 코드(`?code=…`)를 달고 열린다. 화면이 바로 주소에서 지우지만, 그 전에 문서가 부르는 하위 요청 ·
  // 이동에 리퍼러로 실리지 않게 이 경로에만 `no-referrer` 를 둔다(#167). 같은 키는 **뒤 규칙이 이기므로** 콜백 규칙을 맨 뒤에 둔다.
  // 경로는 src/features/onboarding/paths.ts 의 KAKAO_CALLBACK_PATH 와 같아야 한다(app/next-config.test.ts 가 맞춰 본다).
  headers: () =>
    Promise.resolve([
      {
        source: '/(.*)',
        headers: [...SECURITY_HEADERS],
      },
      {
        source: '/login/kakao/callback',
        headers: [{ key: 'Referrer-Policy', value: 'no-referrer' }],
      },
    ]),
}

export default nextConfig
