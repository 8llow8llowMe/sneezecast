package com.sneezecast.domainlayer.aggregate.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.aggregate.domain.enums.InsufficientReason;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 집계 수치 · 판정 값의 불변식. 어긋난 값은 저장 전에 거부한다 — 집계 쿼리나 판정이 틀렸다는 뜻이다.
 */
class DistrictWeeklyAggregateTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 8, 12, 0);

    @ParameterizedTest(name = "참여 {0} · 증상 {1} · 호흡기 {2} · 장관 {3} · 수정 {4}")
    @CsvSource({
        "-1, 0, 0, 0, 0",
        "10, 11, 11, 0, 0",
        "10, 5, 6, 0, 0",
        "10, 5, 0, 6, 0",
        // 증상 보고는 증상군을 하나 이상 가진다 — 호흡기 + 장관 < 증상이면 틀렸다.
        "10, 5, 2, 2, 0",
        "10, 0, 0, 0, 11"
    })
    @DisplayName("수치 관계가 어긋나면 거부한다")
    void countsRejectInconsistentValues(int participant, int symptomatic, int respiratory, int enteric, int revised) {
        assertThatThrownBy(() -> new AggregateCounts(participant, symptomatic, respiratory, enteric, revised)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("둘 다 보고한 사람은 호흡기 · 장관 양쪽에 센다 — 합이 증상 보고보다 커도 된다")
    void countsAllowOverlap() {
        assertThat(new AggregateCounts(10, 5, 5, 5, 10).symptomaticCount()).isEqualTo(5);
        assertThat(new AggregateCounts(0, 0, 0, 0, 0).participantCount()).isZero();
    }

    @Test
    @DisplayName("INSUFFICIENT 는 이유가 있고 기준선이 없다. 그 밖의 단계는 이유가 없고 기준선이 있다")
    void judgementInvariants() {
        assertThat(AggregateJudgement.insufficient(InsufficientReason.NO_BASELINE).baselineParticipantCount()).isNull();
        assertThat(AggregateJudgement.judged(AggregateLevel.HIGH, 400, 40).insufficientReason()).isNull();

        assertThatThrownBy(() -> new AggregateJudgement(AggregateLevel.INSUFFICIENT, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AggregateJudgement(AggregateLevel.NORMAL, InsufficientReason.LOW_SAMPLE, 400, 40))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AggregateJudgement(AggregateLevel.NORMAL, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AggregateJudgement(AggregateLevel.NORMAL, null, 400, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AggregateJudgement(AggregateLevel.INSUFFICIENT, InsufficientReason.UNSTABLE, 400, 40))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AggregateJudgement.judged(AggregateLevel.SLIGHT, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AggregateJudgement.judged(AggregateLevel.SLIGHT, 10, 11)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("불변식을 어긴 행은 어느 행인지(id · 행정동 · 주)를 싣고 원인을 유지한 채 거부한다 — DB 에서 읽은 행을 찾아 고칠 수 있게")
    void invariantViolationNamesTheRow() {
        assertThatThrownBy(() -> DistrictWeeklyAggregate.builder().id(42L).districtCode("11240660").isoWeek(ReportWeek.parse("2026-W41"))
                .participantCount(10).symptomaticCount(11).respiratoryCount(11).level(AggregateLevel.INSUFFICIENT)
                .insufficientReason(InsufficientReason.LOW_SAMPLE).ruleVersion("2026-10-08").calculatedAt(AT).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("id=42").hasMessageContaining("district=11240660").hasMessageContaining("week=2026-W41")
            .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("새 행은 계산 결과를 그대로 펼치고, 마감 · 감사 시각은 비어 있다")
    void newAggregateSpreadsCalculation() {
        AggregateCalculation calculation = new AggregateCalculation("11240660", ReportWeek.parse("2026-W41"), new AggregateCounts(120, 20, 15, 8, 3),
            AggregateJudgement.judged(AggregateLevel.SLIGHT, 400, 40), "2026-10-08", AT);

        DistrictWeeklyAggregate aggregate = DistrictWeeklyAggregate.newAggregate(7L, calculation);

        assertThat(aggregate.id()).isEqualTo(7L);
        assertThat(aggregate.participantCount()).isEqualTo(120);
        assertThat(aggregate.symptomaticCount()).isEqualTo(20);
        assertThat(aggregate.respiratoryCount()).isEqualTo(15);
        assertThat(aggregate.entericCount()).isEqualTo(8);
        assertThat(aggregate.revisedReportCount()).isEqualTo(3);
        assertThat(aggregate.level()).isEqualTo(AggregateLevel.SLIGHT);
        assertThat(aggregate.baselineParticipantCount()).isEqualTo(400);
        assertThat(aggregate.baselineSymptomaticCount()).isEqualTo(40);
        assertThat(aggregate.calculatedAt()).isEqualTo(AT);
        assertThat(aggregate.finalized()).isFalse();
        assertThat(aggregate.createdAt()).isNull();
    }
}
