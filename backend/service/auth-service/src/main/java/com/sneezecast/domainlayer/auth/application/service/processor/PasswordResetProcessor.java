package com.sneezecast.domainlayer.auth.application.service.processor;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.model.EmailCodePurpose;
import com.sneezecast.domainlayer.auth.application.port.out.LoginAttemptStorePort;
import com.sneezecast.domainlayer.auth.application.port.out.MailSendPort;
import com.sneezecast.domainlayer.auth.application.port.out.PasswordResetTokenStorePort;
import com.sneezecast.domainlayer.auth.application.service.support.PasswordResetTokenGenerator;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.global.properties.EmailSendLimitProperties;
import com.sneezecast.global.properties.PasswordResetProperties;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 비밀번호 재설정 — 이메일 → 인증코드 → 재설정 토큰 → 새 비밀번호 (프론트 S13-6). 인자로 받는 이메일은 호출자가 이미 정규화한 값이다.
 * 코드 발급 · 확인 규칙과 한도는 가입과 같다({@link EmailCodeProcessor}, {@code auth.email-send.*}). 저장 키만 가입과 따로다.
 *
 * <p><b>가입 여부 · 계정 상태가 응답으로 새지 않게 한다 (계정 열거 방지).</b> 발송은 회원이 없든 · 탈퇴 · 정지든 정상 회원과 똑같이 코드를 저장하고
 * 같은 응답을 낸다. 코드는 정상 회원에게만 메일로 가고, 회원이 없으면 "가입된 계정이 없다" 는 안내만, 탈퇴 · 정지면 아무 메일도 가지 않는다(미끼
 * 코드). 미끼 코드를 맞혀도 같은 모양의 토큰을 주고, 그 토큰의 재설정은 만료와 같은 {@code PASSWORD_RESET_EXPIRED} 로 끝난다.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetProcessor {

    private static final EmailCodePurpose PURPOSE = EmailCodePurpose.PASSWORD_RESET;

    private final EmailCodeProcessor emailCodeProcessor;
    private final PasswordResetTokenStorePort passwordResetTokenStorePort;
    private final MailSendPort mailSendPort;
    private final MemberRepositoryPort memberRepositoryPort;
    private final LoginAttemptStorePort loginAttemptStorePort;
    private final PasswordResetTokenGenerator passwordResetTokenGenerator;
    private final PasswordResetProperties passwordResetProperties;
    private final EmailSendLimitProperties limits;

    public void sendCode(String email, String clientIp) {
        String code = emailCodeProcessor.issue(PURPOSE, email, clientIp);

        Optional<Member> member = memberRepositoryPort.findByEmail(email);
        if (member.isEmpty()) {
            mailSendPort.sendPasswordResetNoAccountNotice(email);
            return;
        }
        // 탈퇴 · 정지 회원에게는 코드도 안내도 보내지 않는다 — 재설정해도 로그인할 수 없고, 상태를 메일로도 드러내지 않는다.
        if (member.get().status() == MemberStatus.ACTIVE) {
            mailSendPort.sendPasswordResetCode(email, code);
        }
    }

    /**
     * 코드를 확인하고 1회용 재설정 토큰을 돌려준다. 실패 코드는 가입 인증과 같다(AUTH_003 · 004 · 005 · 010).
     *
     * <p>토큰 해시를 먼저 저장하고 코드를 지운다 — 중간 장애 때 "코드만 소비된" 상태를 피한다. 미끼 코드를 맞혀도 같은 모양으로 토큰을 준다.
     */
    public String verifyCode(String email, String code, String clientIp) {
        emailCodeProcessor.verify(PURPOSE, email, code, clientIp);

        String resetToken = passwordResetTokenGenerator.generate();
        passwordResetTokenStorePort.saveToken(PasswordResetTokenGenerator.hash(resetToken), email, passwordResetProperties.tokenTtl());
        emailCodeProcessor.complete(PURPOSE, email);
        return resetToken;
    }

    /**
     * 재설정 토큰을 소비하고 비밀번호를 바꿀 회원을 돌려준다.
     *
     * <ol>
     *   <li>IP 시도 횟수를 <b>먼저</b> 올리고 상한을 넘으면 막는다 — 이 뒤의 저장소 조회 · BCrypt 비용을 주지 않는다. 저장소 장애에는 fail-open.</li>
     *   <li>토큰은 원자적으로 꺼내 지운다(1회성). 없음 · 만료 · 이미 씀이면 {@code PASSWORD_RESET_EXPIRED}.</li>
     *   <li>토큰의 이메일에 정상 회원이 없어도(미끼 코드 · 그사이 탈퇴 · 정지) 같은 코드다.</li>
     * </ol>
     */
    public Member consumeToken(String resetToken, String clientIp) {
        if (passwordResetTokenStorePort.increaseIpResetCount(clientIp, limits.verifyIpWindow()) > limits.verifyIpMaxCount()) {
            throw new AuthException(AuthErrorCode.PASSWORD_RESET_IP_LIMITED);
        }

        String email = passwordResetTokenStorePort.consumeToken(PasswordResetTokenGenerator.hash(resetToken))
            .orElseThrow(() -> new AuthException(AuthErrorCode.PASSWORD_RESET_EXPIRED));
        return memberRepositoryPort.findByEmail(email)
            .filter(member -> member.status() == MemberStatus.ACTIVE)
            .orElseThrow(() -> new AuthException(AuthErrorCode.PASSWORD_RESET_EXPIRED));
    }

    /** 재설정을 마친 이메일의 로그인 실패 카운터 · 잠금을 푼다 — 비밀번호를 잊어 잠긴 사용자가 바로 로그인할 수 있게 한다. 저장소 장애는 무시한다. */
    public void releaseLoginLock(String email) {
        loginAttemptStorePort.clearFailures(email);
    }
}
