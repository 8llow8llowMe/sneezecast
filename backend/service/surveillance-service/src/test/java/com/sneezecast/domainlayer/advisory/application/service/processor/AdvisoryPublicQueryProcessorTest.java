package com.sneezecast.domainlayer.advisory.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryErrorCode;
import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryException;
import com.sneezecast.domainlayer.advisory.application.info.RegionAdvisoryInfo;
import com.sneezecast.domainlayer.advisory.application.info.RegionAdvisoryInfo.CitedGroup;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryDistrictQueryPort;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryRepositoryPort;
import com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus;
import com.sneezecast.domainlayer.advisory.domain.model.Advisory;
import com.sneezecast.domainlayer.advisory.domain.model.AdvisoryCitation;
import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 공개 동네 안내의 세 갈래 — 발행 중(정정 사슬 포함) · 철회 표시 · 안내 없음 — 과 주 · 코드 확인. 포트는 mock 이다.
 */
class AdvisoryPublicQueryProcessorTest {

    // 2026-10-14(수) 10:00 KST = 2026-W42. 조회 창은 2025-W43 ~ 2026-W42 다.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T01:00:00Z"), ReportWeek.ZONE);
    private static final String GARAK_1 = "11240660";
    private static final ReportWeek W40 = ReportWeek.parse("2026-W40");
    // 참여 150 · 증상 30(20.0%) · 호흡기 20(13.3%) · 장관 15(10.0%)
    private static final AdvisoryCitation CITATION = new AdvisoryCitation(150, 30, 20, 15, AggregateLevel.HIGH, "2026-10-08");
    private static final LocalDateTime DAY_1 = LocalDateTime.of(2026, 10, 6, 10, 0);
    private static final LocalDateTime DAY_2 = LocalDateTime.of(2026, 10, 7, 10, 0);
    private static final LocalDateTime DAY_3 = LocalDateTime.of(2026, 10, 8, 10, 0);

    private AdvisoryRepositoryPort repositoryPort;
    private AdvisoryDistrictQueryPort districtPort;
    private AdvisoryPublicQueryProcessor processor;

    @BeforeEach
    void setUp() {
        repositoryPort = mock(AdvisoryRepositoryPort.class);
        districtPort = mock(AdvisoryDistrictQueryPort.class);
        processor = new AdvisoryPublicQueryProcessor(repositoryPort, districtPort, CLOCK);
        when(districtPort.existsByCode(GARAK_1)).thenReturn(true);
    }

    @Test
    @DisplayName("발행 중인 안내는 제목 · 본문 · 발행 시각과 발행 시점에 고정된 인용 수치(비율 포함)를 담고, 정정이 없으면 빈 목록이다")
    void publishedCarriesCitation() {
        givenAdvisories(advisory(1L, AdvisoryStatus.PUBLISHED, DAY_1, null, null));

        RegionAdvisoryInfo info = processor.getRegionAdvisory(GARAK_1, W40.value()).orElseThrow();

        assertThat(info.districtCode()).isEqualTo(GARAK_1);
        assertThat(info.isoWeek()).isEqualTo(W40);
        assertThat(info.retractedAt()).isNull();
        RegionAdvisoryInfo.Published published = info.published();
        assertThat(published.title()).isEqualTo("제목 1");
        assertThat(published.body()).isEqualTo("본문 1");
        assertThat(published.publishedAt()).isEqualTo(DAY_1);
        assertThat(published.level()).isEqualTo(AggregateLevel.HIGH);
        assertThat(published.participantCount()).isEqualTo(150);
        assertThat(published.symptomaticCount()).isEqualTo(30);
        assertThat(published.symptomRate()).isEqualByComparingTo("20.0");
        assertThat(published.groups()).containsExactly(
            new CitedGroup(SymptomGroup.RESPIRATORY, 20, new BigDecimal("13.3")),
            new CitedGroup(SymptomGroup.ENTERIC, 15, new BigDecimal("10.0")));
        assertThat(published.correctedAts()).isEmpty();
    }

    @Test
    @DisplayName("정정 사슬 — 지금 안내에서 corrects_advisory_id 를 따라가며 고리마다 정정한 쪽의 발행 시각을 최근 것부터 모은다")
    void correctionChainIsCollected() {
        givenAdvisories(
            advisory(1L, AdvisoryStatus.RETRACTED, DAY_1, DAY_2, null),
            advisory(2L, AdvisoryStatus.RETRACTED, DAY_2, DAY_3, 1L),
            advisory(3L, AdvisoryStatus.PUBLISHED, DAY_3, null, 2L));

        RegionAdvisoryInfo info = processor.getRegionAdvisory(GARAK_1, W40.value()).orElseThrow();

        assertThat(info.published().title()).isEqualTo("제목 3");
        assertThat(info.published().correctedAts()).containsExactly(DAY_3, DAY_2);
        assertThat(info.retractedAt()).isNull();
    }

