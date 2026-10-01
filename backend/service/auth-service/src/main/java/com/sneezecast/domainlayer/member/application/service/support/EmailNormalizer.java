package com.sneezecast.domainlayer.member.application.service.support;

import java.util.Locale;

/**
 * 이메일 정규화의 단일 기준점 — trim + 소문자.
 *
 * <p>Redis 키(대소문자 구분)와 DB 조회(collation 기반)가 같은 이메일을 같은 값으로 보게 한다. 인증 코드 키와 가입 시 저장값이 한
 * 글자라도 어긋나면 "인증했는데 미인증" 이 된다. 회원 식별자(email 컬럼)의 주인이 member 컨텍스트라 여기에 두고, auth 의 이메일
 * 인증 · 가입 경로가 그대로 쓴다. 복사본을 만들지 않는다.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
