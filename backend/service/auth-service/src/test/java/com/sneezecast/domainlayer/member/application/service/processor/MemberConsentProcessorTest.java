package com.sneezecast.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.groups.Tuple.tuple;

import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.info.MemberConsentStatusInfo;
import com.sneezecast.domainlayer.member.application.port.out.MemberConsentRepositoryPort;
import com.sneezecast.domainlayer.member.application.port.out.ReportPurgeRequestRepositoryPort;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.domainlayer.member.domain.enums.PurgeReason;
import com.sneezecast.domainlayer.member.domain.model.MemberConsent;
import com.sneezecast.domainlayer.member.domain.model.ReportPurgeRequest;
import com.sneezecast.global.properties.LegalDocumentProperties;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 동의 기록 · 상태 판정 · 동의 · 철회 규칙. 저장소는 메모리 가짜다 — 철회의 트랜잭션 원자성은 {@code MemberConsentWithdrawTransactionTest} 가 H2 로 본다.
 */
class MemberConsentProcessorTest {

    private static final long MEMBER_ID = 42L;

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 9, 0);

    private final List<List<MemberConsent>> savedBatches = new ArrayList<>();
    private final List<MemberConsent> storedRows = new ArrayList<>();
    private final List<ReportPurgeRequest> purgeRequests = new ArrayList<>();
    private MemberConsentProcessor processor;

    @BeforeEach
    void setUp() {
        MemberConsentRepositoryPort port = new MemberConsentRepositoryPort() {
            @Override
            public void saveAll(List<MemberConsent> consents) {
                savedBatches.add(List.copyOf(consents));
                storedRows.addAll(consents);
            }

            @Override
            public List<MemberConsent> findAllByMemberId(long memberId) {
                return storedRows.stream().filter(row -> row.memberId() == memberId).toList();
            }

            @Override
            public List<MemberConsent> findAllByMemberIdAndTypeForUpdate(long memberId, ConsentType type) {
                return findAllByMemberId(memberId).stream().filter(row -> row.type() == type).toList();
            }

            @Override
            public void withdraw(long consentId, LocalDateTime withdrawnAt) {
                storedRows.replaceAll(row -> row.id() == consentId
                    ? MemberConsent.builder().id(row.id()).memberId(row.memberId()).type(row.type()).documentVersion(row.documentVersion())
                    .agreedAt(row.agreedAt()).withdrawnAt(withdrawnAt).build()
                    : row);
            }
        };
        ReportPurgeRequestRepositoryPort purgePort = new ReportPurgeRequestRepositoryPort() {
            @Override
            public void save(ReportPurgeRequest request) {
                purgeRequests.add(request);
            }

            @Override
            public boolean existsIncompleteByMemberId(long memberId) {
                return purgeRequests.stream().anyMatch(request -> request.memberId() == memberId && request.completedAt() == null);
            }
        };
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(0, 0);
        processor = new MemberConsentProcessor(port, ids, new LegalDocumentProperties("terms-2026-10", "privacy-2026-10", "health-2026-10"),
            new ReportPurgeRequestProcessor(purgePort, ids));
    }

    @Test
    @DisplayName("건강정보에 동의하지 않으면 필수 셋만 남고, 항목별 설정 버전이 박힌다 — AGE_OVER_19 는 이용약관 버전이다")
    void recordsRequiredConsentsWithConfiguredVersions() {
        LocalDateTime before = LocalDateTime.now();

        processor.recordSignupConsents(MEMBER_ID, false);

        assertThat(savedBatches).singleElement().satisfies(batch -> {
            assertThat(batch).extracting(MemberConsent::memberId, MemberConsent::type, MemberConsent::documentVersion)
                .containsExactly(
                    tuple(MEMBER_ID, ConsentType.TERMS_OF_SERVICE, "terms-2026-10"),
                    tuple(MEMBER_ID, ConsentType.PRIVACY_POLICY, "privacy-2026-10"),
                    tuple(MEMBER_ID, ConsentType.AGE_OVER_19, "terms-2026-10"));
            assertThat(batch).allSatisfy(consent -> assertThat(consent.withdrawnAt()).isNull());
            assertThat(batch).extracting(MemberConsent::agreedAt).containsOnly(batch.getFirst().agreedAt());
            assertThat(batch.getFirst().agreedAt()).isBetween(before, LocalDateTime.now());
        });
    }

    @Test
    @DisplayName("건강정보에 동의하면 민감정보 동의서 버전으로 한 행이 더 남고, 같은 동의 시각을 쓴다")
    void recordsHealthConsentWhenAgreed() {
        processor.recordSignupConsents(MEMBER_ID, true);

        List<MemberConsent> batch = savedBatches.getFirst();
        assertThat(batch).hasSize(4);
        assertThat(batch).filteredOn(consent -> consent.type() == ConsentType.SENSITIVE_HEALTH_INFO)
            .singleElement()
            .satisfies(consent -> {
                assertThat(consent.documentVersion()).isEqualTo("health-2026-10");
                assertThat(consent.agreedAt()).isEqualTo(batch.getFirst().agreedAt());
            });
        assertThat(batch).extracting(MemberConsent::id).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("필수 셋과 건강정보가 모두 현재 버전이면 대기 항목이 없고 건강정보 동의가 유효하며, 파기 요청이 없으면 purgePending false 다")
    void allConsentsValid() {
        storedRows.addAll(List.of(
            row(1, ConsentType.TERMS_OF_SERVICE, "terms-2026-10", T0, null),
            row(2, ConsentType.PRIVACY_POLICY, "privacy-2026-10", T0, null),
            row(3, ConsentType.AGE_OVER_19, "terms-2026-10", T0, null),
            row(4, ConsentType.SENSITIVE_HEALTH_INFO, "health-2026-10", T0, null)));

        MemberConsentStatusInfo status = processor.currentStatus(MEMBER_ID);

        assertThat(status.pendingRequiredConsents()).isEmpty();
        assertThat(status.healthInfoAgreed()).isTrue();
        assertThat(status.purgePending()).isFalse();
    }

    @Test
    @DisplayName("문서가 개정돼 버전이 다르면 그 항목이 대기 항목이 된다. 행이 없는 항목도 대기다 — 대기에는 이용약관 · 개인정보만 들어간다")
    void outdatedOrMissingConsentIsPending() {
        storedRows.addAll(List.of(
            row(1, ConsentType.TERMS_OF_SERVICE, "terms-2026-01", T0, null),
            row(4, ConsentType.SENSITIVE_HEALTH_INFO, "health-2026-01", T0, null)));

        MemberConsentStatusInfo status = processor.currentStatus(MEMBER_ID);

        assertThat(status.pendingRequiredConsents()).containsExactly(ConsentType.TERMS_OF_SERVICE, ConsentType.PRIVACY_POLICY);
        assertThat(status.healthInfoAgreed()).as("옛 버전 건강정보 동의는 무효").isFalse();
    }

    @Test
    @DisplayName("만 19세 확인은 버전을 보지 않는다 — 옛 이용약관 버전으로 확인했어도 약관을 개정했다고 다시 묻지 않는다")
    void ageConfirmationIgnoresVersion() {
        storedRows.addAll(List.of(
            row(1, ConsentType.TERMS_OF_SERVICE, "terms-2026-10", T0, null),
            row(2, ConsentType.PRIVACY_POLICY, "privacy-2026-10", T0, null),
            row(3, ConsentType.AGE_OVER_19, "terms-2025-01", T0, null)));

        assertThat(processor.currentStatus(MEMBER_ID).pendingRequiredConsents()).isEmpty();
    }

    @Test
    @DisplayName("동의 행이 하나도 없으면 이용약관 · 개인정보가 대기이고(만 19세는 대기에 넣지 않는다) 건강정보 동의는 없다")
    void noRows() {
        MemberConsentStatusInfo status = processor.currentStatus(MEMBER_ID);

        assertThat(status.pendingRequiredConsents()).containsExactly(ConsentType.TERMS_OF_SERVICE, ConsentType.PRIVACY_POLICY);
        assertThat(status.healthInfoAgreed()).isFalse();
    }

    @Test
    @DisplayName("항목별 최신 행(agreed_at 최대)만 본다 — 옛 행이 유효해도 최신 행이 철회됐으면 무효, 철회 뒤 재동의했으면 유효")
    void latestRowDecides() {
        storedRows.addAll(requiredRows());
        storedRows.add(row(10, ConsentType.SENSITIVE_HEALTH_INFO, "health-2026-10", T0, null));
        storedRows.add(row(11, ConsentType.SENSITIVE_HEALTH_INFO, "health-2026-10", T0.plusDays(1), T0.plusDays(2)));

        assertThat(processor.currentStatus(MEMBER_ID).healthInfoAgreed()).as("최신 행이 철회됨").isFalse();

        storedRows.add(row(12, ConsentType.SENSITIVE_HEALTH_INFO, "health-2026-10", T0.plusDays(3), null));

        assertThat(processor.currentStatus(MEMBER_ID).healthInfoAgreed()).as("철회 뒤 재동의").isTrue();
    }

    @Test
    @DisplayName("동의 시각이 같으면 나중에 만든 행(ID 가 큰 쪽)이 최신이다")
    void sameAgreedAtUsesLaterId() {
        storedRows.addAll(requiredRows());
        storedRows.add(row(21, ConsentType.SENSITIVE_HEALTH_INFO, "health-2026-10", T0, T0.plusHours(1)));
        storedRows.add(row(20, ConsentType.SENSITIVE_HEALTH_INFO, "health-2026-10", T0, null));

        assertThat(processor.currentStatus(MEMBER_ID).healthInfoAgreed()).isFalse();
    }

    @Test
    @DisplayName("미완료 파기 요청이 있으면 purgePending true 다 — 완료된 요청 · 다른 회원의 요청은 세지 않는다")
    void purgePendingReflectsIncompleteRequests() {
        purgeRequests.add(purge(MEMBER_ID, T0.plusDays(1)));
        purgeRequests.add(purge(7L, null));
        assertThat(processor.currentStatus(MEMBER_ID).purgePending()).as("완료된 요청뿐").isFalse();

        purgeRequests.add(purge(MEMBER_ID, null));
        assertThat(processor.currentStatus(MEMBER_ID).purgePending()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ConsentType.class, names = {"TERMS_OF_SERVICE", "PRIVACY_POLICY", "SENSITIVE_HEALTH_INFO"})
    @DisplayName("동의 API 로 받는 항목은 현재 버전 · 지금 시각으로 새 행을 남기고 기존 행은 고치지 않는다")
    void agreeAddsNewRow(ConsentType type) {
        storedRows.add(row(1, type, "old-version", T0, null));
        LocalDateTime before = LocalDateTime.now();

        processor.agree(MEMBER_ID, type, processor.documentVersionOf(type));

        assertThat(savedBatches).singleElement().satisfies(batch -> assertThat(batch).singleElement().satisfies(consent -> {
            assertThat(consent.type()).isEqualTo(type);
            assertThat(consent.documentVersion()).isEqualTo(processor.documentVersionOf(type));
            assertThat(consent.agreedAt()).isBetween(before, LocalDateTime.now());
            assertThat(consent.withdrawnAt()).isNull();
            assertThat(consent.id()).isNotEqualTo(1L);
        }));
        assertThat(storedRows.getFirst().documentVersion()).as("옛 행은 그대로").isEqualTo("old-version");
    }

    @Test
    @DisplayName("이미 현재 버전으로 유효하면 새 행을 남기지 않는다(멱등) — 철회된 동의에는 다시 동의하면 새 행이다")
    void agreeIsIdempotentWhenAlreadyValid() {
        storedRows.add(row(1, ConsentType.SENSITIVE_HEALTH_INFO, "health-2026-10", T0, null));
        processor.agree(MEMBER_ID, ConsentType.SENSITIVE_HEALTH_INFO, "health-2026-10");
        assertThat(savedBatches).isEmpty();

        storedRows.set(0, row(1, ConsentType.SENSITIVE_HEALTH_INFO, "health-2026-10", T0, T0.plusHours(1)));
        processor.agree(MEMBER_ID, ConsentType.SENSITIVE_HEALTH_INFO, "health-2026-10");
        assertThat(savedBatches).hasSize(1);
        assertThat(processor.currentStatus(MEMBER_ID).healthInfoAgreed()).isTrue();
    }

    @Test
    @DisplayName("만 19세 확인은 동의 API 로 받지 않는다 — MEMBER_010(400), 버전이 맞아도 행을 남기지 않는다")
    void agreeRejectsAgeConfirmation() {
        assertThat(failure(() -> processor.agree(MEMBER_ID, ConsentType.AGE_OVER_19, "terms-2026-10"))).isEqualTo(MemberErrorCode.CONSENT_NOT_AGREEABLE);
        assertThat(MemberErrorCode.CONSENT_NOT_AGREEABLE.getHttpStatus().value()).isEqualTo(400);
        assertThat(savedBatches).isEmpty();
    }

    @Test
    @DisplayName("보낸 문서 버전이 서버 현재 버전과 다르면 MEMBER_011(409) 이고 행을 남기지 않는다")
    void agreeRejectsVersionMismatch() {
        assertThat(failure(() -> processor.agree(MEMBER_ID, ConsentType.PRIVACY_POLICY, "privacy-2026-01"))).isEqualTo(MemberErrorCode.CONSENT_VERSION_MISMATCH);
        assertThat(MemberErrorCode.CONSENT_VERSION_MISMATCH.getHttpStatus().value()).isEqualTo(409);
        assertThat(savedBatches).isEmpty();
    }

    @Test
    @DisplayName("철회는 최신 건강정보 동의 행에 철회 시각을 채우고, 같은 시각으로 HEALTH_CONSENT_WITHDRAWN 파기 요청(시도 0회)을 남긴다")
    void withdrawMarksRowAndRequestsPurge() {
        storedRows.addAll(requiredRows());
        storedRows.add(row(10, ConsentType.SENSITIVE_HEALTH_INFO, "health-2026-10", T0, null));

        assertThat(processor.withdraw(MEMBER_ID, ConsentType.SENSITIVE_HEALTH_INFO)).isTrue();

        MemberConsent withdrawn = storedRows.stream().filter(row -> row.id() == 10L).findFirst().orElseThrow();
        assertThat(withdrawn.withdrawnAt()).isNotNull();
        assertThat(purgeRequests).singleElement().satisfies(request -> {
            assertThat(request.memberId()).isEqualTo(MEMBER_ID);
            assertThat(request.reason()).isEqualTo(PurgeReason.HEALTH_CONSENT_WITHDRAWN);
            assertThat(request.requestedAt()).isEqualTo(withdrawn.withdrawnAt());
            assertThat(request.attemptCount()).isZero();
            assertThat(request.completedAt()).isNull();
        });
        MemberConsentStatusInfo status = processor.currentStatus(MEMBER_ID);
        assertThat(status.healthInfoAgreed()).isFalse();
        assertThat(status.purgePending()).isTrue();
    }

    @Test
    @DisplayName("철회할 동의가 없으면(동의한 적 없음 · 이미 철회) 아무것도 남기지 않는다(멱등)")
    void withdrawIsIdempotent() {
        assertThat(processor.withdraw(MEMBER_ID, ConsentType.SENSITIVE_HEALTH_INFO)).as("동의한 적 없음").isFalse();

        storedRows.add(row(10, ConsentType.SENSITIVE_HEALTH_INFO, "health-2026-10", T0, null));
        processor.withdraw(MEMBER_ID, ConsentType.SENSITIVE_HEALTH_INFO);
        LocalDateTime firstWithdrawnAt = storedRows.getFirst().withdrawnAt();

        assertThat(processor.withdraw(MEMBER_ID, ConsentType.SENSITIVE_HEALTH_INFO)).as("이미 철회").isFalse();
        assertThat(storedRows.getFirst().withdrawnAt()).as("철회 시각을 고치지 않는다").isEqualTo(firstWithdrawnAt);
        assertThat(purgeRequests).hasSize(1);
    }

    @Test
    @DisplayName("옛 버전 건강정보 동의도 철회되지 않았으면 철회하고 파기를 요청한다 — 그 버전으로 남긴 보고가 있다")
    void withdrawOutdatedConsent() {
        storedRows.add(row(10, ConsentType.SENSITIVE_HEALTH_INFO, "health-2026-01", T0, null));

        assertThat(processor.withdraw(MEMBER_ID, ConsentType.SENSITIVE_HEALTH_INFO)).isTrue();
        assertThat(purgeRequests).hasSize(1);
    }

    @Test
    @DisplayName("미완료 파기 요청이 이미 있으면(철회 → 재동의 → 다시 철회) 철회만 하고 둘째 요청은 만들지 않는다")
    void withdrawDoesNotDuplicateIncompletePurge() {
        purgeRequests.add(purge(MEMBER_ID, null));
        storedRows.add(row(10, ConsentType.SENSITIVE_HEALTH_INFO, "health-2026-10", T0, null));

        assertThat(processor.withdraw(MEMBER_ID, ConsentType.SENSITIVE_HEALTH_INFO)).isTrue();
        assertThat(storedRows.getFirst().withdrawnAt()).isNotNull();
        assertThat(purgeRequests).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = ConsentType.class, names = {"TERMS_OF_SERVICE", "PRIVACY_POLICY", "AGE_OVER_19"})
    @DisplayName("건강정보가 아닌 항목은 철회할 수 없다 — MEMBER_012(400), 아무것도 바꾸지 않는다")
    void withdrawRejectsOtherTypes(ConsentType type) {
        storedRows.addAll(requiredRows());

        assertThat(failure(() -> processor.withdraw(MEMBER_ID, type))).isEqualTo(MemberErrorCode.CONSENT_NOT_WITHDRAWABLE);
        assertThat(MemberErrorCode.CONSENT_NOT_WITHDRAWABLE.getHttpStatus().value()).isEqualTo(400);
        assertThat(storedRows).allSatisfy(row -> assertThat(row.withdrawnAt()).isNull());
        assertThat(purgeRequests).isEmpty();
    }

    private static List<MemberConsent> requiredRows() {
        return List.of(
            row(1, ConsentType.TERMS_OF_SERVICE, "terms-2026-10", T0, null),
            row(2, ConsentType.PRIVACY_POLICY, "privacy-2026-10", T0, null),
            row(3, ConsentType.AGE_OVER_19, "terms-2026-10", T0, null));
    }

    private static MemberConsent row(long id, ConsentType type, String version, LocalDateTime agreedAt, LocalDateTime withdrawnAt) {
        return MemberConsent.builder().id(id).memberId(MEMBER_ID).type(type).documentVersion(version).agreedAt(agreedAt).withdrawnAt(withdrawnAt).build();
    }

    private static ReportPurgeRequest purge(long memberId, LocalDateTime completedAt) {
        return ReportPurgeRequest.builder().id(900L).memberId(memberId).reason(PurgeReason.HEALTH_CONSENT_WITHDRAWN).requestedAt(T0).completedAt(completedAt)
            .build();
    }

    private static MemberErrorCode failure(Runnable call) {
        return catchThrowableOfType(MemberException.class, call::run).getErrorCode();
    }
}
