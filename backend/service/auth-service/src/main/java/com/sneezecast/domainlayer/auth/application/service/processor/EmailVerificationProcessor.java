package com.sneezecast.domainlayer.auth.application.service.processor;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.model.EmailCodePurpose;
import com.sneezecast.domainlayer.auth.application.port.out.EmailVerificationStorePort;
import com.sneezecast.domainlayer.auth.application.port.out.MailSendPort;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.global.properties.EmailSendLimitProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 가입용 이메일 인증. 상태는 전부 Redis(TTL)이고, 인자로 받는 이메일은 호출자가 이미 정규화한 값이다. 한도 · 수명은
 * {@link EmailSendLimitProperties}({@code auth.email-send.*}), 발급 · 확인 규칙은 비밀번호 재설정과 같아 {@link EmailCodeProcessor} 에 있다.
 *
 * <p><b>가입 여부가 응답으로 새지 않게 한다 (계정 열거 방지).</b> 이미 가입된 이메일에도 미가입과 똑같이 코드를 저장하고(메일로 보내지
 * 않는 무작위 미끼 코드) 실패 카운터를 초기화한다. 그래서 발송 · 검증의 모든 응답(성공 · AUTH_003 · AUTH_004 · AUTH_005 · 쿨다운 ·
 * 상한)이 가입 여부와 무관하게 같다. 가입 여부는 메일함 소유자에게만 안내 메일로 알린다. 미끼 코드를 맞혀(6자리 숫자, 5회 제한이라 한 코드당
 * 5/1,000,000) 인증 완료 표시가 생겨도 가입은 이메일 중복(MEMBER_001)으로 막힌다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationProcessor {

    private static final EmailCodePurpose PURPOSE = EmailCodePurpose.SIGNUP;

    private final EmailCodeProcessor emailCodeProcessor;
    private final EmailVerificationStorePort emailVerificationStorePort;
    private final MailSendPort mailSendPort;
    private final MemberRepositoryPort memberRepositoryPort;
    private final EmailSendLimitProperties limits;

    public void sendCode(String email, String clientIp) {
        String code = emailCodeProcessor.issue(PURPOSE, email, clientIp);

        // 가입된 이메일에는 코드(미끼)를 보내지 않고 안내 메일만 보낸다. 탈퇴 회원도 파기 전까지는 이메일을 점유한다.
        if (memberRepositoryPort.existsByEmail(email)) {
            mailSendPort.sendAlreadyRegisteredNotice(email);
            return;
        }
        mailSendPort.sendVerificationCode(email, code);
    }

    public void verifyCode(String email, String code, String clientIp) {
        emailCodeProcessor.verify(PURPOSE, email, code, clientIp);

        // 완료 표시를 먼저 저장하고 코드를 지운다 — 중간 장애 때 "코드만 소비된" 상태를 피한다.
        emailVerificationStorePort.saveVerified(email, limits.verifiedTtl());
        emailCodeProcessor.complete(PURPOSE, email);
    }

    /** 가입 직전 검사. 인증 완료 표시가 없거나 수명({@code verified-ttl})이 지났으면 거부한다. */
    public void requireVerified(String email) {
        if (!emailVerificationStorePort.isVerified(email)) {
            throw new AuthException(AuthErrorCode.EMAIL_NOT_VERIFIED);
        }
    }

    /**
     * 가입 커밋 뒤 인증 완료 표시를 지운다. 실패해도 가입 결과를 뒤집지 않는다 — 회원은 이미 만들어졌고, 남은 표시는 TTL 로 사라지며
     * 같은 이메일의 재가입은 중복 이메일로 막힌다.
     */
    public void consumeVerified(String email) {
        try {
            emailVerificationStorePort.deleteVerified(email);
        } catch (AuthException exception) {
            log.warn("email verified flag cleanup failed code={}", exception.getErrorCode().getCode());
        }
    }
}
