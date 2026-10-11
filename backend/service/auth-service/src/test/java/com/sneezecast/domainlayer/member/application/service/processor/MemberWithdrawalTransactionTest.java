package com.sneezecast.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.sneezecast.domainlayer.member.adapter.out.persistence.MemberConsentRepositoryAdapter;
import com.sneezecast.domainlayer.member.adapter.out.persistence.MemberNotificationSettingRepositoryAdapter;
import com.sneezecast.domainlayer.member.adapter.out.persistence.MemberRepositoryAdapter;
import com.sneezecast.domainlayer.member.adapter.out.persistence.ReportPurgeRequestRepositoryAdapter;
import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.MemberEntity;
import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.ReportPurgeRequestEntity;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberConsentRepository;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberNotificationSettingRepository;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.MemberRepository;
import com.sneezecast.domainlayer.member.adapter.out.persistence.repository.ReportPurgeRequestRepository;
import com.sneezecast.domainlayer.member.adapter.out.region.MemberRegionCleanupAdapter;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.mapper.MemberConsentMapperImpl;
import com.sneezecast.domainlayer.member.application.mapper.MemberMapperImpl;
import com.sneezecast.domainlayer.member.application.mapper.MemberNotificationSettingMapperImpl;
import com.sneezecast.domainlayer.member.application.mapper.ReportPurgeRequestMapperImpl;
import com.sneezecast.domainlayer.member.application.port.out.MemberReportScopePort;
import com.sneezecast.domainlayer.member.application.service.support.WithdrawnEmailHasher;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.domainlayer.member.domain.enums.PurgeReason;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.domainlayer.member.domain.model.MemberConsent;
import com.sneezecast.domainlayer.member.domain.model.MemberNotificationSetting;
import com.sneezecast.domainlayer.member.domain.model.ReportPurgeRequest;
import com.sneezecast.domainlayer.region.adapter.out.persistence.MemberInterestRegionRepositoryAdapter;
import com.sneezecast.domainlayer.region.adapter.out.persistence.entity.MemberInterestRegionEntity;
import com.sneezecast.domainlayer.region.adapter.out.persistence.repository.MemberInterestRegionRepository;
import com.sneezecast.domainlayer.region.application.mapper.MemberInterestRegionMapperImpl;
import com.sneezecast.domainlayer.region.domain.model.MemberInterestRegion;
import com.sneezecast.global.properties.LegalDocumentProperties;
import com.sneezecast.global.properties.WithdrawnEmailProperties;
import com.sneezecast.persistence.config.JpaAuditConfig;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import com.sneezecast.security.common.enums.SecurityRole;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 탈퇴가 <b>실제 {@code @Transactional} 프록시</b>와 H2 에서 회원 행 갱신 · 파기 요청 · 관심 동네 · 알림 설정 삭제를 함께 커밋 · 롤백하는지, 같은 회원의
 * 탈퇴 두 건이 겹치면 하나만 탈퇴시키는지 본다 (entity-design §1-1 · §1-5).
 *
 * <p>테스트 트랜잭션은 끈다({@code NOT_SUPPORTED}) — 켜 두면 처리기가 바깥 트랜잭션에 합류해 롤백 여부를 볼 수 없다. 대신 테스트마다 테이블을 비운다.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import({
    JpaAuditConfig.class, MemberWithdrawalProcessor.class, MemberCommandProcessor.class, MemberQueryProcessor.class, MemberConsentProcessor.class,
    ReportPurgeRequestProcessor.class,
    WithdrawnEmailHasher.class, MemberRepositoryAdapter.class, MemberConsentRepositoryAdapter.class, ReportPurgeRequestRepositoryAdapter.class,
    MemberNotificationSettingRepositoryAdapter.class, MemberRegionCleanupAdapter.class, MemberInterestRegionRepositoryAdapter.class, MemberMapperImpl.class,
    MemberConsentMapperImpl.class, ReportPurgeRequestMapperImpl.class, MemberNotificationSettingMapperImpl.class, MemberInterestRegionMapperImpl.class,
    MemberWithdrawalTransactionTest.Beans.class
})
class MemberWithdrawalTransactionTest {

