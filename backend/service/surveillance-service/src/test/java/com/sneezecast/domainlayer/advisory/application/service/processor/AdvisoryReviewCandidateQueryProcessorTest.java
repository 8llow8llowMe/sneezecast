package com.sneezecast.domainlayer.advisory.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryErrorCode;
import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryException;
import com.sneezecast.domainlayer.advisory.application.info.ReviewCandidateListInfo;
import com.sneezecast.domainlayer.advisory.application.info.ReviewCandidateListInfo.AdvisoryState;
import com.sneezecast.domainlayer.advisory.application.info.ReviewCandidateListInfo.Candidate;
import com.sneezecast.domainlayer.advisory.application.info.ReviewCandidateListInfo.GroupCount;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryAggregateQueryPort;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryDistrictQueryPort;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryRepositoryPort;
import com.sneezecast.domainlayer.advisory.application.port.out.query.AdvisoryCandidateAggregateQueryResult;
import com.sneezecast.domainlayer.advisory.application.port.out.query.AdvisoryDistrictQueryResult;
import com.sneezecast.domainlayer.advisory.application.service.ReviewCandidateProperties;
import com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus;
import com.sneezecast.domainlayer.advisory.domain.enums.ReviewSignal;
import com.sneezecast.domainlayer.advisory.domain.model.Advisory;
import com.sneezecast.domainlayer.advisory.domain.model.AdvisoryCitation;
import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.aggregate.domain.enums.InsufficientReason;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * 검토 후보 — 후보 고르기(신호 · 안내), 자료 부족 처리, 안내 상태 선택, 정렬, 주 해석, 조회 수. 포트는 mock 이다. 신호 경계값은 {@code ReviewCandidateRuleTest}.
 */
class AdvisoryReviewCandidateQueryProcessorTest {

    // 2026-10-14(수) 10:00 KST = 2026-W42. 기본 주는 2026-W41, 조회 창은 2025-W43 ~ 2026-W42 다.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T01:00:00Z"), ReportWeek.ZONE);
    private static final ReportWeek W40 = ReportWeek.parse("2026-W40");
    private static final ReportWeek W39 = W40.previous();
    private static final LocalDateTime DAY_1 = LocalDateTime.of(2026, 10, 6, 10, 0);
    private static final LocalDateTime DAY_2 = LocalDateTime.of(2026, 10, 7, 10, 0);
    private static final AdvisoryCitation CITATION = new AdvisoryCitation(150, 30, 20, 15, AggregateLevel.HIGH, "2026-10-08");

    private AdvisoryAggregateQueryPort aggregatePort;
    private AdvisoryRepositoryPort repositoryPort;
    private AdvisoryDistrictQueryPort districtPort;
    private AdvisoryReviewCandidateQueryProcessor processor;
    private final List<AdvisoryCandidateAggregateQueryResult> rows = new ArrayList<>();
    private final List<Advisory> advisories = new ArrayList<>();

    @BeforeEach
    void setUp() {
        aggregatePort = mock(AdvisoryAggregateQueryPort.class);
        repositoryPort = mock(AdvisoryRepositoryPort.class);
        districtPort = mock(AdvisoryDistrictQueryPort.class);
        processor = new AdvisoryReviewCandidateQueryProcessor(aggregatePort, repositoryPort, districtPort, new ReviewCandidateProperties(30, 20), CLOCK);
        when(aggregatePort.findAllByIsoWeekIn(anyCollection())).thenReturn(rows);
        when(repositoryPort.findAllByDistrictCodeInAndIsoWeek(anyCollection(), any())).thenReturn(advisories);
        when(districtPort.findAllByCodeIn(anyCollection())).thenAnswer(invocation -> {
            Collection<String> codes = invocation.getArgument(0);
            return codes.stream().filter(code -> !code.equals("99999999")).map(code -> new AdvisoryDistrictQueryResult(code, code + "동", "서울특별시 마포구", true)).toList();
        });
    }

