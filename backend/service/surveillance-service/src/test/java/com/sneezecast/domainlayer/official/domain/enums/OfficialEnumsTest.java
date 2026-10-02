package com.sneezecast.domainlayer.official.domain.enums;

import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.common.dto.metadata.CodeNameDescribable;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 저장 값(enum 이름)은 batch-service 와의 DB 계약이다 (entity-design §7). batch 가 JDBC 로 이 문자열을 그대로 쓰므로 이름 · 순서를
 * 바꾸면 여기서 먼저 깨져야 한다.
 */
class OfficialEnumsTest {

    @Test
    @DisplayName("저장 값(DB 계약)이 entity-design §7 과 같다")
    void storedValuesMatchDesign() {
        assertThat(names(OfficialSource.values())).containsExactly("KDCA_NOTIFIABLE", "KDCA_SENTINEL");
        assertThat(names(OfficialProgram.values())).containsExactly("NOTIFIABLE", "INFLUENZA_ILI", "ARI", "ENTERIC");
        assertThat(names(OfficialMetric.values())).containsExactly("CASE_COUNT", "INCIDENCE_PER_100K", "ILI_PER_1000");
        assertThat(names(OfficialAgeGroup.values())).containsExactly(
            "ALL", "AGE_0", "AGE_1_6", "AGE_7_12", "AGE_13_18", "AGE_19_49", "AGE_50_64", "AGE_65_PLUS");
        assertThat(names(OfficialRegionLevel.values())).containsExactly("NATION", "SIDO");
        assertThat(names(OfficialPeriodType.values())).containsExactly("WEEK", "YEAR");
        assertThat(names(IngestChannel.values())).containsExactly("OPEN_API", "PORTAL_JSON");
        assertThat(names(IngestStatus.values())).containsExactly("IMPORTED", "FAILED");
    }

    @Test
    @DisplayName("모든 값에 화면 이름과 설명이 있다")
    void everyValueHasDisplayNameAndDescription() {
        List<CodeNameDescribable> all = Stream.of(
                OfficialSource.values(), OfficialProgram.values(), OfficialMetric.values(), OfficialAgeGroup.values(),
                OfficialRegionLevel.values(), OfficialPeriodType.values(), IngestChannel.values(), IngestStatus.values())
            .flatMap(Arrays::stream)
            .map(CodeNameDescribable.class::cast)
            .toList();

        assertThat(all).hasSize(2 + 4 + 3 + 8 + 2 + 2 + 2 + 2);
        all.forEach(value -> {
            assertThat(value.getDisplayName()).isNotBlank();
            assertThat(value.getDescription()).isNotBlank();
        });
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
