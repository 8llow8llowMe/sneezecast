import { describe, expect, it } from 'vitest'

import { withEulReul, withGwaWa, withIGa } from './korean'

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

describe('withEulReul', () => {
  it.each([
    ['서교동', '을'],
    ['망원2동', '을'],
    ['진접읍', '을'],
    ['역삼2', '를'],
    ['서교', '를'],
  ] as const)('%s 뒤에는 %s', (word, josa) => {
    expect(withEulReul(word)).toBe(josa)
  })
})
