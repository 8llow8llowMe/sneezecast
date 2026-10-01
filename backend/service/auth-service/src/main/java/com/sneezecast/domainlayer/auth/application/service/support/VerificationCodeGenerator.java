package com.sneezecast.domainlayer.auth.application.service.support;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 이메일로 보내는 인증코드 생성기. 시안(Signup-code "인증 코드 6자리")에 맞춰 숫자 6자리를 만든다. 앞자리 0 도 자리로 남긴다.
 */
@Component
public class VerificationCodeGenerator {

    static final int CODE_LENGTH = 6;
    private static final int CODE_BOUND = 1_000_000;

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        return String.format("%0" + CODE_LENGTH + "d", secureRandom.nextInt(CODE_BOUND));
    }
}
