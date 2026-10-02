// @vitest-environment jsdom
import { act, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import { resolveAccessToken } from '@/lib/api/access-token'
import { writeBrowserDataSource } from '@/lib/data-source'
import { getSessionSnapshot, resetSessionForTests, setSession } from '@/lib/session/session-store'
import { memberToken, resetApiSession, selectApiSource } from '@/test/api-session'

import { SessionBootstrap } from './session-bootstrap'

beforeEach(() => {
  resetSessionForTests()
})

afterEach(() => {
  resetApiSession()
})

describe('SessionBootstrap', () => {
  it('목데이터 모드면 세션을 되살리지 않는다 (idle 그대로)', () => {
    render(<SessionBootstrap />)
    expect(getSessionSnapshot()).toEqual({ status: 'idle' })
  })

  it('실데이터 모드면 첫 커밋에서 바로 되살린다 (힌트가 없으면 요청 없이 비회원)', () => {
    selectApiSource()
    render(<SessionBootstrap />)
    expect(getSessionSnapshot()).toEqual({ status: 'guest' })
  })

  it('토글로 실데이터가 되면 그때 되살린다', () => {
    render(<SessionBootstrap />)
    expect(getSessionSnapshot()).toEqual({ status: 'idle' })

    act(() => writeBrowserDataSource('api'))
    expect(getSessionSnapshot()).toEqual({ status: 'guest' })
  })

  it('API 계층에 공급자를 끼우고, 해제하면 슬롯을 비운다', async () => {
    const { unmount } = render(<SessionBootstrap />)
    setSession(memberToken({ accessToken: 'access-7' }))
    await expect(resolveAccessToken()).resolves.toBe('access-7')

    unmount()
    await expect(resolveAccessToken()).resolves.toBeNull()
  })
})
