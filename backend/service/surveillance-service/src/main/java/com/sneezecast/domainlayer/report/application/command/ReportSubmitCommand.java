package com.sneezecast.domainlayer.report.application.command;

import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import java.util.Collection;
import java.util.Set;

/**
 * 이번 주 보고 제출 명령. 보고 주와 보고자는 담지 않는다 — 주는 서버가 정하고({@code ReportWeekCalculator}), 보고자는 인증 주체에서 가명 키로 바꾼다.
 *
 * @param districtCode  형식 검증을 통과한 행정동 코드 (현행 여부는 Processor 가 본다)
 * @param symptomGroups 증상군. 빈 집합 = 증상 없음. 선언 순서로 도는 읽기 전용 집합으로 맞춘다
 */
public record ReportSubmitCommand(
    String districtCode,
    Set<SymptomGroup> symptomGroups
) {

    public ReportSubmitCommand {
        // 마스크로 한 번 돌려 null 원소를 거부하고 순서 · 불변성을 저장 값과 같게 맞춘다 (WeeklyReport 와 같은 규칙).
        symptomGroups = SymptomGroup.fromMask(SymptomGroup.toMask(symptomGroups));
    }

    public static ReportSubmitCommand of(String districtCode, Collection<SymptomGroup> symptomGroups) {
        return new ReportSubmitCommand(districtCode, Set.copyOf(symptomGroups));
    }

    /** 증상(민감정보)을 로그 · 예외 메시지에 흘리지 않는다. */
    @Override
    public String toString() {
        return "ReportSubmitCommand[districtCode=" + districtCode + ", symptomGroups=****]";
    }
}