    @Test
    @DisplayName("기준선 후보는 수치(증상 수 · 비율 · 기준선 · 변화 %p · 증상군)와 전주 참여 · 수정 수를 담고, 마감된 칸이면 초안을 만들 수 있다")
    void measuredCandidateCarriesNumbers() {
        // 참여 150 · 증상 30(20.0%) · 호흡기 20(13.3%) · 장관 15(10.0%) · 기준선 60/600(10.0%)
        rows.add(row("11440660", W40, 150, 0, AggregateLevel.HIGH, null, true));
        rows.add(row("11440660", W39, 140, 0, AggregateLevel.NORMAL, null, true));

        ReviewCandidateListInfo info = processor.getCandidates(W40.value());

        assertThat(info.isoWeek()).isEqualTo(W40);
        assertThat(info.finalized()).isTrue();
        assertThat(info.surgeChangePercent()).isEqualTo(30);
        assertThat(info.repeatRevisedPercent()).isEqualTo(20);
        Candidate candidate = info.candidates().getFirst();
        assertThat(candidate.districtName()).isEqualTo("11440660동");
        assertThat(candidate.sigungu()).isEqualTo("서울특별시 마포구");
        assertThat(candidate.active()).isTrue();
        assertThat(candidate.signals()).containsExactly(ReviewSignal.BASELINE);
        assertThat(candidate.draftable()).isTrue();
        assertThat(candidate.participantCount()).isEqualTo(150);
        assertThat(candidate.previousParticipantCount()).isEqualTo(140);
        assertThat(candidate.measured().symptomaticCount()).isEqualTo(30);
        assertThat(candidate.measured().symptomRate()).isEqualByComparingTo("20.0");
        assertThat(candidate.measured().baselineRate()).isEqualByComparingTo("10.0");
        assertThat(candidate.measured().baselineDeltaPp()).isEqualByComparingTo("10.0");
        assertThat(candidate.measured().groups()).containsExactly(
            new GroupCount(SymptomGroup.RESPIRATORY, 20, new BigDecimal("13.3")),
            new GroupCount(SymptomGroup.ENTERIC, 15, new BigDecimal("10.0")));
        assertThat(candidate.advisory()).isNull();
    }

    @Test
    @DisplayName("자료 부족(급변) 동네도 급증 · 반복 신호가 있으면 후보 — 수치 없이 참여 · 전주 참여 · 수정 수만, 초안은 만들 수 없다")
    void insufficientCandidateHasNoNumbers() {
        rows.add(row("11440660", W40, 300, 60, AggregateLevel.INSUFFICIENT, InsufficientReason.UNSTABLE, true));
        rows.add(row("11440660", W39, 120, 0, AggregateLevel.NORMAL, null, true));

        Candidate candidate = processor.getCandidates(W40.value()).candidates().getFirst();

        assertThat(candidate.signals()).containsExactly(ReviewSignal.SURGE, ReviewSignal.REPEAT);
        assertThat(candidate.level()).isEqualTo(AggregateLevel.INSUFFICIENT);
        assertThat(candidate.insufficientReason()).isEqualTo(InsufficientReason.UNSTABLE);
        assertThat(candidate.measured()).isNull();
        assertThat(candidate.draftable()).isFalse();
        assertThat(candidate.participantCount()).isEqualTo(300);
        assertThat(candidate.previousParticipantCount()).isEqualTo(120);
        assertThat(candidate.revisedReportCount()).isEqualTo(60);
    }

    @Test
    @DisplayName("신호도 안내도 없는 동네 · 참여 부족(LOW_SAMPLE) 동네는 후보가 아니다. 신호가 없어도 그 주에 안내가 있으면 후보다(신호 없음)")
    void candidatesNeedSignalOrAdvisory() {
        rows.add(row("11000001", W40, 150, 0, AggregateLevel.NORMAL, null, true));
        rows.add(row("11000002", W40, 50, 50, AggregateLevel.INSUFFICIENT, InsufficientReason.LOW_SAMPLE, true));
        rows.add(row("11000003", W40, 150, 0, AggregateLevel.NORMAL, null, true));
        advisories.add(advisory(1L, "11000003", AdvisoryStatus.DRAFT));

        List<Candidate> candidates = processor.getCandidates(W40.value()).candidates();

        assertThat(candidates).extracting(Candidate::districtCode).containsExactly("11000003");
        assertThat(candidates.getFirst().signals()).isEmpty();
        assertThat(candidates.getFirst().advisory()).isEqualTo(new AdvisoryState(1L, AdvisoryStatus.DRAFT, 0L));
    }

