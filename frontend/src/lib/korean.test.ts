import { describe, expect, it } from 'vitest'

import { withGwaWa, withIGa } from './korean'

describe('withGwaWa', () => {
  it.each([
    ['망원동', '과'],
    ['역삼', '과'],
    ['서교', '와'],
    ['역삼1', '과'],
    ['역삼2', '와'],
    ['abc', '과'],
    ['  신사동  ', '과'],
  ] as const)('%s 뒤에는 %s', (word, josa) => {
    expect(withGwaWa(word)).toBe(josa)
  })
})

describe('withIGa', () => {
  it.each([
    ['○○1동', '이'],
    ['삼청', '이'],
    ['서교', '가'],
    ['역삼2', '가'],
  ] as const)('%s 뒤에는 %s', (word, josa) => {
    expect(withIGa(word)).toBe(josa)
  })
})