    private static final long MEMBER_ID = 42L;
    private static final long OTHER_MEMBER_ID = 43L;
    private static final String EMAIL = "user@example.com";
    private static final String PEPPER = "withdrawn-email-pepper-0123456789abcdef";
    private static final ExecutorService OTHER_REQUEST = Executors.newSingleThreadExecutor();

    @Autowired
    private MemberWithdrawalProcessor processor;

    @Autowired
    private MemberCommandProcessor memberCommandProcessor;

    @Autowired
    private MemberQueryProcessor memberQueryProcessor;

    @Autowired
    private WithdrawnEmailHasher hasher;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberConsentRepository memberConsentRepository;

    @Autowired
    private ReportPurgeRequestRepository reportPurgeRequestRepository;

    @Autowired
    private MemberInterestRegionRepository memberInterestRegionRepository;

    @Autowired
    private MemberNotificationSettingRepository memberNotificationSettingRepository;

    @Autowired
    private MemberConsentRepositoryAdapter memberConsentRepositoryAdapter;

    @Autowired
    private ReportPurgeRequestRepositoryAdapter reportPurgeRequestRepositoryAdapter;

    @Autowired
    private MemberInterestRegionRepositoryAdapter memberInterestRegionRepositoryAdapter;

    @MockitoSpyBean
    private MemberRepositoryAdapter memberRepositoryAdapter;

    @MockitoSpyBean
    private MemberNotificationSettingRepositoryAdapter memberNotificationSettingRepositoryAdapter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        memberNotificationSettingRepository.deleteAll();
        memberInterestRegionRepository.deleteAll();
        reportPurgeRequestRepository.deleteAll();
        memberConsentRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @AfterAll
    static void shutdown() {
        OTHER_REQUEST.shutdownNow();
    }

