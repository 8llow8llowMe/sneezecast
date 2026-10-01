package com.sneezecast.domainlayer.auth.application.service.processor;

import com.sneezecast.domainlayer.auth.application.command.AuthGeneralSignupCommand;
import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.application.service.processor.MemberConsentProcessor;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import com.sneezecast.security.common.enums.SecurityRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이메일 회원가입의 DB 구간 — 회원 행과 동의 행을 한 트랜잭션에 남긴다.
 *
 * <p>트랜잭션을 Facade 가 아니라 여기에 건다. 가입 앞뒤의 인증 완료 표시 확인 · 소비가 Redis 왕복이라, Facade 에 걸면 커넥션을 잡은
 * 채 원격 저장소를 기다리고, 커밋 전에 표시를 지웠다가 롤백되면 "가입은 안 됐는데 인증은 소비된" 상태가 생긴다 (architecture-guide §3-1).
 * 이메일 인증 여부 확인과 비밀번호 해시는 호출자({@code AuthWebFacade})가 트랜잭션 밖에서 먼저 한다.
 */
@Service
@RequiredArgsConstructor
public class GeneralSignupProcessor {

    private final MemberRepositoryPort memberRepositoryPort;
    private final MemberConsentProcessor memberConsentProcessor;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * @param command         정규화된 가입 명령
     * @param encodedPassword 트랜잭션 밖에서 계산한 비밀번호 해시
     * @throws AuthException   필수 동의(AUTH_008) · 만 19세 이상 확인(AUTH_009) 누락
     * @throws MemberException 이미 쓰이는 이메일 (MEMBER_001) — 사전 조회든 동시 가입을 막은 DB unique 제약이든 같은 응답이다
     */
    @Transactional
    public void signup(AuthGeneralSignupCommand command, String encodedPassword) {
        validateRequiredConsents(command);
        validateAgeConfirmed(command);
        validateEmailNotExists(command.email());

        // 동의 이력은 회원 저장 뒤에 남긴다 — memberId 가 있어야 하고, 중복 이메일로 막힌 요청이 동의 행만 남기는 일도 없어야 한다.
        Member member = memberRepositoryPort.save(createMember(command, encodedPassword));
        memberConsentProcessor.recordSignupConsents(member.id(), command.sensitiveHealthInfoAgreed());
    }

    /**
     * web 경계의 {@code @AssertTrue} 와 중복 검사가 아니다. 그쪽은 "요청 형식이 올바른가" 를, 여기는 "남기는 동의 행이 실제 동의를
     * 반영한다" 는 불변식을 지킨다 — 가입 성공 경로는 항상 필수 동의 행을 남기므로, DTO 를 거치지 않는 호출자가 생기는 순간 동의하지
     * 않은 회원의 동의 이력이 만들어진다.
     */
    private void validateRequiredConsents(AuthGeneralSignupCommand command) {
        if (!command.termsAgreed() || !command.privacyAgreed()) {
            throw new AuthException(AuthErrorCode.CONSENT_REQUIRED);
        }
    }

    /** 성인 본인만 보고한다 (루트 CLAUDE.md). 아동 대리 보고 · 법정대리인 동의는 다음 단계다. */
    private void validateAgeConfirmed(AuthGeneralSignupCommand command) {
        if (!command.ageOver19Confirmed()) {
            throw new AuthException(AuthErrorCode.AGE_REQUIREMENT_NOT_MET);
        }
    }

    /** 계정 상태(탈퇴 · 정지)를 드러내지 않도록 상태와 무관하게 같은 응답이다. */
    private void validateEmailNotExists(String email) {
        if (memberRepositoryPort.existsByEmail(email)) {
            throw new MemberException(MemberErrorCode.EXIST_MEMBER_EMAIL);
        }
    }

    private Member createMember(AuthGeneralSignupCommand command, String encodedPassword) {
        return Member.builder()
            .id(snowflakeIdGenerator.generateId())
            .email(command.email())
            .password(encodedPassword)
            .nickname(command.nickname())
            .profileImageUrl(null)
            .profileImageKey(null)
            .role(SecurityRole.USER)
            .provider(null)
            .status(MemberStatus.ACTIVE)
            .withdrawnAt(null)
            .build();
    }
}
