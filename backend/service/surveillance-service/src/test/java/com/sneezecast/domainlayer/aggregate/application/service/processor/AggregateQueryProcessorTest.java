package com.sneezecast.domainlayer.aggregate.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.aggregate.application.exception.AggregateErrorCode;
import com.sneezecast.domainlayer.aggregate.application.exception.AggregateException;
import com.sneezecast.domainlayer.aggregate.application.info.AggregateWeekInfo;
import com.sneezecast.domainlayer.aggregate.application.info.DistrictWeeklyAggregateInfo;
import com.sneezecast.domainlayer.aggregate.application.info.DistrictWeeklyAggregateInfo.GroupRate;
import com.sneezecast.domainlayer.aggregate.application.port.out.AggregateDistrictQueryPort;
import com.sneezecast.domainlayer.aggregate.application.port.out.DistrictWeeklyAggregateRepositoryPort;
import com.sneezecast.domainlayer.aggregate.application.port.out.query.AggregateDistrictQueryResult;
import com.sneezecast.domainlayer.aggregate.application.service.AggregateProperties;
import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.aggregate.domain.enums.GroupTrend;
import com.sneezecast.domainlayer.aggregate.domain.enums.InsufficientReason;
import com.sneezecast.domainlayer.aggregate.domain.model.DistrictWeeklyAggregate;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 공개 집계 조회 규칙 — 자료 부족 수치 숨김, 행 없는 동, 찾은 동네 담기, 주 범위, 증상군 변화 조건. 포트는 mock, 판정 규칙은 운영 설정과 같은 값이다.
 */
class AggregateQueryProcessorTest {