    @Test
    @DisplayName("철회 뒤 같은 주에 새 안내가 발행되면 새 안내만 보이고 철회 표시는 없다 — 정정으로 잇지 않았으면 정정 이력도 없다")
    void newPublicationHidesEarlierRetraction() {
        givenAdvisories(
            advisory(1L, AdvisoryStatus.RETRACTED, DAY_1, DAY_2, null),
            advisory(2L, AdvisoryStatus.PUBLISHED, DAY_3, null, null));

        RegionAdvisoryInfo info = processor.getRegionAdvisory(GARAK_1, W40.value()).orElseThrow();

        assertThat(info.published().title()).isEqualTo("제목 2");
        assertThat(info.published().correctedAts()).isEmpty();
        assertThat(info.retractedAt()).isNull();
    }

    @Test
    @DisplayName("발행 중인 안내가 없고 철회된 안내가 있으면 철회 시각만 담는다 — 제목 · 본문 · 인용 수치를 담지 않는다")
    void retractedShowsOnlyRetraction() {
        givenAdvisories(advisory(1L, AdvisoryStatus.RETRACTED, DAY_1, DAY_2, null));

        RegionAdvisoryInfo info = processor.getRegionAdvisory(GARAK_1, W40.value()).orElseThrow();

        assertThat(info.published()).isNull();
        assertThat(info.retractedAt()).isEqualTo(DAY_2);
    }

    @Test
    @DisplayName("정정으로 내려간 안내는 철회 표시가 아니다 — 정정한 안내까지 철회됐으면 정정되지 않은 쪽(사슬의 끝)의 철회 시각이다")
    void correctedRetractionIsNotShown() {
        givenAdvisories(
            advisory(1L, AdvisoryStatus.RETRACTED, DAY_1, DAY_3, null),
            advisory(2L, AdvisoryStatus.RETRACTED, DAY_1, DAY_2, 1L));

        RegionAdvisoryInfo info = processor.getRegionAdvisory(GARAK_1, W40.value()).orElseThrow();

        // 1 은 철회 시각이 더 늦지만 2 가 정정했으므로 후보가 아니다.
        assertThat(info.retractedAt()).isEqualTo(DAY_2);
    }

    @Test
    @DisplayName("정정 관계가 없는 철회가 여럿이면 가장 최근 철회 하나다")
    void latestOfSeveralRetractions() {
        givenAdvisories(
            advisory(1L, AdvisoryStatus.RETRACTED, DAY_1, DAY_3, null),
            advisory(2L, AdvisoryStatus.RETRACTED, DAY_1, DAY_2, null));

        assertThat(processor.getRegionAdvisory(GARAK_1, W40.value()).orElseThrow().retractedAt()).isEqualTo(DAY_3);
    }

    @Test
    @DisplayName("철회 시각이 같으면 id 가 큰(나중에 만든) 철회를 고른다 — 결과에는 시각만 있어 순서 규칙으로 고정한다")
    void sameRetractionTimeUsesId() {
        Advisory smaller = advisory(3L, AdvisoryStatus.RETRACTED, DAY_1, DAY_2, null);
        Advisory larger = advisory(4L, AdvisoryStatus.RETRACTED, DAY_1, DAY_2, null);

        assertThat(List.of(larger, smaller).stream().max(AdvisoryPublicQueryProcessor.RETRACTION_ORDER)).contains(larger);
        assertThat(List.of(smaller, larger).stream().max(AdvisoryPublicQueryProcessor.RETRACTION_ORDER)).contains(larger);
    }

    @Test
    @DisplayName("공개 상태가 아닌 안내(저장 포트가 잘못 돌려준 초안)가 가리키는 철회는 정정된 것으로 보지 않는다 — 철회 표시가 남는다")
    void nonPublicCorrectorDoesNotHideRetraction() {
        givenAdvisories(
            advisory(1L, AdvisoryStatus.RETRACTED, DAY_1, DAY_2, null),
            advisory(2L, AdvisoryStatus.DRAFT, null, null, 1L));

        assertThat(processor.getRegionAdvisory(GARAK_1, W40.value()).orElseThrow().retractedAt()).isEqualTo(DAY_2);
    }

