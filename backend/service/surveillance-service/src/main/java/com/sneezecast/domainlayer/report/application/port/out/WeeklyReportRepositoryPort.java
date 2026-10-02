package com.sneezecast.domainlayer.report.application.port.out;

import com.sneezecast.domainlayer.report.application.exception.ReportErrorCode;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import com.sneezecast.domainlayer.report.domain.model.WeeklyReport;
import java.util.Optional;
import java.util.Set;

/**
 * 주간 보고 저장 포트 (entity-design §2-1). 같은 사람의 같은 주는 한 행이고, 제출은 "없으면 insert, 있으면 update" 다 — 네이티브 upsert 는
 * 컨벤션상 쓰지 않는다 (coding-conventions §8-4).
 *
 * <p><b>동시 제출 계약</b>: 같은 키 · 같은 주 첫 보고가 동시에 들어오면 둘 다 "없음" 을 보고 insert 하고, 한쪽은 unique 위반으로
 * {@link #insert} 에서 {@link ReportErrorCode#CONCURRENT_SUBMISSION} 을 받는다. 그 트랜잭션은 rollback-only 이고 Hibernate 세션도 쓸 수
 * 없으므로 그 안에서 다시 시도하지 않는다. <b>트랜잭션 밖의 WebFacade 가 이 예외를 잡아 새 트랜잭션으로 한 번 다시 부르고</b>, 두 번째는
 * 행이 있으니 {@link #updateCurrent} 경로를 탄다. 두 번째도 지면 예외를 그대로 내보낸다 (409).
 *
 * <p>보고 주는 호출자가 {@code ReportWeekCalculator} 로 정한 현재 주다. 지난 주 거절 · 행정동 검증은 Processor 의 몫이다.
 */
public interface WeeklyReportRepositoryPort {

    Optional<WeeklyReport> findByReporterKeyAndIsoWeek(String reporterKey, ReportWeek isoWeek);

    /**
     * 이번 주 첫 보고를 저장한다. {@code id} 는 호출자(Processor)가 Snowflake 로 정해서 넘기고, 수정 횟수 0 인 새 보고여야 한다
     * ({@link WeeklyReport#newReport}).
     *
     * <p>INSERT 를 바로 내보내(flush) unique 위반을 이 호출에서 드러낸다. {@code uk_weekly_report_reporter_key_iso_week} 위반만
     * {@code ReportException(CONCURRENT_SUBMISSION)} 으로 바꾸고, 다른 무결성 위반(PK 충돌 · NOT NULL 등)은 그대로 던진다 — 동시 제출로
     * 오인해 재시도하지 않게.
     *
     * @return 감사 시각이 채워진 저장 결과
     */
    WeeklyReport insert(WeeklyReport report);

    /**
     * 같은 주 보고의 행정동 · 증상군을 마지막 요청 값으로 바꾸고 수정 횟수를 1 올린다. 증가는 쿼리 안에서 하므로 동시 수정에서도 사라지지 않는다.
     * 수정 횟수는 SMALLINT 상한(32767)에서 멈춘다 — 그 뒤 수정도 실패하지 않는다.
     *
     * <p><b>호출자 트랜잭션이 있어야 한다</b> (없으면 {@code IllegalTransactionStateException}). 갱신 뒤 영속성 컨텍스트를 비우므로, 같은
     * 트랜잭션에서 앞서 읽어 둔 엔티티는 분리된다 — 도메인 모델로 옮겨 둔 값은 영향이 없다.
     *
     * @return 갱신 뒤 다시 읽은 보고. 행이 없으면(그 사이 취소됨) 빈 값
     */
    Optional<WeeklyReport> updateCurrent(String reporterKey, ReportWeek isoWeek, String districtCode, Set<SymptomGroup> symptoms);

    /**
     * 같은 주 보고를 지운다 (보고 취소). 지울 행이 없어도 실패하지 않는다 — 취소는 멱등이다. <b>호출자 트랜잭션이 있어야 한다.</b>
     *
     * @return 지운 행 수 (0 또는 1)
     */
    int deleteByReporterKeyAndIsoWeek(String reporterKey, ReportWeek isoWeek);
}