    // 2026-10-14(수) 10:00 KST = 2026-W42. 조회 창은 2025-W43 ~ 2026-W42 다.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T01:00:00Z"), ReportWeek.ZONE);
    private static final ReportWeek CURRENT = ReportWeek.parse("2026-W42");
    private static final ReportWeek PREVIOUS = ReportWeek.parse("2026-W41");
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 10, 14, 9, 50);

    private static final AggregateDistrictQueryResult SEOGYO = new AggregateDistrictQueryResult("11440660", "서교동", "서울특별시 마포구", true);
    private static final AggregateDistrictQueryResult HAPJEONG = new AggregateDistrictQueryResult("11440680", "합정동", "서울특별시 마포구", true);
    private static final AggregateDistrictQueryResult YEOKSAM = new AggregateDistrictQueryResult("11230510", "역삼1동", "서울특별시 강남구", true);
    private static final AggregateDistrictQueryResult RETIRED = new AggregateDistrictQueryResult("21120560", "녹산동", "부산광역시 강서구", false);

    private final AtomicLong ids = new AtomicLong();

    private DistrictWeeklyAggregateRepositoryPort repositoryPort;
    private AggregateDistrictQueryPort districtPort;
    private AggregateQueryProcessor processor;

    @BeforeEach
    void setUp() {
        repositoryPort = mock(DistrictWeeklyAggregateRepositoryPort.class);
        districtPort = mock(AggregateDistrictQueryPort.class);
        AggregateProperties properties = new AggregateProperties("2026-10-08", 100, 50, 3, 8, 4, 8);
        processor = new AggregateQueryProcessor(repositoryPort, districtPort, properties, CLOCK);
        when(districtPort.findByCode(any())).thenReturn(Optional.empty());
        when(repositoryPort.findAllByDistrictCodeInAndIsoWeekIn(anyCollection(), anyCollection())).thenReturn(List.of());
    }

    @Test
    @DisplayName("고정 Clock 의 현재 주는 2026-W42 다 (테스트 전제)")
    void clockIsWeek42() {
        assertThat(ReportWeek.of(CLOCK.instant())).isEqualTo(CURRENT);
    }

    @Test
    @DisplayName("집계 행이 없는 동은 INSUFFICIENT · LOW_SAMPLE · 참여 0 이고 수치 · 계산 시각이 없다")
    void districtWithoutRowIsInsufficient() {
        givenDistrict(SEOGYO);

        AggregateWeekInfo week = processor.getDistrictWeek(SEOGYO.code(), null);

        DistrictWeeklyAggregateInfo district = week.districts().getFirst();
        assertThat(district.level()).isEqualTo(AggregateLevel.INSUFFICIENT);
        assertThat(district.insufficientReason()).isEqualTo(InsufficientReason.LOW_SAMPLE);
        assertThat(district.participantCount()).isZero();
        assertThat(district.calculatedAt()).isNull();
        assertThat(district.measured()).isNull();
        assertThat(week.isoWeek()).isEqualTo(CURRENT);
        assertThat(week.publicThreshold()).isEqualTo(100);
        assertThat(week.slightDeltaPp()).isEqualTo(3);
        assertThat(week.highDeltaPp()).isEqualTo(8);
    }

    @Test
    @DisplayName("INSUFFICIENT 행은 숫자가 있어도 수치를 담지 않는다 — 참여자 수 · 이유 · 계산 시각만 남긴다")
    void insufficientRowHidesNumbers() {
        givenDistrict(SEOGYO);
        givenRows(insufficient(SEOGYO.code(), CURRENT, 150, 60, 50, 20, InsufficientReason.NO_BASELINE));

        DistrictWeeklyAggregateInfo district = processor.getDistrictWeek(SEOGYO.code(), null).districts().getFirst();

        assertThat(district.level()).isEqualTo(AggregateLevel.INSUFFICIENT);
        assertThat(district.insufficientReason()).isEqualTo(InsufficientReason.NO_BASELINE);
        assertThat(district.participantCount()).isEqualTo(150);
        assertThat(district.calculatedAt()).isEqualTo(CALCULATED_AT);
        assertThat(district.measured()).isNull();
    }

    @Test
    @DisplayName("수치가 있는 단계는 비율을 퍼센트 소수 첫째 자리 반올림으로 담고, 기준선 비율은 행의 기준선 두 수로 계산한다")
    void measuredRowCarriesRates() {
        givenDistrict(SEOGYO);
        // 20/120 = 16.67% · 호흡기 15/120 = 12.5% · 장관 9/120 = 7.5% · 기준선 41/400 = 10.25% → 10.3 (HALF_UP)
        givenRows(measured(SEOGYO.code(), CURRENT, AggregateLevel.SLIGHT, 120, 20, 15, 9, 400, 41));

        DistrictWeeklyAggregateInfo.Measured measured = processor.getDistrictWeek(SEOGYO.code(), null).districts().getFirst().measured();

        assertThat(measured.symptomaticCount()).isEqualTo(20);
        assertThat(measured.symptomRate()).isEqualByComparingTo("16.7");
        assertThat(measured.baselineRate()).isEqualByComparingTo("10.3");
        assertThat(measured.groups()).extracting(GroupRate::group).containsExactly(SymptomGroup.RESPIRATORY, SymptomGroup.ENTERIC);
        assertThat(measured.groups()).extracting(GroupRate::rate).containsExactly(new BigDecimal("12.5"), new BigDecimal("7.5"));
        assertThat(measured.groups()).extracting(GroupRate::trend).containsOnlyNulls();
    }

    @Test
    @DisplayName("이번 주 · 전주가 모두 수치가 있으면 증상군 변화를 전주 대비 %p 로 계산한다")
    void trendWhenBothWeeksMeasured() {
        givenDistrict(SEOGYO);
        // 호흡기 12.5% vs 전주 5% → +7.5%p SLIGHT. 장관 7.5% vs 전주 10.5% → −3%p DOWN.
        givenRows(measured(SEOGYO.code(), CURRENT, AggregateLevel.SLIGHT, 120, 20, 15, 9, 400, 40),
            measured(SEOGYO.code(), PREVIOUS, AggregateLevel.NORMAL, 200, 30, 10, 21, 400, 40));

        DistrictWeeklyAggregateInfo.Measured measured = processor.getDistrictWeek(SEOGYO.code(), null).districts().getFirst().measured();

        assertThat(measured.groups()).extracting(GroupRate::trend).containsExactly(GroupTrend.SLIGHT, GroupTrend.DOWN);
    }

    @Test
    @DisplayName("전주가 자료 부족이면 전주 행에 숫자가 있어도 증상군 변화를 내리지 않는다")
    void noTrendWhenPreviousInsufficient() {
        givenDistrict(SEOGYO);
        givenRows(measured(SEOGYO.code(), CURRENT, AggregateLevel.HIGH, 120, 40, 40, 0, 400, 40),
            insufficient(SEOGYO.code(), PREVIOUS, 300, 10, 10, 0, InsufficientReason.UNSTABLE));

        DistrictWeeklyAggregateInfo.Measured measured = processor.getDistrictWeek(SEOGYO.code(), null).districts().getFirst().measured();

        assertThat(measured.groups()).extracting(GroupRate::trend).containsOnlyNulls();
    }

    @Test
    @DisplayName("이번 주가 자료 부족이면 전주가 수치가 있어도 변화를 포함해 수치가 없다")
    void noTrendWhenCurrentInsufficient() {
        givenDistrict(SEOGYO);
        givenRows(insufficient(SEOGYO.code(), CURRENT, 99, 50, 50, 0, InsufficientReason.LOW_SAMPLE),
            measured(SEOGYO.code(), PREVIOUS, AggregateLevel.NORMAL, 200, 10, 10, 0, 400, 40));

        assertThat(processor.getDistrictWeek(SEOGYO.code(), null).districts().getFirst().measured()).isNull();
    }

    @Test
    @DisplayName("이번 주 · 전주를 동네 수와 상관없이 한 번에 읽는다 (N+1 없음)")
    void readsRowsOnce() {
        when(districtPort.findActiveBySigunguCode("11440")).thenReturn(List.of(SEOGYO, HAPJEONG));

        processor.getSigunguWeek("11440", null, null);

        verify(repositoryPort, times(1)).findAllByDistrictCodeInAndIsoWeekIn(List.of(SEOGYO.code(), HAPJEONG.code()), List.of(CURRENT, PREVIOUS));
    }

    @Test
    @DisplayName("마감 여부는 그 주에 마감된 행이 있는지다")
    void finalizedFollowsWeek() {
        givenDistrict(SEOGYO);
        when(repositoryPort.existsFinalizedRow(PREVIOUS)).thenReturn(true);

        assertThat(processor.getDistrictWeek(SEOGYO.code(), PREVIOUS.value()).finalized()).isTrue();
        assertThat(processor.getDistrictWeek(SEOGYO.code(), null).finalized()).isFalse();
    }

    @Test
    @DisplayName("없는 코드는 AGGREGATE_001 이다")
    void missingDistrictIsNotFound() {
        assertThatThrownBy(() -> processor.getDistrictWeek("99999999", null))
            .isInstanceOfSatisfying(AggregateException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(AggregateErrorCode.DISTRICT_NOT_FOUND));
    }

    @Test
    @DisplayName("폐지된 코드도 오류가 아니다 — active=false 로 그 주 집계를 담는다")
    void retiredDistrictIsAnswered() {
        givenDistrict(RETIRED);
        givenRows(measured(RETIRED.code(), CURRENT, AggregateLevel.NORMAL, 120, 12, 12, 0, 400, 40));

        DistrictWeeklyAggregateInfo district = processor.getDistrictWeek(RETIRED.code(), null).districts().getFirst();

        assertThat(district.active()).isFalse();
        assertThat(district.level()).isEqualTo(AggregateLevel.NORMAL);
    }

    @Test
    @DisplayName("찾은 동네(include)가 시군구 밖이면 맨 앞에 더하고, 행이 없어도 자료 부족으로 담는다")
    void includeOutsideSigunguIsPrepended() {
        when(districtPort.findActiveBySigunguCode("11440")).thenReturn(List.of(SEOGYO, HAPJEONG));
        givenDistrict(YEOKSAM);

        List<DistrictWeeklyAggregateInfo> districts = processor.getSigunguWeek("11440", YEOKSAM.code(), null).districts();

        assertThat(districts).extracting(DistrictWeeklyAggregateInfo::code).containsExactly(YEOKSAM.code(), SEOGYO.code(), HAPJEONG.code());
        assertThat(districts.getFirst().level()).isEqualTo(AggregateLevel.INSUFFICIENT);
        assertThat(districts.getFirst().participantCount()).isZero();
    }

    @Test
    @DisplayName("찾은 동네가 이미 시군구 목록에 있으면 겹쳐 넣지 않고 순서도 그대로다")
    void includeInsideSigunguIsNotDuplicated() {
        when(districtPort.findActiveBySigunguCode("11440")).thenReturn(List.of(SEOGYO, HAPJEONG));

        List<DistrictWeeklyAggregateInfo> districts = processor.getSigunguWeek("11440", HAPJEONG.code(), null).districts();

        assertThat(districts).extracting(DistrictWeeklyAggregateInfo::code).containsExactly(SEOGYO.code(), HAPJEONG.code());
        verify(districtPort, never()).findByCode(any());
    }

    @Test
    @DisplayName("폐지된 찾은 동네도 담는다 (시군구 목록은 현행만)")
    void retiredIncludeIsAnswered() {
        when(districtPort.findActiveBySigunguCode("21120")).thenReturn(List.of());
        givenDistrict(RETIRED);

        List<DistrictWeeklyAggregateInfo> districts = processor.getSigunguWeek("21120", RETIRED.code(), null).districts();

        assertThat(districts).extracting(DistrictWeeklyAggregateInfo::code).containsExactly(RETIRED.code());
        assertThat(districts.getFirst().active()).isFalse();
    }

    @Test
    @DisplayName("없는 찾은 동네는 AGGREGATE_001 이다")
    void missingIncludeIsNotFound() {
        when(districtPort.findActiveBySigunguCode("11440")).thenReturn(List.of(SEOGYO));

        assertThatThrownBy(() -> processor.getSigunguWeek("11440", "99999999", null))
            .isInstanceOfSatisfying(AggregateException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(AggregateErrorCode.DISTRICT_NOT_FOUND));
    }

    @Test
    @DisplayName("시군구에 현행 동이 없고 찾은 동네도 없으면 빈 목록이다")
    void emptySigunguIsEmptyList() {
        when(districtPort.findActiveBySigunguCode("99999")).thenReturn(List.of());

        assertThat(processor.getSigunguWeek("99999", null, null).districts()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-W42", "2026-W01", "2025-W43"})
    @DisplayName("현재 주 ~ 51주 전은 조회할 수 있다")
    void queryableWeeks(String week) {
        assertThat(processor.resolveWeek(week)).isEqualTo(ReportWeek.parse(week));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-W43", "2027-W01", "2025-W42", "2020-W10", "2025-W53"})
    @DisplayName("미래 주 · 51주보다 이전 · 그 해에 없는 주(2025-W53)는 AGGREGATE_002 이고 메시지에 조회할 수 있는 범위를 싣는다")
    void notQueryableWeeks(String week) {
        assertThatThrownBy(() -> processor.resolveWeek(week))
            .isInstanceOfSatisfying(AggregateException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(AggregateErrorCode.WEEK_NOT_QUERYABLE);
                assertThat(exception.getMessage()).contains("2025-W43 ~ 2026-W42");
            });
    }

    @Test
    @DisplayName("주를 조회할 수 없으면 행정동 · 집계를 읽지 않는다")
    void weekIsCheckedFirst() {
        assertThatThrownBy(() -> processor.getDistrictWeek("99999999", "2027-W01"))
            .isInstanceOfSatisfying(AggregateException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(AggregateErrorCode.WEEK_NOT_QUERYABLE));
        verify(districtPort, never()).findByCode(any());
    }

    private void givenDistrict(AggregateDistrictQueryResult district) {
        when(districtPort.findByCode(district.code())).thenReturn(Optional.of(district));
    }

    private void givenRows(DistrictWeeklyAggregate... rows) {
        when(repositoryPort.findAllByDistrictCodeInAndIsoWeekIn(anyCollection(), anyCollection())).thenReturn(List.of(rows));
    }

    private DistrictWeeklyAggregate measured(String code, ReportWeek week, AggregateLevel level, int participants, int symptomatic, int respiratory,
        int enteric, int baselineParticipants, int baselineSymptomatic) {
        return row(code, week, participants, symptomatic, respiratory, enteric).level(level)
            .baselineParticipantCount(baselineParticipants).baselineSymptomaticCount(baselineSymptomatic).build();
    }

    private DistrictWeeklyAggregate insufficient(String code, ReportWeek week, int participants, int symptomatic, int respiratory, int enteric,
        InsufficientReason reason) {
        return row(code, week, participants, symptomatic, respiratory, enteric).level(AggregateLevel.INSUFFICIENT).insufficientReason(reason).build();
    }

    private DistrictWeeklyAggregate.DistrictWeeklyAggregateBuilder row(String code, ReportWeek week, int participants, int symptomatic, int respiratory,
        int enteric) {
        return DistrictWeeklyAggregate.builder()
            .id(ids.incrementAndGet())
            .districtCode(code)
            .isoWeek(week)
            .participantCount(participants)
            .symptomaticCount(symptomatic)
            .respiratoryCount(respiratory)
            .entericCount(enteric)
            .ruleVersion("2026-10-08")
            .calculatedAt(CALCULATED_AT);
    }
}
