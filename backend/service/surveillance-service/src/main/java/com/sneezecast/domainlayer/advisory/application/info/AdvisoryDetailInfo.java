package com.sneezecast.domainlayer.advisory.application.info;

import java.util.List;
import java.util.Objects;

/**
 * 안내 한 건과 그 전 이력 (오래된 것부터).
 */
public record AdvisoryDetailInfo(
    AdvisoryInfo advisory,
    List<AdvisoryHistoryInfo> histories
) {

    public AdvisoryDetailInfo {
        Objects.requireNonNull(advisory, "advisory");
        histories = List.copyOf(histories);
    }
}
