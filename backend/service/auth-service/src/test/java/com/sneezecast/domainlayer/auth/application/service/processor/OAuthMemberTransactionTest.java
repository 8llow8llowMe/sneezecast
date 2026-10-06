package com.sneezecast.domainlayer.auth.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import com.sneezecast.domainlayer.auth.application.command.AuthOAuthSignupCommand;
import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.model.OAuthLinkTicket;
import com.sneezecast.domainlayer.auth.application.model.OAuthSignupTicket;
import com.sneezecast.domainlayer.member.adapter.out.persistence.MemberConsentRepositoryAdapter;
import com.sneezecast.domainlayer.member.adapter.out.persistence.MemberRepositoryAdapter;
import com.sneezecast.domainlayer.member.adapter.out.persistence.ReportPurgeRequestRepositoryAdapter;
import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.MemberConsentEntity;
import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.MemberEntity;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberConsentRepository;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberRepository;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.mapper.MemberConsentMapperImpl;
import com.sneezecast.domainlayer.member.application.mapper.MemberMapperImpl;
import com.sneezecast.domainlayer.member.application.mapper.ReportPurgeRequestMapperImpl;
import com.sneezecast.domainlayer.member.application.service.processor.MemberCommandProcessor;
import com.sneezecast.domainlayer.member.application.service.processor.MemberConsentProcessor;
import com.sneezecast.domainlayer.member.application.service.processor.ReportPurgeRequestProcessor;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.global.properties.LegalDocumentProperties;
import com.sneezecast.persistence.config.JpaAuditConfig;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import com.sneezecast.security.common.enums.SecurityRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카카오 가입 · 연결이 <b>실제 {@code @Transactional} 프록시</b>로 H2 에 남는 모양을 본다. 테스트 트랜잭션은 끈다({@link GeneralSignupTransactionTest} 와 같은
 * 이유).
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import({
    JpaAuditConfig.class, OAuthMemberProcessor.class, MemberConsentProcessor.class, MemberCommandProcessor.class,
    MemberRepositoryAdapter.class, MemberConsentRepositoryAdapter.class, MemberMapperImpl.class, MemberConsentMapperImpl.class,
    ReportPurgeRequestProcessor.class, ReportPurgeRequestRepositoryAdapter.class, ReportPurgeRequestMapperImpl.class,
    OAuthMemberTransactionTest.Beans.class
})
class OAuthMemberTransactionTest {

    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD_HASH = "$2a$10$encodedPasswordHashForTest";
    private static final AuthOAuthSignupCommand AGREED = AuthOAuthSignupCommand.builder().termsAgreed(true).privacyAgreed(true).ageOver19Confirmed(true)
        .build();

    @Autowired
    private OAuthMemberProcessor processor;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Autowired
    private MemberConsentRepository memberConsentRepository;

    @MockitoSpyBean
    private MemberRepositoryAdapter memberRepositoryAdapter;

