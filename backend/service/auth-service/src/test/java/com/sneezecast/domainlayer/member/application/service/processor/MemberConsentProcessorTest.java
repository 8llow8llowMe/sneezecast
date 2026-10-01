package com.sneezecast.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

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

    private final List<List<MemberConsent>> savedBatches = new ArrayList<>();
    private MemberConsentProcessor processor;

    @BeforeEach
    void setUp() {
        MemberConsentRepositoryPort port = consents -> savedBatches.add(List.copyOf(consents));
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
}
