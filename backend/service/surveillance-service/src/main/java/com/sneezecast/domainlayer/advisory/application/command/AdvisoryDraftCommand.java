package com.sneezecast.domainlayer.advisory.application.command;

import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.util.Objects;

/**
 * 초안 생성 명령. 근거 집계는 동네 · 주로 찾는다 — 검토 후보(#266)가 동네 · 주 단위라 집계 ID 를 화면에 내리지 않아도 된다. 인용 수치는 받지 않는다
 * (서버가 마감된 집계에서 복사한다).
 *
 * @param districtCode 대상 행정동 (형식은 웹 경계가 본다)
 * @param isoWeek      근거 집계 주
 * @param title        제목
 * @param body         본문
 */
public record AdvisoryDraftCommand(
    String districtCode,
    ReportWeek isoWeek,
    String title,
    String body
) {

    public AdvisoryDraftCommand {
        Objects.requireNonNull(districtCode, "districtCode");
        Objects.requireNonNull(isoWeek, "isoWeek");
    }
}
