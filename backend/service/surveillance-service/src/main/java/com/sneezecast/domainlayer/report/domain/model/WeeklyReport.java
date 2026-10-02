package com.sneezecast.domainlayer.report.domain.model;

import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.Builder;

/**
 * 주간 건강 보고 한 건 (entity-design §2-1). 같은 사람({@code reporterKey})의 같은 주는 한 건이고, 같은 주 수정은 이 행을 고친다.
 *
 * <p><b>회원 아이디를 갖지 않는다.</b> 보고자는 가명 키로만 식별한다 (architecture-guide §6).
 *
 * @param id            Snowflake. 새 보고는 Processor 가 정해서 넘긴다
 * @param reporterKey   가명 보고자 키 (64자 소문자 hex, {@code ReporterKeyGenerator})
 * @param isoWeek       보고 주. 서버가 요청 시각(KST)으로 정한다
 * @param districtCode  보고 행정동 코드. 같은 주 수정이면 마지막 요청 값이다
 * @param symptoms      증상군. 빈 집합 = 증상 없음. 선언 순서로 도는 읽기 전용 집합으로 맞춘다
 * @param revisionCount 같은 주 수정 횟수 (새 보고는 0)
 * @param createdAt     처음 저장한 시각. 저장 전이면 null
 * @param updatedAt     마지막으로 고친 시각. 저장 전이면 null
 */
@Builder
public record WeeklyReport(
    long id,
    String reporterKey,
    ReportWeek isoWeek,
    String districtCode,
    Set<SymptomGroup> symptoms,
    int revisionCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

    public WeeklyReport {
        // 마스크로 한 번 돌려 null 원소를 거부하고, 순서 · 불변성을 저장 값과 같게 맞춘다.
        symptoms = SymptomGroup.fromMask(SymptomGroup.toMask(symptoms));
    }

    /** 아직 저장하지 않은 이번 주 첫 보고. 수정 횟수는 0 이고 시각은 저장할 때 Auditing 이 채운다. */
    public static WeeklyReport newReport(long id, String reporterKey, ReportWeek isoWeek, String districtCode, Set<SymptomGroup> symptoms) {
        return new WeeklyReport(id, reporterKey, isoWeek, districtCode, symptoms, 0, null, null);
    }

    /** 증상 없음 보고인지. 이 보고도 그 주 참여자(분모)에 들어간다. */
    public boolean noSymptom() {
        return symptoms.isEmpty();
    }

    /**
     * 증상(민감정보)과 가명 키를 싣지 않는다. record 기본 {@code toString} 은 모든 필드를 내보내 로그 한 줄로 증상 · 키가 함께 남는다.
     */
    @Override
    public String toString() {
        return "WeeklyReport[id=" + id + ", isoWeek=" + (isoWeek == null ? null : isoWeek.value()) + "]";
    }
}
