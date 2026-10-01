// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { TextField } from './text-field'

describe('TextField', () => {
  it('라벨로 찾히고 도움말을 설명으로 읽는다', () => {
    render(<TextField label="이메일" type="email" hint="로그인에 쓰는 이메일이에요" />)
    const input = screen.getByRole('textbox', { name: '이메일' })

    expect(input.getAttribute('aria-invalid')).toBeNull()
    expect(input.classList).toContain('border-inactive-bar')
    const described = document.getElementById(input.getAttribute('aria-describedby') ?? '')
    expect(described?.textContent).toBe('로그인에 쓰는 이메일이에요')
  })

  it('오류가 있으면 aria-invalid 를 켜고 오류를 설명 · 알림으로 읽는다', () => {
    render(<TextField label="이메일" hint="도움말" error="이메일 형식이 아니에요" />)
    const input = screen.getByRole('textbox', { name: '이메일' })

    expect(input.getAttribute('aria-invalid')).toBe('true')
    expect(input.classList).toContain('border-danger')
    expect(screen.getByRole('alert').textContent).toBe('이메일 형식이 아니에요')
    expect(input.getAttribute('aria-describedby')).toBe(screen.getByRole('alert').id)
    // 오류가 도움말을 대신한다
    expect(screen.queryByText('도움말')).toBeNull()
  })

  it('빈 문자열 오류 · 도움말은 없는 것과 같다', () => {
    render(<TextField label="이메일" error="" hint="" />)
    const input = screen.getByRole('textbox', { name: '이메일' })
    expect(input.getAttribute('aria-invalid')).toBeNull()
    expect(input.getAttribute('aria-describedby')).toBeNull()
    expect(input.classList).toContain('border-inactive-bar')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('부르는 쪽이 준 설명에 칸 아래 문구를 덧붙인다', () => {
    render(
      <>
        <p id="extra">로그인 결과</p>
        <TextField label="이메일" hint="도움말" aria-describedby="extra" />
      </>,
    )
    const ids = screen.getByRole('textbox', { name: '이메일' }).getAttribute('aria-describedby')
    expect(ids?.split(' ')).toHaveLength(2)
    expect(ids?.startsWith('extra ')).toBe(true)
  })

  it('도움말 · 오류가 없으면 설명을 잇지 않는다', () => {
    render(<TextField label="닉네임" />)
    expect(
      screen.getByRole('textbox', { name: '닉네임' }).getAttribute('aria-describedby'),
    ).toBeNull()
  })

  it('비밀번호는 보기 버튼으로 글자를 보이고 숨긴다', async () => {
    const user = userEvent.setup()
    render(<TextField label="비밀번호" type="password" />)
    const input = screen.getByLabelText('비밀번호', { selector: 'input' })
    expect(input.getAttribute('type')).toBe('password')

    await user.click(screen.getByRole('button', { name: '비밀번호 보기' }))
    expect(input.getAttribute('type')).toBe('text')

    await user.click(screen.getByRole('button', { name: '비밀번호 숨기기' }))
    expect(input.getAttribute('type')).toBe('password')
  })
})
