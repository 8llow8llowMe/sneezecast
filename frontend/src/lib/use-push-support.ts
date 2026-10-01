'use client'

import { useSyncExternalStore } from 'react'
import { useSearchParams } from 'next/navigation'

import {
  detectPushSupport,
  type DevicePlatform,
  MOCK_PUSH_PARAM,
  parseMockPush,
  type PushSupport,
  readPushEnvironment,
} from './push-support'

// 기기 · 브라우저 값은 열린 동안 바뀌지 않는다고 본다(홈 화면 앱으로 바뀌면 새 페이지로 열린다)
const noopSubscribe = () => () => {}
const serverSnapshot = () => null
const clientPushSupport = (): PushSupport => detectPushSupport(readPushEnvironment(window))
// 터치 지점 수가 없는 환경(jsdom 등)도 readPushEnvironment 가 0 으로 읽는다
const clientPlatform = (): DevicePlatform => readPushEnvironment(window).platform

/**
 * 이 기기에서 웹 푸시를 받을 수 있는지 (`push-support.ts`). 서버는 기기를 몰라 **서버 · 하이드레이션 첫 그림은 null** 이고,
 * 그 뒤 다시 그릴 때 브라우저 값으로 바뀐다. null 이면 미지원 안내를 그리지 않는다(지원되는 기기에서 잠깐 보이지 않게).
 *
 * QA 용 `?mock-push=supported|needs-install|unsupported` 덮어쓰기도 하이드레이션 뒤에만 따른다 — 첫 그림을 서버와 맞춘다.
 * 덮어쓰기는 실제 푸시 구독을 붙일 때 지운다.
 */
export function usePushSupport(): PushSupport | null {
  const override = parseMockPush(useSearchParams().get(MOCK_PUSH_PARAM))
  const detected = useSyncExternalStore(noopSubscribe, clientPushSupport, serverSnapshot)
  if (detected === null) return null
  return override ?? detected
}

/** 기기 종류 (설치 안내 묶음 고르기). 서버 · 하이드레이션 첫 그림은 null 이다 */
export function useDevicePlatform(): DevicePlatform | null {
  return useSyncExternalStore(noopSubscribe, clientPlatform, serverSnapshot)
}
