package com.sneezecast.domainlayer.aggregate.application.service.presenter;

import com.sneezecast.common.dto.metadata.CodeNameDescriptionMetadata;
import com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.item.DistrictWeeklyAggregateItem;
import com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.item.SymptomGroupAggregateItem;
import com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.response.DistrictWeeklyAggregateListResponse;
import com.sneezecast.domainlayer.aggregate.adapter.in.web.dto.response.DistrictWeeklyAggregateResponse;
import com.sneezecast.domainlayer.aggregate.application.info.AggregateWeekInfo;
import com.sneezecast.domainlayer.aggregate.application.info.DistrictWeeklyAggregateInfo;
import com.sneezecast.domainlayer.aggregate.application.info.DistrictWeeklyAggregateInfo.GroupRate;
import com.sneezecast.domainlayer.aggregate.application.info.DistrictWeeklyAggregateInfo.Measured;
import com.sneezecast.domainlayer.aggregate.domain.enums.GroupTrend;
import com.sneezecast.domainlayer.aggregate.domain.enums.InsufficientReason;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * 공개 집계 Info → 응답 DTO. 값을 다시 계산하지 않는다 — 비율 · 변화는 Processor 가 끝냈고, 여기서는 enum 을 metadata(coding-conventions §7)로,
 * 시각을 초 단위 UTC {@link Instant}(§2-2)로 바꾸기만 한다.
 *
 * <p>자료 부족이면 Info 에 수치가 없으므로 응답 항목도 null 로 남고 {@code @JsonInclude(NON_NULL)} 로 빠진다.
 *
 * <p>{@code calculated_at} 은 Auditing 과 같은 기준(JVM 기본 시간대의 지역 시각)으로 쓰인다 — 같은 기준으로 읽어 순간으로 되돌린다
 * ({@code ReportPresenter} 와 같다).
 */
@Component
public class AggregatePresenter {

    private final ZoneId storageZone;

    public AggregatePresenter() {
        this(ZoneId.systemDefault());
    }

    /** 테스트가 JVM 시간대와 무관하게 변환을 고정할 때 쓴다. */
    AggregatePresenter(ZoneId storageZone) {
        this.storageZone = storageZone;
    }

    /** 동네 하나. Processor 가 늘 한 건을 담는다. */
    public DistrictWeeklyAggregateResponse toResponse(AggregateWeekInfo week) {
        return DistrictWeeklyAggregateResponse.builder()
            .isoWeek(week.isoWeek().value())
            .finalized(week.finalized())
            .publicThreshold(week.publicThreshold())
            .slightDeltaPp(week.slightDeltaPp())
            .highDeltaPp(week.highDeltaPp())
            .district(toItem(week.districts().getFirst()))
            .build();
    }

    public DistrictWeeklyAggregateListResponse toListResponse(AggregateWeekInfo week) {
        return DistrictWeeklyAggregateListResponse.builder()
            .isoWeek(week.isoWeek().value())
            .finalized(week.finalized())
            .publicThreshold(week.publicThreshold())
            .slightDeltaPp(week.slightDeltaPp())
            .highDeltaPp(week.highDeltaPp())
            .districts(week.districts().stream().map(this::toItem).toList())
            .build();
    }

    private DistrictWeeklyAggregateItem toItem(DistrictWeeklyAggregateInfo district) {
        DistrictWeeklyAggregateItem.DistrictWeeklyAggregateItemBuilder item = DistrictWeeklyAggregateItem.builder()
            .code(district.code())
            .name(district.name())
            .sigungu(district.sigungu())
            .active(district.active())
            .level(district.level().toMetadata())
            .insufficientReason(metadata(district.insufficientReason()))
            .participantCount(district.participantCount())
            .calculatedAt(toInstant(district.calculatedAt()));
        Measured measured = district.measured();
        if (measured == null) {
            return item.build();
        }
        return item
            .symptomaticCount(measured.symptomaticCount())
            .symptomRate(measured.symptomRate())
            .baselineRate(measured.baselineRate())
            .groups(measured.groups().stream().map(AggregatePresenter::toGroupItem).toList())
            .build();
    }

    private static SymptomGroupAggregateItem toGroupItem(GroupRate group) {
        GroupTrend trend = group.trend();
        return SymptomGroupAggregateItem.builder()
            .group(group.group().toMetadata())
            .rate(group.rate())
            .trend(trend == null ? null : trend.toMetadata())
            .build();
    }

    private static CodeNameDescriptionMetadata metadata(InsufficientReason reason) {
        return reason == null ? null : reason.toMetadata();
    }

    private Instant toInstant(LocalDateTime storedAt) {
        return storedAt == null ? null : storedAt.atZone(storageZone).toInstant().truncatedTo(ChronoUnit.SECONDS);
    }
}