    @AfterEach
    void cleanUp() {
        memberConsentRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("가입하면 카카오 회원(비밀번호 없음 · USER · ACTIVE · 표의 닉네임) 1행과 필수 동의 3행이 함께 커밋된다 — 건강정보 동의는 남기지 않는다")
    void signupCommitsKakaoMemberAndRequiredConsents() {
        Member member = processor.signup(new OAuthSignupTicket(OAuthProvider.KAKAO, EMAIL, "재채기😀탐정"), AGREED);

        MemberEntity saved = memberRepository.findById(member.id()).orElseThrow();
        assertThat(saved.getEmail()).isEqualTo(EMAIL);
        assertThat(saved.getPassword()).isNull();
        assertThat(saved.getNickname()).isEqualTo("재채기😀탐정");
        assertThat(saved.getProvider()).isEqualTo(OAuthProvider.KAKAO);
        assertThat(saved.getRole()).isEqualTo(SecurityRole.USER);
        assertThat(saved.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(saved.getProfileImageUrl()).isNull();
        assertThat(memberConsentRepository.findAll()).extracting(MemberConsentEntity::getType)
            .containsExactlyInAnyOrder(ConsentType.TERMS_OF_SERVICE, ConsentType.PRIVACY_POLICY, ConsentType.AGE_OVER_19);
    }

    @Test
    @DisplayName("필수 동의가 빠지면 AUTH_008 · AUTH_009 이고 아무 행도 남지 않는다")
    void missingConsentsLeaveNothing() {
        OAuthSignupTicket ticket = new OAuthSignupTicket(OAuthProvider.KAKAO, EMAIL, "재채기탐정");

        assertThatThrownBy(() -> processor.signup(ticket, AuthOAuthSignupCommand.builder().privacyAgreed(true).ageOver19Confirmed(true).build()))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.CONSENT_REQUIRED));
        assertThatThrownBy(() -> processor.signup(ticket, AuthOAuthSignupCommand.builder().termsAgreed(true).privacyAgreed(true).build()))
            .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.AGE_REQUIREMENT_NOT_MET));

        assertThat(memberRepository.count()).isZero();
        assertThat(memberConsentRepository.count()).isZero();
    }

    @Test
    @DisplayName("이미 쓰이는 이메일은 MEMBER_001 — 사전 조회를 통과한 동시 가입도 uk_member_email 이 막고, 두 번째 요청의 동의 행은 남지 않는다")
    void duplicateEmailIsBlocked() {
        OAuthSignupTicket ticket = new OAuthSignupTicket(OAuthProvider.KAKAO, EMAIL, "재채기탐정");
        processor.signup(ticket, AGREED);

        assertThatThrownBy(() -> processor.signup(ticket, AGREED))
            .isInstanceOfSatisfying(MemberException.class, e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.EXIST_MEMBER_EMAIL));

        doReturn(false).when(memberRepositoryAdapter).existsByEmail(anyString());
        assertThatThrownBy(() -> processor.signup(ticket, AGREED))
            .isInstanceOfSatisfying(MemberException.class, e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.EXIST_MEMBER_EMAIL));

        assertThat(memberRepository.count()).isEqualTo(1);
        assertThat(memberConsentRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("연결하면 이메일 계정의 제공자가 KAKAO 로 바뀌고 비밀번호는 그대로다 (조회한 엔티티의 변경 감지 — PK 위반 없음)")
    void linkKeepsPassword() {
        long memberId = saveEmailMember(MemberStatus.ACTIVE, null);

        Member linked = processor.link(new OAuthLinkTicket(memberId, OAuthProvider.KAKAO));

        assertThat(linked.provider()).isEqualTo(OAuthProvider.KAKAO);
        MemberEntity saved = memberRepository.findById(memberId).orElseThrow();
        assertThat(saved.getProvider()).isEqualTo(OAuthProvider.KAKAO);
        assertThat(saved.getPassword()).isEqualTo(PASSWORD_HASH);
        assertThat(memberRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("확인표를 받은 뒤 탈퇴 · 정지 · 이미 연결 · 회원 없음이면 OAUTH_LINK_NOT_ALLOWED(409) 이고 행은 바뀌지 않는다")
    void linkRechecksMemberState() {
        long withdrawn = saveEmailMember(MemberStatus.WITHDRAWN, null);
        long suspended = saveEmailMember(MemberStatus.SUSPENDED, null);
        long alreadyLinked = saveEmailMember(MemberStatus.ACTIVE, OAuthProvider.KAKAO);

        for (long memberId : new long[] {withdrawn, suspended, alreadyLinked, 999L}) {
            assertThatThrownBy(() -> processor.link(new OAuthLinkTicket(memberId, OAuthProvider.KAKAO)))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.OAUTH_LINK_NOT_ALLOWED));
        }
        assertThat(AuthErrorCode.OAUTH_LINK_NOT_ALLOWED.getHttpStatus().value()).isEqualTo(409);
        assertThat(memberRepository.findById(withdrawn).orElseThrow().getProvider()).isNull();
        assertThat(memberRepository.findById(suspended).orElseThrow().getProvider()).isNull();
    }

    private long saveEmailMember(MemberStatus status, OAuthProvider provider) {
        long id = snowflakeIdGenerator.generateId();
        memberRepositoryAdapter.save(Member.builder().id(id).email(id + "@example.com").password(PASSWORD_HASH).nickname("재채기탐정").role(SecurityRole.USER)
            .provider(provider).status(status).build());
        return id;
    }

    @TestConfiguration
    static class Beans {

        @Bean
        SnowflakeIdGenerator snowflakeIdGenerator() {
            return new SnowflakeIdGenerator(0, 0);
        }

        @Bean
        LegalDocumentProperties legalDocumentProperties() {
            return new LegalDocumentProperties("terms-v1", "privacy-v1", "health-v1");
        }
    }
}
