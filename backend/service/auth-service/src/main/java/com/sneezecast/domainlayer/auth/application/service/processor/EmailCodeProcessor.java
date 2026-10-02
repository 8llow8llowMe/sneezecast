package com.sneezecast.domainlayer.auth.application.service.processor;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.model.EmailCodePurpose;
import com.sneezecast.domainlayer.auth.application.port.out.EmailVerificationStorePort;
import com.sneezecast.domainlayer.auth.application.service.support.VerificationCodeGenerator;
import com.sneezecast.global.properties.EmailSendLimitProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 이메일 인증코드의 발급 · 확인 규칙 — 가입({@link EmailVerificationProcessor})과 비밀번호 재설정({@link PasswordResetProcessor})이 함께 쓴다.
 *
 * <p>둘은 한도 · 수명({@code auth.email-send.*})과 브루트포스 방어(IP 발송 상한 · 쿨다운 · 오입력 상한 · IP 검증 상한)가 같아야 한다. 흐름을 두
 * 처리기에 복사하면 한쪽만 고쳐져 방어가 조용히 어긋나므로 한곳에 두고, 저장 키만 {@link EmailCodePurpose} 로 나눈다. 코드를 맞힌 다음 무엇을
 * 남기는지(가입 인증 완료 표시 / 재설정 토큰)와 누구에게 어떤 메일을 보내는지는 각 처리기가 정한다.
 *
 * <p>인자로 받는 이메일은 호출자가 이미 정규화한 값이다.
 */
@Service
@RequiredArgsConstructor
public class EmailCodeProcessor {

    private final EmailVerificationStorePort emailVerificationStorePort;
    private final EmailSendLimitProperties limits;
    private final VerificationCodeGenerator verificationCodeGenerator;

    /**
     * 새 코드를 만들어 저장하고 돌려준다. 메일 발송은 호출자가 한다 — 가입 여부 · 회원 상태에 따라 코드를 보내지 않는 경우(미끼 코드)가 있다.
     *
     * @throws AuthException IP 발송 상한(AUTH_002) · 재발송 쿨다운(AUTH_001)
     */
    public String issue(EmailCodePurpose purpose, String email, String clientIp) {
        // 1. IP 발송 상한 — 조회만 한다. 쿨다운에 막힐 요청까지 세면 정상 사용자의 재시도가 상한을 갉아먹는다.
        if (emailVerificationStorePort.findIpSendCount(purpose, clientIp) >= limits.ipMaxSendCount()) {
            throw new AuthException(AuthErrorCode.EMAIL_SEND_IP_LIMITED);
        }

        // 2. 재발송 쿨다운 — 가입 여부 판별보다 먼저 걸어 존재 여부를 떠보는 요청에도 같은 비용을 물린다.
        if (!emailVerificationStorePort.tryAcquireCooldown(purpose, email, limits.resendCooldown())) {
            throw new AuthException(AuthErrorCode.EMAIL_CODE_COOLDOWN);
        }

        // 3. 실제로 발송하는 요청만 IP 상한에 센다. 조회와 증가 사이의 동시 요청으로 상한을 몇 건 넘을 수 있다 — 보조 방어라 받아들인다.
        emailVerificationStorePort.increaseIpSendCount(purpose, clientIp, limits.ipWindow());

        // 4. 가입 여부와 무관하게 코드를 저장하고 이전 실패 카운터를 지운다 — 이후 검증 흐름이 가입 · 미가입에서 똑같아야 한다.
        String code = verificationCodeGenerator.generate();
        emailVerificationStorePort.saveCode(purpose, email, code, limits.codeTtl());
        emailVerificationStorePort.clearVerifyFailures(purpose, email);
        return code;
    }

    /**
     * 코드를 확인한다. 맞으면 아무것도 지우지 않고 돌아온다 — 호출자가 결과(인증 완료 표시 · 재설정 토큰)를 먼저 저장한 뒤 {@link #complete} 를
     * 불러 중간 장애 때 "코드만 소비된" 상태를 피한다.
     *
     * @throws AuthException IP 검증 상한(AUTH_010) · 코드 없음 · 만료(AUTH_004) · 불일치(AUTH_003) · 오입력 상한(AUTH_005, 코드를 지운다)
     */
    public void verify(EmailCodePurpose purpose, String email, String code, String clientIp) {
        // IP 검증 상한 — 여러 이메일에 걸쳐 코드를 대입하는 시도를 늦춘다. 저장소 장애에는 fail-open 이다.
        if (emailVerificationStorePort.increaseIpVerifyCount(purpose, clientIp, limits.verifyIpWindow()) > limits.verifyIpMaxCount()) {
            throw new AuthException(AuthErrorCode.EMAIL_VERIFY_IP_LIMITED);
        }

        String storedCode = emailVerificationStorePort.findCode(purpose, email)
            .orElseThrow(() -> new AuthException(AuthErrorCode.EXPIRED_EMAIL_CODE));

        // 앞뒤 공백(복사 · 붙여넣기)은 사용자 실수로 보고 맞춘다.
        if (!storedCode.equals(code.strip())) {
            // 실패가 쌓이면 코드를 무효화해 브루트포스를 막는다 (6자리 코드 · 짧은 TTL 이라도 상한 없이는 표면이 열려 있다).
            long failures = emailVerificationStorePort.increaseVerifyFailureCount(purpose, email, limits.codeTtl());
            if (failures >= limits.maxVerifyFailures()) {
                emailVerificationStorePort.deleteCode(purpose, email);
                throw new AuthException(AuthErrorCode.EMAIL_CODE_ATTEMPTS_EXCEEDED);
            }
            throw new AuthException(AuthErrorCode.INVALID_EMAIL_CODE);
        }
    }

    /** 확인을 마친 코드와 실패 카운터를 지운다. 결과를 저장한 뒤에 부른다. */
    public void complete(EmailCodePurpose purpose, String email) {
        emailVerificationStorePort.deleteCode(purpose, email);
        emailVerificationStorePort.clearVerifyFailures(purpose, email);
    }
}
