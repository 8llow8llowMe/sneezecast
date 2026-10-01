package com.sneezecast.domainlayer.auth.application.port.in;

import com.sneezecast.domainlayer.auth.application.command.AuthGeneralSignupCommand;

public interface AuthWebUseCase {

    /** clientIp 는 IP 기준 발송 상한 검사에만 쓴다. */
    void sendEmailVerificationCode(String email, String clientIp);

    /** clientIp 는 IP 기준 검증 상한 검사에만 쓴다. */
    void verifyEmailVerificationCode(String email, String code, String clientIp);

    void generalSignup(AuthGeneralSignupCommand command);
}
