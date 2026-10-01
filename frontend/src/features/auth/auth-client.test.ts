import { describe, expect, it } from 'vitest'

import { loginWithEmail, startKakaoLogin } from './auth-client'

describe('loginWithEmail (목)', () => {
  it('재현용 잠긴 이메일이면 locked 다 (대소문자 · 앞뒤 공백 무시)', async () => {
    expect(await loginWithEmail(' Locked@Example.com ', 'anything')).toEqual({ status: 'locked' })
  })

  it('재현용 비밀번호면 wrong 이다', async () => {
    expect(await loginWithEmail('dong@example.com', 'wrong')).toEqual({ status: 'wrong' })
  })

  it('그 밖에는 성공이다', async () => {
    expect(await loginWithEmail('dong@example.com', 'dongne2026')).toEqual({ status: 'ok' })
  })
})

describe('startKakaoLogin (목)', () => {
  it('신규 회원으로 보고 동네 선택으로 보낸다', async () => {
    expect(await startKakaoLogin()).toEqual({ redirectTo: '/setup/region' })
    expect(await startKakaoLogin({ switchAccount: true })).toEqual({ redirectTo: '/setup/region' })
  })
})
