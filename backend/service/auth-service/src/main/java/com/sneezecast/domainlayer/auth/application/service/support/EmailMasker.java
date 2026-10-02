package com.sneezecast.domainlayer.auth.application.service.support;

/**
 * 화면에 보일 가린 이메일 — 로컬 부분의 첫 글자와 도메인만 남긴다(예: {@code d***@example.com}). 카카오 로그인 연결 확인 화면이 "이 이메일의 계정에
 * 연결할까요?" 를 물을 때 쓴다. 로컬 부분의 길이는 드러내지 않는다.
 */
public final class EmailMasker {

    private static final String MASK = "***";

    private EmailMasker() {
    }

    public static String mask(String email) {
        if (email == null) {
            return MASK;
        }
        int at = email.lastIndexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return MASK;
        }
        return email.substring(0, email.offsetByCodePoints(0, 1)) + MASK + email.substring(at);
    }
}
