package com.sneezecast.domainlayer.auth.application.service.processor;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.port.out.LoginAttemptStorePort;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.global.properties.LoginAttemptProperties;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 이메일 + 비밀번호 로그인의 자격 확인. 토큰 발급은 {@link AuthTokenProcessor} 가 한다.
 *
 * <p><b>응답으로 계정 존재 여부가 새지 않게 한다 (계정 열거 방지).</b>
 * <ul>
 *   <li>미가입 이메일 · 비밀번호 없는(소셜) 계정 · 비밀번호 불일치는 모두 같은 {@code LOGIN_FAILED} 다.</li>
 *   <li>미가입 · 비밀번호 없는 계정에도 더미 해시로 BCrypt 비교를 한 번 돌려 응답 시간을 맞춘다.</li>
 *   <li>실패 카운터 · 잠금은 <b>이메일 키만으로</b> 동작한다 — 미가입 이메일도 같은 임계값에서 같은 {@code LOGIN_ATTEMPT_LOCKED} 로 잠긴다.</li>
 *   <li>회원 상태(탈퇴 · 정지)는 비밀번호가 맞을 때만 드러낸다.</li>
 * </ul>
 */
@Service
public class GeneralLoginProcessor {

    private final MemberRepositoryPort memberRepositoryPort;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptStorePort loginAttemptStorePort;
    private final LoginAttemptProperties limits;
    // 응답 시간 균등화용 더미 해시. 같은 인코더로 만들어 실제 해시와 비용(cost factor)이 같다.
    private final String timingEqualizerHash;

    public GeneralLoginProcessor(MemberRepositoryPort memberRepositoryPort, PasswordEncoder passwordEncoder, LoginAttemptStorePort loginAttemptStorePort,
        LoginAttemptProperties limits) {
        this.memberRepositoryPort = memberRepositoryPort;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptStorePort = loginAttemptStorePort;
        this.limits = limits;
        this.timingEqualizerHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * 자격을 확인하고 로그인할 회원을 돌려준다.
     *
     * <p><b>카운터를 BCrypt 전에 올린다.</b> "읽기 → 비교 → 실패면 증가" 순서면 같은 이메일로 동시에 들어온 요청이 모두 읽기를 통과해 요청 수만큼
     * BCrypt 를 거친다(5회/10분 약속이 깨진다). 먼저 원자적으로 올리고 그 값으로 판정하면, 한 잠금 창에서 비밀번호 비교까지 가는 시도는 이메일당
     * 상한 이하다. 성공하면 이메일 카운터는 지우고 IP 카운터는 자기 몫만 되돌린다 — 남는 값은 실패 수다.
     *
     * @param email    정규화한 이메일
     * @param clientIp IP 시도 상한의 키로만 쓴다
     */
    public Member authenticate(String email, String rawPassword, String clientIp) {
        // 1. IP 상한 — 이메일을 바꿔 가며 대입하는 시도를 늦춘다. 상한을 넘은 시도는 BCrypt 없이 막는다.
        if (loginAttemptStorePort.increaseIpAttemptCount(clientIp, limits.ipWindow()) > limits.ipMaxFailureCount()) {
            throw new AuthException(AuthErrorCode.LOGIN_IP_LIMITED);
        }

        // 2. 이메일 잠금 — 회원 조회보다 먼저 걸어 잠긴 이메일에는 DB 조회 · BCrypt 비용을 주지 않는다.
        if (loginAttemptStorePort.isLocked(email)) {
            throw new AuthException(AuthErrorCode.LOGIN_ATTEMPT_LOCKED);
        }

        // 3. 이메일 시도 횟수를 먼저 올린다. 상한을 넘었으면(동시 요청이 잠금 직전에 몰린 경우) 비교하지 않고 잠근다.
        long attempts = loginAttemptStorePort.increaseFailureCount(email, limits.lockDuration());
        if (attempts > limits.maxFailureCount()) {
            throw lock(email);
        }

        // 4. 비밀번호를 확인한다. 틀리면 계정이 있든 없든 같은 실패이고, 이번이 상한째면 잠근다. 저장소 장애로 카운터가 0 이면(fail-open) 잠그지 않는다.
        Optional<Member> found = memberRepositoryPort.findByEmail(email);
        if (!matchesPassword(rawPassword, found.map(Member::password).orElse(null))) {
            if (attempts >= limits.maxFailureCount()) {
                throw lock(email);
            }
            throw new AuthException(AuthErrorCode.LOGIN_FAILED);
        }
        Member member = found.get();

        // 5. 비밀번호가 맞았다 — 추측 실패가 아니므로 이메일 카운터를 지우고 IP 몫을 되돌린다. 상태(탈퇴 · 정지)는 그다음에야 드러낸다.
        loginAttemptStorePort.clearFailures(email);
        loginAttemptStorePort.decreaseIpAttemptCount(clientIp);
        requireActive(member);
        return member;
    }

    /** 탈퇴 · 정지 회원이면 상태별 코드로 막는다. 토큰 재발급도 같은 판정을 쓴다. */
    static void requireActive(Member member) {
        switch (member.status()) {
            case WITHDRAWN -> throw new MemberException(MemberErrorCode.WITHDRAWN_MEMBER);
            case SUSPENDED -> throw new MemberException(MemberErrorCode.SUSPENDED_MEMBER);
            case ACTIVE -> {
            }
        }
    }

    private AuthException lock(String email) {
        loginAttemptStorePort.lock(email, limits.lockDuration());
        return new AuthException(AuthErrorCode.LOGIN_ATTEMPT_LOCKED);
    }

    /** 해시가 없으면(미가입 · 소셜 계정) 더미 해시와 비교해 시간을 쓰고 실패로 본다. */
    private boolean matchesPassword(String rawPassword, String encodedPassword) {
        if (encodedPassword == null) {
            passwordEncoder.matches(rawPassword, timingEqualizerHash);
            return false;
        }
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }
}
