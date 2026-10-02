package com.sneezecast.domainlayer.member.application.service.processor;

import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.port.out.MemberPasswordAttemptPort;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.global.properties.LoginAttemptProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 비밀번호 변경 · 설정의 규칙 — 어떤 계정이 어느 API 를 쓰는지와 현재 비밀번호 확인(시도 제한 포함).
 *
 * <p>현재 비밀번호 확인 실패 제한은 <b>회원 단위</b>이고 횟수 · 잠금 시간은 로그인과 같은 {@code auth.login.max-failure-count} ·
 * {@code lock-duration}(기본 5회 · 10분)이다. access token 을 손에 넣은 사람이 이 API 로 비밀번호를 대입하지 못하게 한다.
 */
@Service
@RequiredArgsConstructor
public class MemberPasswordProcessor {

    private final PasswordEncoder passwordEncoder;
    private final MemberPasswordAttemptPort memberPasswordAttemptPort;
    private final LoginAttemptProperties limits;

    /** 변경은 비밀번호가 있는 계정만 — 소셜 가입자는 설정 API 를 쓴다. */
    public void requirePasswordSet(Member member) {
        if (member.password() == null) {
            throw new MemberException(MemberErrorCode.PASSWORD_NOT_SET);
        }
    }

    /** 설정은 비밀번호가 없는 계정만 — 이미 있으면 변경 API 를 쓴다(현재 비밀번호 확인 없이 바꾸는 길을 만들지 않는다). */
    public void requirePasswordNotSet(Member member) {
        if (member.password() != null) {
            throw new MemberException(MemberErrorCode.PASSWORD_ALREADY_SET);
        }
    }

    /**
     * 현재 비밀번호를 확인한다.
     *
     * <p><b>카운터를 BCrypt 전에 올린다</b> (로그인과 같은 이유 — 읽고 나중에 올리면 동시 요청이 모두 비교를 거친다). 먼저 원자적으로 올린 값으로
     * 판정하므로 한 잠금 창에서 비교까지 가는 시도는 회원당 상한 이하다. 상한째 틀리면 잠그고(카운터 수명을 잠금 시간으로 다시 건다), 상한을 넘은
     * 시도는 비교 없이 막는다. 맞으면 카운터를 지운다. 저장소 장애로 카운터가 0 이면(fail-open) 잠그지 않는다.
     *
     * @param member 비밀번호가 있는 회원 ({@link #requirePasswordSet} 을 통과한)
     */
    public void verifyCurrentPassword(Member member, String rawCurrentPassword) {
        long attempts = memberPasswordAttemptPort.increaseFailureCount(member.id(), limits.lockDuration());
        if (attempts > limits.maxFailureCount()) {
            throw new MemberException(MemberErrorCode.PASSWORD_CHANGE_LOCKED);
        }

        if (!passwordEncoder.matches(rawCurrentPassword, member.password())) {
            if (attempts >= limits.maxFailureCount()) {
                memberPasswordAttemptPort.lock(member.id(), limits.lockDuration());
                throw new MemberException(MemberErrorCode.PASSWORD_CHANGE_LOCKED);
            }
            throw new MemberException(MemberErrorCode.CURRENT_PASSWORD_MISMATCH);
        }
        memberPasswordAttemptPort.clearFailures(member.id());
    }
}
