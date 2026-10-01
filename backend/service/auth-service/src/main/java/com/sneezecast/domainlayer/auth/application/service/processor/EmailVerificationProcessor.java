package com.sneezecast.domainlayer.auth.application.service.processor;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.port.out.EmailVerificationStorePort;
import com.sneezecast.domainlayer.auth.application.port.out.MailSendPort;
import com.sneezecast.domainlayer.auth.application.service.support.VerificationCodeGenerator;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.global.properties.EmailSendLimitProperties;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 가입용 이메일 인증. 상태는 전부 Redis(TTL)이고, 인자로 받는 이메일은 호출자가 이미 정규화한 값이다. 한도 · 수명은
 * {@link EmailSendLimitProperties}({@code auth.email-send.*}).
 *
 * <p><b>가입 여부가 응답으로 새지 않게 한다 (계정 열거 방지).</b> 이미 가입된 이메일에도 미가입과 똑같이 코드를 저장하고(메일로 보내지
 * 않는 무작위 미끼 코드) 실패 카운터를 초기화한다. 그래서 발송 · 검증의 모든 응답(성공 · AUTH_003 · AUTH_004 · AUTH_005 · 쿨다운 ·
 * 상한)이 가입 여부와 무관하게 같다. 가입 여부는 메일함 소유자에게만 안내 메일로 알린다. 미끼 코드를 맞혀(8자 32진, 5회 제한이라 사실상
 * 불가) 인증 완료 표시가 생겨도 가입은 이메일 중복(MEMBER_001)으로 막힌다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationProcessor {

    private final EmailVerificationStorePort emailVerificationStorePort;
    private final MailSendPort mailSendPort;
    private final MemberRepositoryPort memberRepositoryPort;
    private final EmailSendLimitProperties limits;
    private final VerificationCodeGenerator verificationCodeGenerator;

    public void sendCode(String email, String clientIp) {
        // 1. IP 발송 상한 — 조회만 한다. 쿨다운에 막힐 요청까지 세면 정상 사용자의 재시도가 상한을 갉아먹는다.
        if (emailVerificationStorePort.findIpSendCount(clientIp) >= limits.ipMaxSendCount()) {
            throw new AuthException(AuthErrorCode.EMAIL_SEND_IP_LIMITED);
        }

        // 2. 재발송 쿨다운 — 가입 여부 판별보다 먼저 걸어 존재 여부를 떠보는 요청에도 같은 비용을 물린다.
        if (!emailVerificationStorePort.tryAcquireCooldown(email, limits.resendCooldown())) {
            throw new AuthException(AuthErrorCode.EMAIL_CODE_COOLDOWN);
        }

        // 3. 실제로 발송하는 요청만 IP 상한에 센다. 조회와 증가 사이의 동시 요청으로 상한을 몇 건 넘을 수 있다 — 보조 방어라 받아들인다.
        emailVerificationStorePort.increaseIpSendCount(clientIp, limits.ipWindow());

        // 4. 가입 여부와 무관하게 코드를 저장하고 이전 실패 카운터를 지운다 — 이후 검증 흐름이 가입 · 미가입에서 똑같아야 한다.
        String code = verificationCodeGenerator.generate();
        emailVerificationStorePort.saveCode(email, code, limits.codeTtl());
        emailVerificationStorePort.clearVerifyFailures(email);

        // 5. 가입된 이메일에는 코드(미끼)를 보내지 않고 안내 메일만 보낸다. 탈퇴 회원도 파기 전까지는 이메일을 점유한다.
        if (memberRepositoryPort.existsByEmail(email)) {
            mailSendPort.sendAlreadyRegisteredNotice(email);
            return;
        }
        mailSendPort.sendVerificationCode(email, code);
    }

    public void verifyCode(String email, String code, String clientIp) {
        // IP 검증 상한 — 여러 이메일에 걸쳐 코드를 대입하는 시도를 늦춘다. 저장소 장애에는 fail-open 이다.
        if (emailVerificationStorePort.increaseIpVerifyCount(clientIp, limits.verifyIpWindow()) > limits.verifyIpMaxCount()) {
            throw new AuthException(AuthErrorCode.EMAIL_VERIFY_IP_LIMITED);
        }

        String storedCode = emailVerificationStorePort.findCode(email)
            .orElseThrow(() -> new AuthException(AuthErrorCode.EXPIRED_EMAIL_CODE));

        // 코드는 대문자 · 숫자라, 앞뒤 공백과 소문자 입력은 사용자 실수로 보고 맞춘다.
        if (!storedCode.equals(code.strip().toUpperCase(Locale.ROOT))) {
            // 실패가 쌓이면 코드를 무효화해 브루트포스를 막는다 (8자 코드 · 짧은 TTL 이라도 상한 없이는 표면이 열려 있다).
            long failures = emailVerificationStorePort.increaseVerifyFailureCount(email, limits.codeTtl());
            if (failures >= limits.maxVerifyFailures()) {
                emailVerificationStorePort.deleteCode(email);
                throw new AuthException(AuthErrorCode.EMAIL_CODE_ATTEMPTS_EXCEEDED);
            }
            throw new AuthException(AuthErrorCode.INVALID_EMAIL_CODE);
        }

        // 완료 표시를 먼저 저장하고 코드를 지운다 — 중간 장애 때 "코드만 소비된" 상태를 피한다.
        emailVerificationStorePort.saveVerified(email, limits.verifiedTtl());
        emailVerificationStorePort.deleteCode(email);
        emailVerificationStorePort.clearVerifyFailures(email);
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
