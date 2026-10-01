package com.sneezecast.domainlayer.auth.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.sneezecast.domainlayer.auth.application.command.AuthGeneralSignupCommand;
import com.sneezecast.domainlayer.member.adapter.out.persistence.MemberConsentRepositoryAdapter;
import com.sneezecast.domainlayer.member.adapter.out.persistence.MemberRepositoryAdapter;
import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.MemberConsentEntity;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberConsentRepository;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberRepository;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.mapper.MemberConsentMapperImpl;
import com.sneezecast.domainlayer.member.application.mapper.MemberMapperImpl;
import com.sneezecast.domainlayer.member.application.service.processor.MemberConsentProcessor;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.global.properties.LegalDocumentProperties;
import com.sneezecast.persistence.config.JpaAuditConfig;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
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
 * 회원 행과 동의 행이 <b>실제 {@code @Transactional} 프록시</b>로 함께 커밋 · 롤백되는지 H2 에서 본다.
 *
 * <p>{@code @DataJpaTest} 의 테스트 트랜잭션을 끈다({@code NOT_SUPPORTED}) — 켜 두면 프로세서가 바깥 트랜잭션에 합류해 롤백 여부를 볼 수
 * 없다. 대신 테스트마다 테이블을 비운다.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import({
    JpaAuditConfig.class, GeneralSignupProcessor.class, MemberConsentProcessor.class,
    MemberRepositoryAdapter.class, MemberConsentRepositoryAdapter.class, MemberMapperImpl.class, MemberConsentMapperImpl.class,
    GeneralSignupTransactionTest.Beans.class
})
class GeneralSignupTransactionTest {

    private static final String EMAIL = "user@example.com";
    private static final String ENCODED_PASSWORD = "$2a$10$encodedPasswordHashForTest";

    @Autowired
    private GeneralSignupProcessor processor;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberConsentRepository memberConsentRepository;

    @MockitoSpyBean
    private MemberRepositoryAdapter memberRepositoryAdapter;

    @MockitoSpyBean
    private MemberConsentRepositoryAdapter memberConsentRepositoryAdapter;

    @AfterEach
    void cleanUp() {
        memberConsentRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("가입하면 회원 1행과 동의 행(설정 버전)이 함께 커밋된다")
    void signupCommitsMemberAndConsents() {
        processor.signup(command(true), ENCODED_PASSWORD);

        assertThat(memberRepository.count()).isEqualTo(1);
        long memberId = memberRepository.findAll().getFirst().getId();
        assertThat(memberConsentRepository.findAll())
            .allSatisfy(consent -> assertThat(consent.getMemberId()).isEqualTo(memberId))
            .extracting(MemberConsentEntity::getType, MemberConsentEntity::getDocumentVersion)
            .containsExactlyInAnyOrder(
                tuple(ConsentType.TERMS_OF_SERVICE, "terms-v1"),
                tuple(ConsentType.PRIVACY_POLICY, "privacy-v1"),
                tuple(ConsentType.AGE_OVER_19, "terms-v1"),
                tuple(ConsentType.SENSITIVE_HEALTH_INFO, "health-v1"));
    }

    @Test
    @DisplayName("동의 저장이 실패하면 회원 행도 롤백된다 — 동의 없는 회원이 남지 않는다")
    void consentFailureRollsBackMember() {
        doThrow(new IllegalStateException("consent store down")).when(memberConsentRepositoryAdapter).saveAll(anyList());

        assertThatThrownBy(() -> processor.signup(command(false), ENCODED_PASSWORD)).isInstanceOf(IllegalStateException.class);

        assertThat(memberRepository.count()).isZero();
        assertThat(memberConsentRepository.count()).isZero();
    }

    @Test
    @DisplayName("사전 조회를 통과한 동시 가입은 uk_member_email 이 막아 MEMBER_001 이 되고, 두 번째 요청의 동의 행은 남지 않는다")
    void concurrentDuplicateIsBlockedByUniqueConstraint() {
        processor.signup(command(false), ENCODED_PASSWORD);
        // 두 요청이 동시에 사전 조회를 통과한 상황 — 두 번째 요청의 조회가 아직 첫 커밋을 보지 못했다.
        doReturn(false).when(memberRepositoryAdapter).existsByEmail(anyString());

        assertThatThrownBy(() -> processor.signup(command(true), ENCODED_PASSWORD))
            .isInstanceOfSatisfying(MemberException.class, e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.EXIST_MEMBER_EMAIL));

        assertThat(memberRepository.count()).isEqualTo(1);
        assertThat(memberConsentRepository.count()).isEqualTo(3);
    }

    private static AuthGeneralSignupCommand command(boolean healthAgreed) {
        return AuthGeneralSignupCommand.builder()
            .email(EMAIL).password("P@ssw0rd!").nickname("재채기탐정")
            .termsAgreed(true).privacyAgreed(true).ageOver19Confirmed(true).sensitiveHealthInfoAgreed(healthAgreed)
            .build();
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