    @Test
    @DisplayName("마감 전 주는 finalized=false 이고 수치가 있어도 초안을 만들 수 없다(draftable=false)")
    void notFinalizedWeekIsNotDraftable() {
        rows.add(row("11440660", W40, 150, 0, AggregateLevel.HIGH, null, false));

        ReviewCandidateListInfo info = processor.getCandidates(W40.value());

        assertThat(info.finalized()).isFalse();
        assertThat(info.candidates().getFirst().draftable()).isFalse();
        assertThat(info.candidates().getFirst().measured()).isNotNull();
    }

    @Test
    @DisplayName("안내 상태 — 발행 중이 검토 중보다, 검토 중(가장 최근 id)이 철회보다 먼저다. 철회만 있으면 가장 최근 철회, 없으면 null")
    void advisoryStateSelection() {
        Advisory draft = advisory(10L, "11440660", AdvisoryStatus.DRAFT);
        Advisory approved = advisory(11L, "11440660", AdvisoryStatus.APPROVED);
        Advisory published = advisory(5L, "11440660", AdvisoryStatus.PUBLISHED);
        Advisory retractedEarly = retracted(3L, DAY_2);
        Advisory retractedLate = retracted(2L, DAY_2.plusDays(1));

        assertThat(AdvisoryReviewCandidateQueryProcessor.selectAdvisory(List.of(draft, approved, published, retractedLate)))
            .contains(new AdvisoryState(5L, AdvisoryStatus.PUBLISHED, 0L));
        assertThat(AdvisoryReviewCandidateQueryProcessor.selectAdvisory(List.of(approved, draft, retractedLate)))
            .contains(new AdvisoryState(11L, AdvisoryStatus.APPROVED, 0L));
        assertThat(AdvisoryReviewCandidateQueryProcessor.selectAdvisory(List.of(retractedEarly, retractedLate)))
            .contains(new AdvisoryState(2L, AdvisoryStatus.RETRACTED, 0L));
        assertThat(AdvisoryReviewCandidateQueryProcessor.selectAdvisory(List.of())).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("정렬 — 신호가 있는 후보 먼저, 단계(많이 → 조금 → 평소 → 자료 부족), 코드 순. 신호 없이 안내만 있는 동네는 맨 뒤")
    void candidatesAreSorted() {
        rows.add(row("11000009", W40, 150, 0, AggregateLevel.HIGH, null, true));
        advisories.add(advisory(1L, "11000009", AdvisoryStatus.PUBLISHED));
        rows.add(row("11000001", W40, 150, 0, AggregateLevel.NORMAL, null, true));
        advisories.add(advisory(2L, "11000001", AdvisoryStatus.DRAFT));
        rows.add(row("11000002", W40, 150, 30, AggregateLevel.INSUFFICIENT, InsufficientReason.NO_BASELINE, true));
        rows.add(row("11000003", W40, 150, 30, AggregateLevel.NORMAL, null, true));
        rows.add(row("11000004", W40, 150, 0, AggregateLevel.SLIGHT, null, true));
        rows.add(row("11000006", W40, 150, 0, AggregateLevel.HIGH, null, true));
        rows.add(row("11000005", W40, 150, 0, AggregateLevel.HIGH, null, true));

        assertThat(processor.getCandidates(W40.value()).candidates()).extracting(Candidate::districtCode)
            .containsExactly("11000005", "11000006", "11000009", "11000004", "11000003", "11000002", "11000001");
    }

    @Test
    @DisplayName("조회 수 — 집계 [주, 전주] 1 · 안내 1(마감됐고 자료 부족이 아닌 행의 동네 IN — 안내는 그런 행으로만 만들어진다) · 행정동 1(후보 IN). "
        + "행정동 행이 없는 후보는 이름 없이 보인다")
    void readsInBulk() {
        rows.add(row("11000001", W40, 150, 0, AggregateLevel.HIGH, null, true));
        rows.add(row("11000002", W40, 150, 0, AggregateLevel.NORMAL, null, true));
        rows.add(row("99999999", W40, 150, 0, AggregateLevel.HIGH, null, true));
        rows.add(row("11000003", W39, 150, 0, AggregateLevel.NORMAL, null, true));
        // 안내 IN 대상이 아니다 — 자료 부족 행(초안 전제 ADVISORY_004), 마감 전 행(ADVISORY_003)
        rows.add(row("11000004", W40, 50, 0, AggregateLevel.INSUFFICIENT, InsufficientReason.LOW_SAMPLE, true));
        rows.add(row("11000005", W40, 150, 0, AggregateLevel.NORMAL, null, false));

        List<Candidate> candidates = processor.getCandidates(W40.value()).candidates();

        verify(aggregatePort, times(1)).findAllByIsoWeekIn(List.of(W40, W39));
        ArgumentCaptor<Collection<String>> advisoryCodes = codesCaptor();
        verify(repositoryPort, times(1)).findAllByDistrictCodeInAndIsoWeek(advisoryCodes.capture(), any());
        assertThat(advisoryCodes.getValue()).containsExactlyInAnyOrder("11000001", "11000002", "99999999");
        ArgumentCaptor<Collection<String>> districtCodes = codesCaptor();
        verify(districtPort, times(1)).findAllByCodeIn(districtCodes.capture());
        assertThat(districtCodes.getValue()).containsExactlyInAnyOrder("11000001", "99999999");
        Candidate missing = candidates.stream().filter(c -> c.districtCode().equals("99999999")).findFirst().orElseThrow();
        assertThat(missing.districtName()).isNull();
        assertThat(missing.sigungu()).isNull();
        assertThat(missing.active()).isFalse();
    }

    @Test
    @DisplayName("집계 행이 없는 주는 후보 없음 · finalized=false")
    void emptyWeek() {
        ReviewCandidateListInfo info = processor.getCandidates(W40.value());

        assertThat(info.candidates()).isEmpty();
        assertThat(info.finalized()).isFalse();
    }

    @Test
    @DisplayName("주를 생략하면 현재 주 − 1 이다. 현재 주와 51주 전은 받는다")
    void resolvesWeek() {
        assertThat(processor.resolveWeek(null)).isEqualTo(ReportWeek.parse("2026-W41"));
        assertThat(processor.resolveWeek("2026-W42")).isEqualTo(ReportWeek.parse("2026-W42"));
        assertThat(processor.resolveWeek("2025-W43")).isEqualTo(ReportWeek.parse("2025-W43"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-W43", "2025-W42", "2025-W53"})
    @DisplayName("미래 주 · 51주보다 이전 · 그 해에 없는 주는 ADVISORY_009 이고 메시지에 조회 범위를 싣는다")
    void rejectsWeeksOutsideWindow(String week) {
        assertThatThrownBy(() -> processor.getCandidates(week))
            .isInstanceOfSatisfying(AdvisoryException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AdvisoryErrorCode.WEEK_NOT_QUERYABLE))
            .hasMessageContaining("2025-W43 ~ 2026-W42");
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Collection<String>> codesCaptor() {
        return ArgumentCaptor.forClass(Collection.class);
    }

    /** 증상 30 · 호흡기 20 · 장관 15. 자료 부족이 아니면 기준선 60/600(10.0%). */
    private static AdvisoryCandidateAggregateQueryResult row(String code, ReportWeek week, int participants, int revised, AggregateLevel level,
        InsufficientReason reason, boolean finalized) {
        boolean insufficient = level == AggregateLevel.INSUFFICIENT;
        int symptomatic = Math.min(30, participants);
        return new AdvisoryCandidateAggregateQueryResult(code, week, participants, symptomatic, Math.min(20, symptomatic), Math.min(15, symptomatic), revised,
            level, reason, insufficient ? null : 600, insufficient ? null : 60, finalized);
    }

    private static Advisory advisory(long id, String code, AdvisoryStatus status) {
        Advisory draft = Advisory.newDraft(id, 7L, code, W40, CITATION, "제목 " + id, "본문 " + id);
        return switch (status) {
            case DRAFT -> draft;
            case APPROVED -> draft.toBuilder().status(AdvisoryStatus.APPROVED).build();
            case PUBLISHED -> draft.toBuilder().status(AdvisoryStatus.PUBLISHED).publishedAt(DAY_1).build();
            default -> throw new IllegalArgumentException("테스트가 만들지 않는 상태: " + status);
        };
    }

    private static Advisory retracted(long id, LocalDateTime retractedAt) {
        return Advisory.newDraft(id, 7L, "11440660", W40, CITATION, "제목 " + id, "본문 " + id).toBuilder()
            .status(AdvisoryStatus.RETRACTED).publishedAt(DAY_1).retractedAt(retractedAt).build();
    }
}
