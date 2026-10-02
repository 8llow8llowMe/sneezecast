package com.sneezecast.domainlayer.official.domain.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 저장 값 {@code name()} 이 DB 계약이다. surveillance-service 의 같은 이름 enum(entity-design §7)과 목록 · 순서가 같아야 한다 — 여기를 바꾸면
 * surveillance 와 entity-design §7 을 함께 바꾼다.
 */
class OfficialEnumsTest {

    @Test
    @DisplayName("공식 감시 자료 enum 값은 entity-design §7 · surveillance 와 같다")
    void valuesMatchSurveillanceContract() {
        assertThat(names(OfficialSource.values())).containsExactly("KDCA_NOTIFIABLE", "KDCA_SENTINEL");
        assertThat(names(OfficialProgram.values())).containsExactly("NOTIFIABLE", "INFLUENZA_ILI", "ARI", "ENTERIC");
        assertThat(names(OfficialMetric.values())).containsExactly("CASE_COUNT", "INCIDENCE_PER_100K", "ILI_PER_1000");
        assertThat(names(OfficialAgeGroup.values()))
            .containsExactly("ALL", "AGE_0", "AGE_1_6", "AGE_7_12", "AGE_13_18", "AGE_19_49", "AGE_50_64", "AGE_65_PLUS");
        assertThat(names(OfficialRegionLevel.values())).containsExactly("NATION", "SIDO");
        assertThat(names(OfficialPeriodType.values())).containsExactly("WEEK", "YEAR");
        assertThat(names(IngestChannel.values())).containsExactly("OPEN_API", "PORTAL_JSON");
        assertThat(names(IngestStatus.values())).containsExactly("IMPORTED", "FAILED");
    }

    @Test
    @DisplayName("값 이름은 컬럼 길이 안에 든다 — source VARCHAR(30), program · age_group · status · channel VARCHAR(20), metric VARCHAR(30), region_level · period_type VARCHAR(10)")
    void namesFitColumns() {
        assertThat(maxLength(OfficialSource.values())).isLessThanOrEqualTo(30);
        assertThat(maxLength(OfficialProgram.values())).isLessThanOrEqualTo(20);
        assertThat(maxLength(OfficialMetric.values())).isLessThanOrEqualTo(30);
        assertThat(maxLength(OfficialAgeGroup.values())).isLessThanOrEqualTo(20);
        assertThat(maxLength(OfficialRegionLevel.values())).isLessThanOrEqualTo(10);
        assertThat(maxLength(OfficialPeriodType.values())).isLessThanOrEqualTo(10);
        assertThat(maxLength(IngestChannel.values())).isLessThanOrEqualTo(20);
        assertThat(maxLength(IngestStatus.values())).isLessThanOrEqualTo(20);
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    private static int maxLength(Enum<?>[] values) {
        return Arrays.stream(values).mapToInt(value -> value.name().length()).max().orElse(0);
    }
}
