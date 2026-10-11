package com.sneezecast.domainlayer.advisory.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryErrorCode;
import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryException;
import com.sneezecast.domainlayer.advisory.application.info.AdvisoryHistoryInfo;
import com.sneezecast.domainlayer.advisory.application.info.PublicationHistoryListInfo;
import com.sneezecast.domainlayer.advisory.application.info.PublicationHistoryListInfo.Entry;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryDistrictQueryPort;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryHistoryRepositoryPort;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryRepositoryPort;
import com.sneezecast.domainlayer.advisory.application.port.out.query.AdvisoryDistrictQueryResult;
import com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus;
import com.sneezecast.domainlayer.advisory.domain.model.Advisory;
import com.sneezecast.domainlayer.advisory.domain.model.AdvisoryCitation;
import com.sneezecast.domainlayer.advisory.domain.model.AdvisoryHistory;
import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * 발행 이력(#267) — 4주 범위 · 정렬 · 정정 관계(양방향) · 이력 묶기 · 행정동 · 주 해석 · 조회 수. 포트는 mock 이다. 상태 거르기(PUBLISHED · RETRACTED)는 저장
 * 포트의 몫이라 통합 테스트({@code AdvisoryOperatorApiIntegrationTest})가 본다.
 */
class AdvisoryPublicationHistoryQueryProcessorTest {

    // 2026-10-14(수) 10:00 KST = 2026-W42. 기본 끝 주는 2026-W41(범위 W38 ~ W41), 조회 창은 2025-W43 ~ 2026-W42 다.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T01:00:00Z"), ReportWeek.ZONE);
    private static final ReportWeek W40 = ReportWeek.parse("2026-W40");
    private static final AdvisoryCitation CITATION = new AdvisoryCitation(150, 30, 20, 15, AggregateLevel.HIGH, "2026-10-08");
    private static final LocalDateTime DAY_1 = LocalDateTime.of(2026, 10, 6, 10, 0);
    private static final LocalDateTime DAY_2 = LocalDateTime.of(2026, 10, 7, 10, 0);
    private static final LocalDateTime DAY_3 = LocalDateTime.of(2026, 10, 8, 10, 0);

    private AdvisoryRepositoryPort repositoryPort;
    private AdvisoryHistoryRepositoryPort historyPort;
    private AdvisoryDistrictQueryPort districtPort;
    private AdvisoryPublicationHistoryQueryProcessor processor;
    private final List<Advisory> advisories = new ArrayList<>();
    private final List<AdvisoryHistory> histories = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repositoryPort = mock(AdvisoryRepositoryPort.class);
        historyPort = mock(AdvisoryHistoryRepositoryPort.class);
        districtPort = mock(AdvisoryDistrictQueryPort.class);
        processor = new AdvisoryPublicationHistoryQueryProcessor(repositoryPort, historyPort, districtPort, CLOCK);
        when(repositoryPort.findAllPublicByIsoWeekIn(anyCollection())).thenReturn(advisories);
        when(historyPort.findAllByAdvisoryIdIn(anyCollection())).thenReturn(histories);
        when(districtPort.findAllByCodeIn(anyCollection())).thenAnswer(invocation -> {
            Collection<String> codes = invocation.getArgument(0);
            return codes.stream().filter(code -> !code.equals("99999999")).map(code -> new AdvisoryDistrictQueryResult(code, code + "동", "서울특별시 송파구", true)).toList();
        });
    }

    @Test
    @DisplayName("끝 주로 끝나는 4주(끝 주 − 3 ~ 끝 주)를 오래된 주부터 묻고, 범위를 돌려준다. 생략하면 현재 주 − 1 로 끝난다")
    void readsFourWeeksEndingAtWeek() {
        PublicationHistoryListInfo info = processor.getPublicationHistory(W40.value());

        verify(repositoryPort).findAllPublicByIsoWeekIn(List.of(ReportWeek.parse("2026-W37"), ReportWeek.parse("2026-W38"), ReportWeek.parse("2026-W39"), W40));
        assertThat(info.fromWeek()).isEqualTo(ReportWeek.parse("2026-W37"));
        assertThat(info.toWeek()).isEqualTo(W40);
        assertThat(info.entries()).isEmpty();

        PublicationHistoryListInfo defaulted = processor.getPublicationHistory(null);
        assertThat(defaulted.fromWeek()).isEqualTo(ReportWeek.parse("2026-W38"));
        assertThat(defaulted.toWeek()).isEqualTo(ReportWeek.parse("2026-W41"));
    }

    @Test
    @DisplayName("해를 넘는 범위 — 2026-W02 로 끝나면 2025-W51 ~ 2026-W02 다(2025년은 52주)")
    void rangeCrossesYear() {
        Clock january = Clock.fixed(Instant.parse("2026-01-14T01:00:00Z"), ReportWeek.ZONE);
        processor = new AdvisoryPublicationHistoryQueryProcessor(repositoryPort, historyPort, districtPort, january);

        processor.getPublicationHistory("2026-W02");

        verify(repositoryPort).findAllPublicByIsoWeekIn(List.of(ReportWeek.parse("2025-W51"), ReportWeek.parse("2025-W52"), ReportWeek.parse("2026-W01"),
            ReportWeek.parse("2026-W02")));
    }

    @Test
    @DisplayName("발행 시각 최근 것부터, 같으면 id 큰 것(나중에 만든 안내 — 같은 순간의 정정이면 새 안내)부터")
    void ordersByPublishedAtDescending() {
        advisories.add(published(1L, "11000001", DAY_1));
        advisories.add(published(2L, "11000002", DAY_3));
        advisories.add(retracted(3L, "11000003", DAY_2, DAY_3));
        advisories.add(published(4L, "11000004", DAY_2));

        assertThat(processor.getPublicationHistory(W40.value()).entries()).extracting(entry -> entry.advisory().id()).containsExactly(2L, 4L, 3L, 1L);
    }

    @Test
    @DisplayName("정정 관계는 양방향 — 새 안내는 correctsAdvisoryId 로 이전 안내를, 이전 안내는 조회 때 만든 correctedByAdvisoryId 로 새 안내를 가리킨다. 사슬도 이어진다")
    void linksCorrectionsBothWays() {
        // 1 → 2 → 3 정정 사슬(정정 시각 = 이전 안내의 철회 시각 = 새 안내의 발행 시각), 4 는 정정 없이 철회됐다.
        Advisory first = retracted(1L, "11000001", DAY_1, DAY_2);
        Advisory second = Advisory.newCorrection(2L, first, "정정 2", "정정 본문 2").publish(DAY_2).retract(DAY_3);
        Advisory third = Advisory.newCorrection(3L, second, "정정 3", "정정 본문 3").publish(DAY_3);
        advisories.addAll(List.of(first, second, third, retracted(4L, "11000002", DAY_1, DAY_2)));

        List<Entry> entries = processor.getPublicationHistory(W40.value()).entries();

        assertThat(entries).extracting(entry -> entry.advisory().id(), entry -> entry.advisory().correctsAdvisoryId(), Entry::correctedByAdvisoryId)
            .containsExactly(tuple(3L, 2L, null), tuple(2L, 1L, 3L), tuple(4L, null, null), tuple(1L, null, 2L));
    }

    @Test
    @DisplayName("이력은 안내마다 오래된 것부터 묶이고(섞여 와도), 이력이 없는 안내는 빈 목록이다. 행정동 이름 · 시군구가 붙고 행정동 행이 없으면 null 이다")
    void groupsHistoriesAndDistricts() {
        advisories.add(published(1L, "11000001", DAY_1));
        advisories.add(published(2L, "99999999", DAY_2));
        advisories.add(published(3L, "11000003", DAY_3));
        histories.add(history(10L, 1L, null, AdvisoryStatus.DRAFT, null));
        histories.add(history(11L, 2L, null, AdvisoryStatus.DRAFT, null));
        histories.add(history(12L, 1L, AdvisoryStatus.DRAFT, AdvisoryStatus.APPROVED, null));
        histories.add(history(13L, 1L, AdvisoryStatus.APPROVED, AdvisoryStatus.PUBLISHED, null));

        List<Entry> entries = processor.getPublicationHistory(W40.value()).entries();

        Entry first = entries.get(2);
        assertThat(first.advisory().id()).isEqualTo(1L);
        assertThat(first.histories()).extracting(AdvisoryHistoryInfo::id).containsExactly(10L, 12L, 13L);
        assertThat(first.districtName()).isEqualTo("11000001동");
        assertThat(first.sigungu()).isEqualTo("서울특별시 송파구");
        assertThat(entries.get(1).histories()).extracting(AdvisoryHistoryInfo::id).containsExactly(11L);
        assertThat(entries.get(1).districtName()).isNull();
        assertThat(entries.get(1).sigungu()).isNull();
        assertThat(entries.get(0).histories()).isEmpty();
    }

    @Test
    @DisplayName("조회 수 — 안내 1(주 IN) · 이력 1(안내 ID IN) · 행정동 1(겹치지 않는 코드 IN). 안내 수만큼 단건으로 읽지 않는다")
    void readsInBulk() {
        advisories.add(published(1L, "11000001", DAY_1));
        advisories.add(retracted(2L, "11000001", DAY_1, DAY_2));
        advisories.add(published(3L, "11000002", DAY_3));

        processor.getPublicationHistory(W40.value());

        verify(repositoryPort, times(1)).findAllPublicByIsoWeekIn(anyCollection());
        ArgumentCaptor<Collection<Long>> ids = captor();
        verify(historyPort, times(1)).findAllByAdvisoryIdIn(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(1L, 2L, 3L);
        ArgumentCaptor<Collection<String>> codes = captor();
        verify(districtPort, times(1)).findAllByCodeIn(codes.capture());
        assertThat(codes.getValue()).containsExactlyInAnyOrder("11000001", "11000002");
    }

    @Test
    @DisplayName("끝 주는 현재 주와 51주 전까지 받는다 — 범위의 첫 주가 창보다 오래돼도 된다")
    void acceptsWindowEdges() {
        assertThat(processor.resolveWeek("2026-W42")).isEqualTo(ReportWeek.parse("2026-W42"));
        assertThat(processor.getPublicationHistory("2025-W43").fromWeek()).isEqualTo(ReportWeek.parse("2025-W40"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-W43", "2025-W42", "2025-W53"})
    @DisplayName("미래 주 · 51주보다 이전 · 그 해에 없는 주는 ADVISORY_009 이고 메시지에 조회 범위를 싣는다")
    void rejectsWeeksOutsideWindow(String week) {
        assertThatThrownBy(() -> processor.getPublicationHistory(week))
            .isInstanceOfSatisfying(AdvisoryException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AdvisoryErrorCode.WEEK_NOT_QUERYABLE))
            .hasMessageContaining("2025-W43 ~ 2026-W42");
    }

    @SuppressWarnings("unchecked")
    private static <T> ArgumentCaptor<Collection<T>> captor() {
        return ArgumentCaptor.forClass(Collection.class);
    }

    private static Advisory published(long id, String code, LocalDateTime publishedAt) {
        return Advisory.newDraft(id, 7L, code, W40, CITATION, "제목 " + id, "본문 " + id).approve().publish(publishedAt);
    }

    private static Advisory retracted(long id, String code, LocalDateTime publishedAt, LocalDateTime retractedAt) {
        return published(id, code, publishedAt).retract(retractedAt);
    }

    private static AdvisoryHistory history(long id, long advisoryId, AdvisoryStatus from, AdvisoryStatus to, String memo) {
        return new AdvisoryHistory(id, advisoryId, from, to, "제목", "본문", 9001L, null, null, memo, DAY_1);
    }
}
