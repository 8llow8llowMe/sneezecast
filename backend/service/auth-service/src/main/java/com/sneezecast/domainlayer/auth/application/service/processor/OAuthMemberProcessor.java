package com.sneezecast.domainlayer.auth.application.service.processor;

import com.sneezecast.domainlayer.auth.application.command.AuthOAuthSignupCommand;
import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.model.OAuthLinkTicket;
import com.sneezecast.domainlayer.auth.application.model.OAuthSignupTicket;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.application.service.processor.MemberCommandProcessor;
import com.sneezecast.domainlayer.member.application.service.processor.MemberConsentProcessor;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import com.sneezecast.security.common.enums.SecurityRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 소셜 로그인의 DB 구간 — 가입(회원 + 동의 행)과 기존 이메일 계정 연결. 앞뒤의 제공자 호출 · 표 소비 · 토큰 발급(Redis)은 호출자가 트랜잭션 밖에서 한다
 * (architecture-guide §3-1, {@link GeneralSignupProcessor} 와 같은 이유).
 */
@Service
@RequiredArgsConstructor
public class OAuthMemberProcessor {

    private final MemberRepositoryPort memberRepositoryPort;
    private final MemberConsentProcessor memberConsentProcessor;
    private final MemberCommandProcessor memberCommandProcessor;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 가입표의 이메일 · 닉네임으로 회원을 만들고 가입 동의 이력을 같은 트랜잭션에 남긴다. 비밀번호는 없다 — 카카오로만 로그인한다.
     *
     * <p>필수 동의 검사는 이메일 가입과 같다({@link GeneralSignupProcessor} 의 불변식 설명 참고). 건강정보 동의는 가입 뒤 별도 API 로 받으므로 여기서는
     * 남기지 않는다.
     *
     * @throws AuthException   필수 동의(AUTH_008) · 만 19세 이상 확인(AUTH_009) 누락
     * @throws MemberException 그사이 같은 이메일로 가입됐다(MEMBER_001) — 사전 조회든 동시 가입을 막은 DB unique 제약이든 같은 응답이다
     */
    @Transactional
    public Member signup(OAuthSignupTicket ticket, AuthOAuthSignupCommand command) {
        if (!command.termsAgreed() || !command.privacyAgreed()) {
            throw new AuthException(AuthErrorCode.CONSENT_REQUIRED);
        }
        if (!command.ageOver19Confirmed()) {
            throw new AuthException(AuthErrorCode.AGE_REQUIREMENT_NOT_MET);
        }
        if (memberRepositoryPort.existsByEmail(ticket.email())) {
            throw new MemberException(MemberErrorCode.EXIST_MEMBER_EMAIL);
        }

        Member member = memberRepositoryPort.save(Member.builder()
            .id(snowflakeIdGenerator.generateId())
            .email(ticket.email())
            .password(null)
            .nickname(ticket.nickname())
            .profileImageUrl(null)
            .profileImageKey(null)
            .role(SecurityRole.USER)
            .provider(ticket.provider())
            .status(MemberStatus.ACTIVE)
            .withdrawnAt(null)
            .build());
        memberConsentProcessor.recordSignupConsents(member.id(), false);
        return member;
    }

    /**
     * 이메일 계정에 소셜 로그인을 연결한다(비밀번호는 그대로 — 이메일 로그인도 계속 된다). 확인표를 받은 뒤 계정이 바뀌었을 수 있어 <b>같은 트랜잭션 안에서
     * 다시 확인한다</b> — 여전히 ACTIVE 이고 제공자가 없어야 한다.
     *
     * @throws AuthException 탈퇴 · 정지 · 이미 연결 · 회원 없음은 모두 {@code OAUTH_LINK_NOT_ALLOWED}(409)
     */
    @Transactional
    public Member link(OAuthLinkTicket ticket) {
        memberRepositoryPort.findById(ticket.memberId())
            .filter(member -> member.status() == MemberStatus.ACTIVE && member.provider() == null)
            .orElseThrow(() -> new AuthException(AuthErrorCode.OAUTH_LINK_NOT_ALLOWED));
        return memberCommandProcessor.changeProvider(ticket.memberId(), ticket.provider());
    }
}
