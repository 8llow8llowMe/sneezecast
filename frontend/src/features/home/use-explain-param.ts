'use client'

import { useCallback } from 'react'

import { useModalParam } from '@/lib/use-modal-param'

/** 판단 기준 시트를 여는 주소 (docs/design/SCREENS.md S11 `/?explain=1`) */
export const EXPLAIN_PARAM = 'explain'

/**
 * 판단 기준 시트의 열림 상태. `?explain=1` 이면 열린다.
 * 기록을 쌓고 되돌리는 규칙은 useModalParam 에 있다.
 */
export function useExplainParam() {
  const param = useModalParam(EXPLAIN_PARAM)
  const { open: push } = param

  const openExplain = useCallback(() => push('1'), [push])

  return { open: param.value === '1', openExplain, closeExplain: param.close }
}
