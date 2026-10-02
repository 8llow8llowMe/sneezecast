package com.sneezecast.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.sneezecast.domainlayer.member.application.info.MemberConsentStatusInfo;
import com.sneezecast.domainlayer.member.application.port.out.MemberConsentRepositoryPort;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.domainlayer.member.domain.model.MemberConsent;
import com.sneezecast.global.properties.LegalDocumentProperties;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemberConsentProcessorTest {

    private static final long MEMBER_ID = 42L;

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 9, 0);

    private final List<List<MemberConsent>> savedBatches = new ArrayList<>();
    private final List<MemberConsent> storedRows = new ArrayList<>();
    private MemberConsentProcessor processor;

    @BeforeEach
    void setUp() {
        MemberConsentRepositoryPort port = new MemberConsentRepositoryPort() {
            @Override
            public void saveAll(List<MemberConsent> consents) {
                savedBatches.add(List.copyOf(consents));
            }

            @Override
            public List<MemberConsent> findAllByMemberId(long memberId) {
                return storedRows.stream().filter(row -> row.memberId() == memberId).toList();
            }
        };
        processor = new MemberConsentProcessor(port, new SnowflakeIdGenerator(0, 0),
            new LegalDocumentProperties("terms-2026-10", "privacy-2026-10", "health-2026-10"));
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
    @DisplayName("필수 셋과 건강정보가 모두 현재 버전이면 대기 항목이 없고 건강정보 동의가 유효하다")
    void allConsentsValid() {
        storedRows.addAll(List.of(
            row(1, ConsentType.TERMS_OF_SERVICE, "terms-2026-10", T0, null),
            row(2, ConsentType.PRIVACY_POLICY, "privacy-2026-10", T0, null),
            row(3, ConsentType.AGE_OVER_19, "terms-2026-10", T0, null),
            row(4, ConsentType.SENSITIVE_HEALTH_INFO, "health-2026-10", T0, null)));

        MemberConsentStatusInfo status = processor.currentStatus(MEMBER_ID);

        assertThat(status.pendingRequiredConsents()).isEmpty();
        assertThat(status.healthInfoAgreed()).isTrue();
    }

    @Test
    @DisplayName("문서가 개정돼 버전이 다르면 그 항목이 대기 항목이 된다 — AGE_OVER_19 는 이용약관 버전을 따른다. 행이 없는 항목도 대기다")
    void outdatedOrMissingConsentIsPending() {
        storedRows.addAll(List.of(
            row(1, ConsentType.TERMS_OF_SERVICE, "terms-2026-01", T0, null),
            row(3, ConsentType.AGE_OVER_19, "terms-2026-01", T0, null),
            row(4, ConsentType.SENSITIVE_HEALTH_INFO, "health-2026-01", T0, null)));

        MemberConsentStatusInfo status = processor.currentStatus(MEMBER_ID);

        assertThat(status.pendingRequiredConsents())
            .containsExactly(ConsentType.TERMS_OF_SERVICE, ConsentType.PRIVACY_POLICY, ConsentType.AGE_OVER_19);
        assertThat(status.healthInfoAgreed()).as("옛 버전 건강정보 동의는 무효").isFalse();
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
    @DisplayName("동의 행이 하나도 없으면 필수 셋이 모두 대기이고 건강정보 동의는 없다")
    void noRows() {
        MemberConsentStatusInfo status = processor.currentStatus(MEMBER_ID);

        assertThat(status.pendingRequiredConsents()).hasSize(3);
        assertThat(status.healthInfoAgreed()).isFalse();
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
}
