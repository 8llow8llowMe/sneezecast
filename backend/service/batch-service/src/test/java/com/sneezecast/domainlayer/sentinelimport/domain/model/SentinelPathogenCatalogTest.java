package com.sneezecast.domainlayer.sentinelimport.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 기대 목록은 2026-10-02 실호출의 {@code captionList} 다 (data-api-analysis §3-3). 응답에 병원체 코드가 없어 열 순서로 코드를 붙이므로,
 * 이 목록이 코드 대응의 보증이다.
 */
class SentinelPathogenCatalogTest {

    @Test
    @DisplayName("급성호흡기는 12열이고, 코드는 계 → ND0708 · ND0709 · ND0701 ~ ND0707 · ND0001 · ND0022 순서다")
    void ariHasTwelveColumns() {
        assertThat(SentinelPathogenCatalog.columns(SentinelProgram.ARI)).extracting(SentinelPathogenCatalog.Column::diseaseKey)
            .containsExactly("TOTAL", "ND0708", "ND0709", "ND0701", "ND0702", "ND0703", "ND0704", "ND0705", "ND0706", "ND0707", "ND0001", "ND0022");
        assertThat(SentinelPathogenCatalog.captions(SentinelProgram.ARI))
            .hasSize(12)
            .startsWith("계 계")
            // 원천 분류의 오타를 그대로 둔다 — 고쳐 적으면 대조가 어긋난다.
            .contains("인를루엔자 인플루엔자 바이러스")
            .endsWith("코로나19 코로나19 바이러스");
    }

    @Test
    @DisplayName("장관감염증은 21열이고, 코드는 계 → ND0601 부터 ND0620 까지 하나씩 늘어난다")
    void entericHasTwentyOneColumns() {
        assertThat(SentinelPathogenCatalog.columns(SentinelProgram.ENTERIC)).hasSize(21)
            .extracting(SentinelPathogenCatalog.Column::diseaseKey)
            .startsWith("TOTAL", "ND0601")
            .endsWith("ND0620");
        for (int index = 1; index <= 20; index++) {
            assertThat(SentinelPathogenCatalog.columns(SentinelProgram.ENTERIC).get(index).diseaseKey()).isEqualTo("ND06%02d".formatted(index));
        }
        assertThat(SentinelPathogenCatalog.captions(SentinelProgram.ENTERIC))
            .contains("세균 장독소성대장균(ETEC)", "바이러스 그룹 A형 로타바이러스", "원충 원포자충");
    }

    @Test
    @DisplayName("코드 · 열 제목은 프로그램 안에서 겹치지 않는다 — 겹치면 다른 병원체 값이 한 행으로 합쳐진다")
    void columnsAreUniqueWithinAProgram() {
        for (SentinelProgram program : new SentinelProgram[] {SentinelProgram.ARI, SentinelProgram.ENTERIC}) {
            assertThat(SentinelPathogenCatalog.columns(program)).extracting(SentinelPathogenCatalog.Column::diseaseKey).doesNotHaveDuplicates();
            assertThat(SentinelPathogenCatalog.captions(program)).doesNotHaveDuplicates();
        }
    }

    @Test
    @DisplayName("열 제목은 '분류 이름' 이다 — 원천 captionList 와 같은 문자열")
    void captionJoinsGroupAndName() {
        SentinelPathogenCatalog.Column column = SentinelPathogenCatalog.columns(SentinelProgram.ARI).get(1);

        assertThat(column.diseaseGroup()).isEqualTo("세균");
        assertThat(column.diseaseName()).isEqualTo("마이코플라즈마균");
        assertThat(column.caption()).isEqualTo("세균 마이코플라즈마균");
    }

    @Test
    @DisplayName("인플루엔자 열은 주차라 병원체 목록이 없다")
    void influenzaHasNoPathogenColumns() {
        assertThatThrownBy(() -> SentinelPathogenCatalog.columns(SentinelProgram.INFLUENZA_ILI)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("인플루엔자 연령대 라벨 7개를 원천 행 순서로 잇는다 — 전체 합계 행은 없다")
    void mapsInfluenzaAgeLabels() {
        assertThat(SentinelPathogenCatalog.ageLabels()).containsExactly("0세", "1-6세", "7-12세", "13-18세", "19-49세", "50-64세", "65세 이상");
        assertThat(SentinelPathogenCatalog.ageGroup("0세")).isEqualTo(OfficialAgeGroup.AGE_0);
        assertThat(SentinelPathogenCatalog.ageGroup("65세 이상")).isEqualTo(OfficialAgeGroup.AGE_65_PLUS);
        assertThat(SentinelPathogenCatalog.ageGroup("전체")).isNull();
        assertThat(SentinelPathogenCatalog.ageLabels()).doesNotContain("전체");
    }

    @Test
    @DisplayName("프로그램은 원천 화면 이름 · 적재 program · request_key 이름을 잇는다")
    void programMapsSourceAndStorageNames() {
        assertThat(SentinelProgram.ARI.getIcdNm()).isEqualTo("ari");
        assertThat(SentinelProgram.ENTERIC.getIcdNm()).isEqualTo("gstrnftn");
        assertThat(SentinelProgram.INFLUENZA_ILI.getIcdNm()).isEqualTo("influ");
        assertThat(SentinelProgram.ENTERIC.getKeyName()).isEqualTo("enteric");
        assertThat(SentinelProgram.INFLUENZA_ILI.getOfficialProgram().name()).isEqualTo("INFLUENZA_ILI");
        assertThat(SentinelProgram.ARI.isPathogenWeekly()).isTrue();
        assertThat(SentinelProgram.INFLUENZA_ILI.isPathogenWeekly()).isFalse();
    }
}
