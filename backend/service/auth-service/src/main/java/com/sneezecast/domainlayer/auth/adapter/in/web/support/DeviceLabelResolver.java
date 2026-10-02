package com.sneezecast.domainlayer.auth.adapter.in.web.support;

import org.springframework.stereotype.Component;

/**
 * User-Agent 를 기기 목록에 보일 짧은 이름("OS · 브라우저", 예: {@code iPhone · Safari})으로 줄인다. 외부 파서 없이 흔한 조합만 알아본다.
 *
 * <p><b>User-Agent 원문과 IP 는 저장하지 않는다</b> (개인정보 최소 수집). 세션에는 이 결과만 남는다. 모르는 값은 {@code 알 수 없는 기기} 이고,
 * 결과는 {@value #MAX_LENGTH}자를 넘지 않는다.
 */
@Component
public class DeviceLabelResolver {

    static final String UNKNOWN = "알 수 없는 기기";
    static final int MAX_LENGTH = 50;
    private static final String SEPARATOR = " · ";

    public String resolve(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UNKNOWN;
        }
        String os = resolveOs(userAgent);
        String browser = resolveBrowser(userAgent);
        String label;
        if (os == null && browser == null) {
            label = UNKNOWN;
        } else if (os == null || browser == null) {
            label = os == null ? browser : os;
        } else {
            label = os + SEPARATOR + browser;
        }
        return label.length() > MAX_LENGTH ? label.substring(0, MAX_LENGTH) : label;
    }

    /** iOS UA 에도 {@code like Mac OS X} 가, Android UA 에도 {@code Linux} 가 들어 있어 기기 쪽을 먼저 본다. */
    private static String resolveOs(String userAgent) {
        if (userAgent.contains("iPhone")) {
            return "iPhone";
        }
        if (userAgent.contains("iPad")) {
            return "iPad";
        }
        if (userAgent.contains("Android")) {
            // 삼성 기기는 모델명이 SM- 으로 시작한다 (예: SM-S918N). 모델을 가린 UA(Chrome 축약 UA)면 Android 로 남는다.
            return userAgent.contains("SM-") ? "Galaxy" : "Android";
        }
        if (userAgent.contains("Macintosh") || userAgent.contains("Mac OS X")) {
            return "Mac";
        }
        if (userAgent.contains("Windows")) {
            return "Windows";
        }
        if (userAgent.contains("Linux")) {
            return "Linux";
        }
        return null;
    }

    /**
     * 파생 브라우저 · 인앱 브라우저는 UA 에 Chrome · Safari 토큰을 함께 싣는다. 그래서 고유 토큰을 먼저 보고 Chrome → Safari 순으로 내려간다.
     */
    private static String resolveBrowser(String userAgent) {
        if (userAgent.contains("SamsungBrowser")) {
            return "삼성 인터넷";
        }
        if (userAgent.contains("KAKAOTALK")) {
            return "카카오톡";
        }
        if (userAgent.contains("NAVER")) {
            return "네이버";
        }
        if (userAgent.contains("Whale")) {
            return "Whale";
        }
        if (userAgent.contains("Edg/") || userAgent.contains("EdgA/") || userAgent.contains("EdgiOS/")) {
            return "Edge";
        }
        if (userAgent.contains("Firefox/") || userAgent.contains("FxiOS")) {
            return "Firefox";
        }
        if (userAgent.contains("Chrome/") || userAgent.contains("CriOS")) {
            return "Chrome";
        }
        if (userAgent.contains("Safari")) {
            return "Safari";
        }
        return null;
    }
}
