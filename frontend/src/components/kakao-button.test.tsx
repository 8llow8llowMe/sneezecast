// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { KakaoButton } from './kakao-button'

describe('KakaoButton', () => {
  it('카카오 가이드 색을 쓰고 심볼은 장식이다', () => {
    render(<KakaoButton>카카오로 계속하기</KakaoButton>)
    const button = screen.getByRole('button', { name: '카카오로 계속하기' })
    expect(button.style.backgroundColor).toBe('rgb(254, 229, 0)')
    expect(button.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true')
  })

  it('꺼지면 누를 수 없다', () => {
    render(<KakaoButton disabled>카카오로 계속하기</KakaoButton>)
    expect(screen.getByRole<HTMLButtonElement>('button').disabled).toBe(true)
  })
})
