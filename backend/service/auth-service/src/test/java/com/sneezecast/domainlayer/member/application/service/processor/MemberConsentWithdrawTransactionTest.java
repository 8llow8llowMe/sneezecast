package com.sneezecast.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.sneezecast.domainlayer.member.adapter.out.persistence.MemberConsentRepositoryAdapter;
import com.sneezecast.domainlayer.member.adapter.out.persistence.ReportPurgeRequestRepositoryAdapter;
import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.ReportPurgeRequestEntity;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberConsentRepository;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.ReportPurgeRequestRepository;
import com.sneezecast.domainlayer.member.application.mapper.MemberConsentMapperImpl;
import com.sneezecast.domainlayer.member.application.mapper.ReportPurgeRequestMapperImpl;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.domainlayer.member.domain.enums.PurgeReason;
import com.sneezecast.domainlayer.member.domain.model.MemberConsent;
import com.sneezecast.global.properties.LegalDocumentProperties;
import com.sneezecast.persistence.config.JpaAuditConfig;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 건강정보 동의 철회가 <b>실제 {@code @Transactional} 프록시</b>에서 철회 표시와 파기 요청을 함께 커밋 · 롤백하는지 H2 에서 본다 (entity-design §1-5 —
 * 파기 요청이 철회와 함께 반드시 남아야 한다).
 *
 * <p>테스트 트랜잭션은 끈다({@code NOT_SUPPORTED}) — 켜 두면 처리기가 바깥 트랜잭션에 합류해 롤백 여부를 볼 수 없다. 대신 테스트마다 테이블을 비운다.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import({
    JpaAuditConfig.class, MemberConsentProcessor.class, ReportPurgeRequestProcessor.class,
    MemberConsentRepositoryAdapter.class, ReportPurgeRequestRepositoryAdapter.class, MemberConsentMapperImpl.class, ReportPurgeRequestMapperImpl.class,
    MemberConsentWithdrawTransactionTest.Beans.class
})
class MemberConsentWithdrawTransactionTest {

    private static final long MEMBER_ID = 42L;

    @Autowired
    private MemberConsentProcessor processor;

    @Autowired
    private MemberConsentRepository memberConsentRepository;

    @Autowired
    private ReportPurgeRequestRepository reportPurgeRequestRepository;

    @Autowired
    private MemberConsentRepositoryAdapter memberConsentRepositoryAdapter;

    @MockitoSpyBean
    private ReportPurgeRequestRepositoryAdapter reportPurgeRequestRepositoryAdapter;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void cleanUp() {
        reportPurgeRequestRepository.deleteAll();
        memberConsentRepository.deleteAll();
    }

    @Test
    @DisplayName("철회하면 동의 행의 철회 시각과 파기 요청(사유 HEALTH_CONSENT_WITHDRAWN · 시도 0회 · 미완료)이 같은 시각으로 함께 커밋된다")
    void withdrawCommitsConsentAndPurgeRequest() {
        givenHealthConsent();

        assertThat(processor.withdraw(MEMBER_ID, ConsentType.SENSITIVE_HEALTH_INFO)).isTrue();

        LocalDateTime withdrawnAt = new JdbcTemplate(dataSource).queryForObject("select withdrawn_at from member_consent where id = 1", LocalDateTime.class);
        assertThat(withdrawnAt).isNotNull();
        assertThat(memberConsentRepository.count()).as("새 행을 만들지 않는다").isEqualTo(1);
        assertThat(reportPurgeRequestRepository.findAll()).singleElement().satisfies(request -> {
            assertThat(request.getMemberId()).isEqualTo(MEMBER_ID);
            assertThat(request.getReason()).isEqualTo(PurgeReason.HEALTH_CONSENT_WITHDRAWN);
            assertThat(request.getRequestedAt()).isEqualTo(withdrawnAt);
            assertThat(request.getAttemptCount()).isZero();
            assertThat(request.getCompletedAt()).isNull();
        });
        assertThat(processor.currentStatus(MEMBER_ID).purgePending()).isTrue();
    }

    @Test
    @DisplayName("파기 요청 저장이 실패하면 철회도 롤백된다 — 파기 요청 없이 철회만 남지 않는다")
    void purgeFailureRollsBackWithdrawal() {
        givenHealthConsent();
        doThrow(new IllegalStateException("purge store down")).when(reportPurgeRequestRepositoryAdapter).save(any());

        assertThatThrownBy(() -> processor.withdraw(MEMBER_ID, ConsentType.SENSITIVE_HEALTH_INFO)).isInstanceOf(IllegalStateException.class);

        assertThat(new JdbcTemplate(dataSource).queryForObject("select withdrawn_at from member_consent where id = 1", LocalDateTime.class)).isNull();
        assertThat(reportPurgeRequestRepository.count()).isZero();
        assertThat(processor.currentStatus(MEMBER_ID).healthInfoAgreed()).isTrue();
    }

    @Test
    @DisplayName("다시 철회해도 아무것도 바뀌지 않는다(멱등) — 철회 시각 · 파기 요청 수가 그대로다")
    void secondWithdrawalChangesNothing() {
        givenHealthConsent();
        processor.withdraw(MEMBER_ID, ConsentType.SENSITIVE_HEALTH_INFO);
        LocalDateTime first = new JdbcTemplate(dataSource).queryForObject("select withdrawn_at from member_consent where id = 1", LocalDateTime.class);

        assertThat(processor.withdraw(MEMBER_ID, ConsentType.SENSITIVE_HEALTH_INFO)).isFalse();

        assertThat(new JdbcTemplate(dataSource).queryForObject("select withdrawn_at from member_consent where id = 1", LocalDateTime.class)).isEqualTo(first);
        assertThat(reportPurgeRequestRepository.findAll()).extracting(ReportPurgeRequestEntity::getMemberId).containsExactly(MEMBER_ID);
    }

    private void givenHealthConsent() {
        memberConsentRepositoryAdapter.saveAll(List.of(MemberConsent.builder().id(1L).memberId(MEMBER_ID).type(ConsentType.SENSITIVE_HEALTH_INFO)
            .documentVersion("health-v1").agreedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).minusDays(1)).build()));
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