    @Test
    @DisplayName("탈퇴하면 회원 행(다이제스트 · 비운 컬럼 · WITHDRAWN) · WITHDRAWAL 파기 요청 · 관심 동네 삭제 · 알림 설정 삭제가 함께 커밋되고 동의 이력은 남는다")
    void withdrawCommitsEverything() {
        givenMember(MEMBER_ID, EMAIL);
        givenMember(OTHER_MEMBER_ID, "other@example.com");
        givenMemberData(MEMBER_ID);
        givenInterestRegion(100L, OTHER_MEMBER_ID, "11110515", 1);
        givenNotificationSetting(201L, OTHER_MEMBER_ID);

        processor.withdraw(MEMBER_ID);

        MemberEntity withdrawn = memberRepository.findById(MEMBER_ID).orElseThrow();
        assertThat(withdrawn.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(withdrawn.getEmail()).isEqualTo(hasher.hash(MEMBER_ID, EMAIL)).hasSize(64);
        assertThat(withdrawn.getPassword()).isNull();
        assertThat(withdrawn.getNickname()).isEqualTo(MemberWithdrawalProcessor.WITHDRAWN_NICKNAME);
        assertThat(withdrawn.getProvider()).isNull();
        assertThat(withdrawn.getProfileImageUrl()).isNull();
        assertThat(withdrawn.getProfileImageKey()).isNull();
        assertThat(withdrawn.getWithdrawnAt()).isNotNull();
        assertThat(withdrawn.getRole()).isEqualTo(SecurityRole.USER);
        assertThat(memberRepository.existsByEmail(EMAIL)).as("원문 이메일로는 찾을 수 없다 — 재가입 가능").isFalse();

        assertThat(reportPurgeRequestRepository.findAll()).filteredOn(request -> request.getReason() == PurgeReason.WITHDRAWAL).singleElement()
            .satisfies(request -> {
                assertThat(request.getMemberId()).isEqualTo(MEMBER_ID);
                assertThat(request.getRequestedAt()).isEqualTo(withdrawn.getWithdrawnAt());
                assertThat(request.getAttemptCount()).isZero();
                assertThat(request.getCompletedAt()).isNull();
            });
        assertThat(memberInterestRegionRepository.findAll()).extracting(MemberInterestRegionEntity::getMemberId).containsExactly(OTHER_MEMBER_ID);
        assertThat(memberNotificationSettingRepository.findAll()).singleElement().satisfies(setting -> assertThat(setting.getMemberId()).isEqualTo(OTHER_MEMBER_ID));
        assertThat(memberConsentRepository.findAllByMemberId(MEMBER_ID)).as("동의 이력은 회원 파기(#244) 때 지운다").hasSize(1);
        assertThat(memberRepository.findById(OTHER_MEMBER_ID).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("건강정보 동의 철회 파기가 미완료로 남아 있어도 WITHDRAWAL 파기 요청이 새로 생긴다 — 두 요청이 함께 미완료로 남는다")
    void withdrawalRequestIsCreatedDespiteIncompleteConsentPurge() {
        givenMember(MEMBER_ID, EMAIL);
        reportPurgeRequestRepositoryAdapter.save(ReportPurgeRequest.builder().id(300L).memberId(MEMBER_ID).reason(PurgeReason.HEALTH_CONSENT_WITHDRAWN)
            .requestedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).minusMinutes(3)).attemptCount(0).build());

        processor.withdraw(MEMBER_ID);

        assertThat(reportPurgeRequestRepository.findAll()).extracting(ReportPurgeRequestEntity::getReason, ReportPurgeRequestEntity::getCompletedAt)
            .containsExactlyInAnyOrder(tuple(PurgeReason.HEALTH_CONSENT_WITHDRAWN, null),
                tuple(PurgeReason.WITHDRAWAL, null));
    }

    @Test
    @DisplayName("마지막 단계(알림 설정 삭제)가 실패하면 전부 롤백된다 — 회원은 ACTIVE · 원문 이메일 그대로, 파기 요청 · 관심 동네 · 설정도 그대로")
    void failureInLastStepRollsBackEverything() {
        givenMember(MEMBER_ID, EMAIL);
        givenMemberData(MEMBER_ID);
        // 저장소의 회원 단위 삭제는 MANDATORY 라 스텁을 거는 호출도 트랜잭션 안에서 한다(프록시의 트랜잭션 검사가 Mockito 보다 먼저 돈다).
        inTransaction(() -> doThrow(new IllegalStateException("settings store down")).when(memberNotificationSettingRepositoryAdapter).deleteByMemberId(anyLong()));

        assertThatThrownBy(() -> processor.withdraw(MEMBER_ID)).isInstanceOf(IllegalStateException.class);

        MemberEntity member = memberRepository.findById(MEMBER_ID).orElseThrow();
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(member.getEmail()).isEqualTo(EMAIL);
        assertThat(member.getPassword()).isNotNull();
        assertThat(member.getWithdrawnAt()).isNull();
        assertThat(reportPurgeRequestRepository.count()).isZero();
        assertThat(memberInterestRegionRepository.count()).isEqualTo(2);
        assertThat(memberNotificationSettingRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 회원의 탈퇴 두 건이 겹치면 뒤 요청은 회원 행 잠금을 기다렸다가 MEMBER_002 로 끝난다 — 파기 요청은 하나뿐이다")
    void concurrentWithdrawalsWithdrawOnce() throws Exception {
        givenMember(MEMBER_ID, EMAIL);
        AtomicBoolean armed = new AtomicBoolean(true);
        CountDownLatch otherEntered = new CountDownLatch(1);
        AtomicReference<Future<?>> other = new AtomicReference<>();
        inTransaction(() -> doAnswer(invocation -> {
            if (armed.compareAndSet(true, false)) {
                // 첫 요청(이 스레드)이 잠금을 잡은 뒤, 둘째 요청(다른 스레드 · 다른 트랜잭션)을 띄워 같은 행의 잠금을 기다리게 한다.
                Object locked = invocation.callRealMethod();
                other.set(OTHER_REQUEST.submit(() -> processor.withdraw(MEMBER_ID)));
                assertThat(otherEntered.await(5, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(200);
                return locked;
            }
            otherEntered.countDown();
            return invocation.callRealMethod();
        }).when(memberRepositoryAdapter).findByIdForUpdate(MEMBER_ID));

        processor.withdraw(MEMBER_ID);

        assertThatThrownBy(() -> other.get().get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(MemberException.class)
            .cause().satisfies(cause -> assertThat(((MemberException) cause).getErrorCode()).isEqualTo(MemberErrorCode.WITHDRAWN_MEMBER));
        assertThat(reportPurgeRequestRepository.findAll()).extracting(ReportPurgeRequestEntity::getReason).containsExactly(PurgeReason.WITHDRAWAL);
        assertThat(memberRepository.findById(MEMBER_ID).orElseThrow().getEmail()).isEqualTo(hasher.hash(MEMBER_ID, EMAIL));
    }

    @Test
    @DisplayName("닉네임 변경이 상태를 읽은 뒤 탈퇴가 커밋되면, 변경은 잠금 읽기에서 MEMBER_002 로 끝나고 탈퇴를 되살리지 않는다 (내 정보 수정 Facade 와 같은 순서)")
    void nicknameChangeAfterWithdrawalCommitDoesNotResurrect() {
        givenMember(MEMBER_ID, EMAIL);

        // 내 정보 수정은 한 트랜잭션에서 상태 확인(잠금 없는 읽기 — 엔티티가 ACTIVE 로 영속성 컨텍스트에 올라간다) → 닉네임 변경이다. 그 사이에 탈퇴가 커밋된다.
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            memberQueryProcessor.getActiveMember(MEMBER_ID);
            withdrawInOtherTransaction();
            memberCommandProcessor.changeNickname(MEMBER_ID, "새닉네임");
        })).isInstanceOfSatisfying(MemberException.class, e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.WITHDRAWN_MEMBER));

        assertWithdrawnRowIntact();
    }

    @Test
    @DisplayName("비밀번호 변경 · 재설정이 상태를 확인한 뒤 탈퇴가 커밋되면, 저장은 잠금 읽기에서 MEMBER_002 로 끝나고 탈퇴한 행에 해시를 다시 쓰지 않는다")
    void passwordChangeAfterWithdrawalCommitDoesNotWriteHash() {
        givenMember(MEMBER_ID, EMAIL);

        // 비밀번호 변경 · 재설정은 트랜잭션 밖에서 회원을 확인하고(현재 비밀번호 · 재설정 토큰) BCrypt 뒤에 저장 트랜잭션을 연다.
        memberQueryProcessor.getActiveMember(MEMBER_ID);
        withdrawInOtherTransaction();

        assertThatThrownBy(() -> memberCommandProcessor.changePassword(MEMBER_ID, "$2a$10$newHash"))
            .isInstanceOfSatisfying(MemberException.class, e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.WITHDRAWN_MEMBER));

        assertWithdrawnRowIntact();
    }

    /** 다른 스레드(다른 트랜잭션)에서 탈퇴를 끝까지 커밋시키고 기다린다. */
    private void withdrawInOtherTransaction() {
        try {
            OTHER_REQUEST.submit(() -> processor.withdraw(MEMBER_ID)).get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertWithdrawnRowIntact() {
        MemberEntity member = memberRepository.findById(MEMBER_ID).orElseThrow();
        assertThat(member.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(member.getEmail()).isEqualTo(hasher.hash(MEMBER_ID, EMAIL));
        assertThat(member.getPassword()).isNull();
        assertThat(member.getNickname()).isEqualTo(MemberWithdrawalProcessor.WITHDRAWN_NICKNAME);
        assertThat(member.getProvider()).isNull();
    }

    @Test
    @DisplayName("탈퇴 → 같은 이메일로 재가입 → 다시 탈퇴해도 uk_member_email 에 막히지 않는다 — 다이제스트가 회원마다 다르다")
    void rejoinedMemberCanWithdrawAgain() {
        givenMember(MEMBER_ID, EMAIL);
        processor.withdraw(MEMBER_ID);
        givenMember(OTHER_MEMBER_ID, EMAIL);

        processor.withdraw(OTHER_MEMBER_ID);

        assertThat(memberRepository.findAll()).extracting(MemberEntity::getStatus).containsOnly(MemberStatus.WITHDRAWN);
        assertThat(memberRepository.findAll()).extracting(MemberEntity::getEmail)
            .containsExactlyInAnyOrder(hasher.hash(MEMBER_ID, EMAIL), hasher.hash(OTHER_MEMBER_ID, EMAIL));
        assertThat(reportPurgeRequestRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("잠금 읽기 · 탈퇴 갱신 · 관심 동네 회원 단위 삭제를 트랜잭션 없이 부르면 바로 실패한다(MANDATORY)")
    void writesRequireTransaction() {
        givenMember(MEMBER_ID, EMAIL);
        givenInterestRegion(100L, MEMBER_ID, "11110515", 1);

        assertThatThrownBy(() -> memberRepositoryAdapter.findByIdForUpdate(MEMBER_ID)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> memberRepositoryAdapter.withdraw(MEMBER_ID, "digest", "탈퇴회원", LocalDateTime.now()))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> memberInterestRegionRepositoryAdapter.deleteAllByMemberId(MEMBER_ID)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(memberInterestRegionRepository.count()).isEqualTo(1);
    }

    private void inTransaction(Runnable stubbing) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> stubbing.run());
    }

    private void givenMember(long memberId, String email) {
        memberRepositoryAdapter.save(Member.builder().id(memberId).email(email).password("$2a$10$hash").nickname("재채기탐정")
            .profileImageUrl("https://k.kakaocdn.net/profile.jpg").profileImageKey("profile/" + memberId + ".png").role(SecurityRole.USER)
            .provider(OAuthProvider.KAKAO).status(MemberStatus.ACTIVE).build());
    }

    /** 동의 1행 · 관심 동네 2곳 · 알림 설정 1행. */
    private void givenMemberData(long memberId) {
        memberConsentRepositoryAdapter.saveAll(List.of(MemberConsent.builder().id(memberId * 10).memberId(memberId).type(ConsentType.TERMS_OF_SERVICE)
            .documentVersion("terms-v1").agreedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).minusDays(1)).build()));
        givenInterestRegion(memberId * 10 + 1, memberId, "11110530", 1);
        givenInterestRegion(memberId * 10 + 2, memberId, "11110540", 2);
        givenNotificationSetting(memberId * 10 + 3, memberId);
    }

    private void givenInterestRegion(long id, long memberId, String districtCode, int slot) {
        memberInterestRegionRepositoryAdapter.insert(MemberInterestRegion.builder().id(id).memberId(memberId).districtCode(districtCode).slot(slot).build());
    }

    private void givenNotificationSetting(long id, long memberId) {
        memberNotificationSettingRepositoryAdapter.insert(MemberNotificationSetting.builder().id(id).memberId(memberId).weeklyReport(true).regionNotice(true)
            .build());
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

        @Bean
        WithdrawnEmailProperties withdrawnEmailProperties() {
            return new WithdrawnEmailProperties(PEPPER);
        }

        @Bean
        MemberReportScopePort memberReportScopePort() {
            return status -> false;
        }
    }
}
