package com.sneezecast.domainlayer.auth.application.command;

import com.sneezecast.domainlayer.member.application.service.support.EmailNormalizer;
import lombok.Builder;

/**
 * 이메일 회원가입 명령. web 요청 DTO 에서의 변환은 컨트롤러(adapter)가 맡는다 — application 은 adapter 타입을 모른다
 * (architecture-guide §3).
 */
@Builder
public record AuthGeneralSignupCommand(
    String email,
    String password,
    String nickname,
    boolean termsAgreed,
    boolean privacyAgreed,
    boolean ageOver19Confirmed,
    boolean sensitiveHealthInfoAgreed
) {

    /**
     * 이메일은 trim + 소문자, 닉네임은 앞뒤 공백을 걷은 사본. 인증 완료 표시 키와 저장값이 같은 문자열이어야 해서 Facade 가 가장 먼저 부른다.
     */
    public AuthGeneralSignupCommand normalized() {
        return AuthGeneralSignupCommand.builder()
            .email(EmailNormalizer.normalize(email))
            .password(password)
            .nickname(nickname.strip())
            .termsAgreed(termsAgreed)
            .privacyAgreed(privacyAgreed)
            .ageOver19Confirmed(ageOver19Confirmed)
            .sensitiveHealthInfoAgreed(sensitiveHealthInfoAgreed)
            .build();
    }
}
