import type { ReviewCandidate } from '@/features/admin/types'

/**
 * 목 후보를 처리 요청에 싣는 값(초안 · 버전)이 있는 모양으로 좁힌다. 화면 모델은 안내가 없는 후보(실데이터)를 위해 둘 다 선택 값이지만
 * 목 후보는 늘 있다 — 없으면 테스트가 잘못 고른 것이라 던진다
 */
export function processable(candidate: ReviewCandidate | undefined) {
  if (candidate?.version === undefined || candidate.draft === undefined) {
    throw new Error('처리할 수 있는 목 후보가 아니다')
  }
  return { ...candidate, version: candidate.version, draft: candidate.draft }
}
