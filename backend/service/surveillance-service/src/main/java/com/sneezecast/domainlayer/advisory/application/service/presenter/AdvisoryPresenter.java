package com.sneezecast.domainlayer.advisory.application.service.presenter;

import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.AdvisoryCitationItem;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.AdvisoryCitedGroupItem;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.AdvisoryCorrectionItem;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.AdvisoryRetractionItem;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.PublishedAdvisoryItem;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.RegionAdvisoryResponse;
import com.sneezecast.domainlayer.advisory.application.info.RegionAdvisoryInfo;
import com.sneezecast.domainlayer.advisory.application.info.RegionAdvisoryInfo.CitedGroup;
import com.sneezecast.domainlayer.advisory.application.info.RegionAdvisoryInfo.Published;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * 공개 동네 안내 Info → 응답 DTO. 값을 다시 계산하지 않는다 — 비율은 Processor 가 끝냈고, 여기서는 enum 을 metadata(coding-conventions §7)로, 시각을
 * 초 단위 UTC {@link Instant}(§2-2)로 바꾸기만 한다.
 *
 * <p>{@code published_at} · {@code retracted_at} 은 Auditing 과 같은 기준(JVM 기본 시간대의 지역 시각)으로 쓰인다 — 같은 기준으로 읽어 순간으로
 * 되돌린다 ({@code AggregatePresenter} 와 같다).
 */
@Component
public class AdvisoryPresenter {

    private final ZoneId storageZone;

    public AdvisoryPresenter() {
        this(ZoneId.systemDefault());
    }

    /** 테스트가 JVM 시간대와 무관하게 변환을 고정할 때 쓴다. */
    AdvisoryPresenter(ZoneId storageZone) {
        this.storageZone = storageZone;
    }

    public RegionAdvisoryResponse toResponse(RegionAdvisoryInfo info) {
        return RegionAdvisoryResponse.builder()
            .districtCode(info.districtCode())
            .isoWeek(info.isoWeek().value())
            .advisory(info.published() == null ? null : toPublishedItem(info.published()))
            .retraction(info.retractedAt() == null ? null : new AdvisoryRetractionItem(toInstant(info.retractedAt())))
            .build();
    }

    private PublishedAdvisoryItem toPublishedItem(Published published) {
        return PublishedAdvisoryItem.builder()
            .title(published.title())
            .body(published.body())
            .publishedAt(toInstant(published.publishedAt()))
            .citation(AdvisoryCitationItem.builder()
                .level(published.level().toMetadata())
                .participantCount(published.participantCount())
                .symptomaticCount(published.symptomaticCount())
                .symptomRate(published.symptomRate())
                .groups(published.groups().stream().map(AdvisoryPresenter::toGroupItem).toList())
                .build())
            .corrections(published.correctedAts().stream().map(correctedAt -> new AdvisoryCorrectionItem(toInstant(correctedAt))).toList())
            .build();
    }

    private static AdvisoryCitedGroupItem toGroupItem(CitedGroup group) {
        return AdvisoryCitedGroupItem.builder()
            .group(group.group().toMetadata())
            .count(group.count())
            .rate(group.rate())
            .build();
    }

    private Instant toInstant(LocalDateTime storedAt) {
        return storedAt.atZone(storageZone).toInstant().truncatedTo(ChronoUnit.SECONDS);
    }
}