    @Test
    @DisplayName("안내가 없으면 empty 다")
    void noAdvisoryIsEmpty() {
        givenAdvisories();

        assertThat(processor.getRegionAdvisory(GARAK_1, W40.value())).isEmpty();
    }

    @Test
    @DisplayName("저장 포트가 공개 상태 밖(초안 · 승인)을 돌려줘도 보이지 않는다")
    void nonPublicStatusesAreIgnored() {
        givenAdvisories(
            advisory(1L, AdvisoryStatus.DRAFT, null, null, null),
            advisory(2L, AdvisoryStatus.APPROVED, null, null, null),
            advisory(3L, AdvisoryStatus.EDITED, null, null, null));

        assertThat(processor.getRegionAdvisory(GARAK_1, W40.value())).isEmpty();
    }

    @Test
    @DisplayName("발행 중인 안내가 여럿이면(규칙이 깨진 행) 500 대신 가장 최근 발행 하나를 고른다")
    void multiplePublishedPicksLatest() {
        givenAdvisories(
            advisory(2L, AdvisoryStatus.PUBLISHED, DAY_2, null, null),
            advisory(1L, AdvisoryStatus.PUBLISHED, DAY_1, null, null));

        assertThat(processor.getRegionAdvisory(GARAK_1, W40.value()).orElseThrow().published().title()).isEqualTo("제목 2");
    }

    @Test
    @DisplayName("정정 사슬이 끊기거나(가리키는 안내 없음) 돌면 거기서 멈추고 500 을 내지 않는다")
    void brokenChainStops() {
        givenAdvisories(
            advisory(1L, AdvisoryStatus.RETRACTED, DAY_1, DAY_2, 2L),
            advisory(2L, AdvisoryStatus.RETRACTED, DAY_2, DAY_3, 1L),
            advisory(3L, AdvisoryStatus.PUBLISHED, DAY_3, null, 2L));

        assertThat(processor.getRegionAdvisory(GARAK_1, W40.value()).orElseThrow().published().correctedAts()).containsExactly(DAY_3, DAY_2);

        givenAdvisories(advisory(4L, AdvisoryStatus.PUBLISHED, DAY_3, null, 999L));

        assertThat(processor.getRegionAdvisory(GARAK_1, W40.value()).orElseThrow().published().correctedAts()).isEmpty();
    }

    @Test
    @DisplayName("없는 코드는 ADVISORY_008 이다")
    void missingDistrictIsNotFound() {
        assertThatThrownBy(() -> processor.getRegionAdvisory("99999999", W40.value()))
            .isInstanceOfSatisfying(AdvisoryException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(AdvisoryErrorCode.DISTRICT_NOT_FOUND));
        verify(repositoryPort, never()).findAllPublicByDistrictCodeAndIsoWeek(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-W43", "2025-W42", "2025-W53"})
    @DisplayName("미래 주 · 51주보다 이전 · 그 해에 없는 주는 ADVISORY_009 이고 메시지에 범위가 있으며, 행정동 · 안내를 읽지 않는다")
    void notQueryableWeek(String week) {
        assertThatThrownBy(() -> processor.getRegionAdvisory(GARAK_1, week))
            .isInstanceOfSatisfying(AdvisoryException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(AdvisoryErrorCode.WEEK_NOT_QUERYABLE);
                assertThat(exception.getMessage()).contains("2025-W43 ~ 2026-W42");
            });
        verify(districtPort, never()).existsByCode(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-W42", "2025-W43"})
    @DisplayName("현재 주 ~ 51주 전은 조회할 수 있다")
    void queryableWeek(String week) {
        assertThat(processor.resolveWeek(week)).isEqualTo(ReportWeek.parse(week));
    }

    private void givenAdvisories(Advisory... advisories) {
        when(repositoryPort.findAllPublicByDistrictCodeAndIsoWeek(GARAK_1, W40)).thenReturn(List.of(advisories));
    }

    private static Advisory advisory(long id, AdvisoryStatus status, LocalDateTime publishedAt, LocalDateTime retractedAt, Long correctsAdvisoryId) {
        return Advisory.builder()
            .id(id)
            .aggregateId(7L)
            .districtCode(GARAK_1)
            .isoWeek(W40)
            .status(status)
            .title("제목 " + id)
            .body("본문 " + id)
            .citation(CITATION)
            .correctsAdvisoryId(correctsAdvisoryId)
            .publishedAt(publishedAt)
            .retractedAt(retractedAt)
            .version(0L)
            .build();
    }
}
