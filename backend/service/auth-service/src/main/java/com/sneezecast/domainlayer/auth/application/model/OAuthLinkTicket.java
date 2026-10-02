package com.sneezecast.domainlayer.auth.application.model;

import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;

/**
 * 기존 이메일 계정에 소셜 로그인을 연결할지 묻는 확인표에 묶어 두는 값. 확인을 받은 뒤에만 연결한다(자동 연결하지 않는다).
 *
 * @param memberId 연결할 이메일 계정
 * @param provider 연결할 소셜 제공자
 */
public record OAuthLinkTicket(
    long memberId,
    OAuthProvider provider
) {

}
