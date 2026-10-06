package com.sneezecast.domainlayer.report.adapter.out.persistence;

import static com.sneezecast.domainlayer.report.domain.enums.SymptomGroup.ENTERIC;
import static com.sneezecast.domainlayer.report.domain.enums.SymptomGroup.RESPIRATORY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.SurveillanceH2TestSupport;
import com.sneezecast.domainlayer.report.adapter.out.persistence.entity.WeeklyReportEntity;
import com.sneezecast.domainlayer.report.application.exception.ReportErrorCode;
import com.sneezecast.domainlayer.report.application.exception.ReportException;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import com.sneezecast.domainlayer.report.domain.model.WeeklyReport;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보고 저장 어댑터를 실제 스키마(H2 MySQL 모드)에 돌려 본다 — 갱신 · 삭제 JPQL 은 컴파일로 검증되지 않고, 동시 제출 판정은 DB 제약 이름에 기댄다.
 * 테스트 메서드에는 트랜잭션을 걸지 않는다. 운영처럼 어댑터 호출마다 커밋돼야 unique 위반 · 트랜잭션 필수 규칙이 그대로 드러난다.
 */
@ExtendWith(OutputCaptureExtension.class)
class WeeklyReportRepositoryAdapterTest extends SurveillanceH2TestSupport {

    private static final String KEY_A = "a".repeat(64);
    private static final String KEY_B = "b".repeat(64);
    private static final ReportWeek W40 = ReportWeek.parse("2026-W40");
    private static final ReportWeek W41 = ReportWeek.parse("2026-W41");
    private static final String YEOKSAM_1 = "11230510";
    private static final String GARAK_1 = "11240660";
    private static final LocalDateTime OLD_UPDATED_AT = LocalDateTime.of(2000, 1, 1, 0, 0);

    @Autowired
    private WeeklyReportRepositoryAdapter adapter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM weekly_report");
        tx = new TransactionTemplate(transactionManager);
    }

    @Test
    @DisplayName("새 보고를 저장하면 감사 시각이 채워지고, 같은 키 · 주로 다시 읽힌다")
    void insertAndFind() {
        WeeklyReport saved = adapter.insert(WeeklyReport.newReport(1L, KEY_A, W40, YEOKSAM_1, Set.of(ENTERIC, RESPIRATORY)));

        assertThat(saved.createdAt()).isNotNull();
        assertThat(saved.updatedAt()).isNotNull();
        WeeklyReport found = adapter.findByReporterKeyAndIsoWeek(KEY_A, W40).orElseThrow();
        assertThat(found.id()).isEqualTo(1L);
        assertThat(found.isoWeek()).isEqualTo(W40);
        assertThat(found.districtCode()).isEqualTo(YEOKSAM_1);
        assertThat(found.symptoms()).containsExactly(RESPIRATORY, ENTERIC);
        assertThat(found.revisionCount()).isZero();
        assertThat(number(row(KEY_A, W40), "SYMPTOM_MASK")).isEqualTo(3);
        assertThat(row(KEY_A, W40)).containsEntry("ISO_WEEK", "2026-W40");
    }

    @Test
    @DisplayName("증상 없음 보고는 마스크 0 으로 저장되고 빈 집합으로 읽힌다 — 분모가 되는 정상 보고다")
    void noSymptomReport() {
        adapter.insert(WeeklyReport.newReport(1L, KEY_A, W40, YEOKSAM_1, Set.of()));

        WeeklyReport found = adapter.findByReporterKeyAndIsoWeek(KEY_A, W40).orElseThrow();
        assertThat(found.symptoms()).isEmpty();
        assertThat(found.noSymptom()).isTrue();
        assertThat(number(row(KEY_A, W40), "SYMPTOM_MASK")).isZero();
    }

    @Test
    @DisplayName("다른 키 · 다른 주는 찾지 않는다")
    void findIsScopedToKeyAndWeek() {
        adapter.insert(WeeklyReport.newReport(1L, KEY_A, W40, YEOKSAM_1, Set.of()));

        assertThat(adapter.findByReporterKeyAndIsoWeek(KEY_A, W41)).isEmpty();
        assertThat(adapter.findByReporterKeyAndIsoWeek(KEY_B, W40)).isEmpty();
    }

    @Test
    @DisplayName("같은 키 · 같은 주 두 번째 insert 는 REPORT_001 동시 제출 예외다 — 원인 예외도, Hibernate SQL 오류 로그도 가명 키를 싣지 않는다")
    void secondInsertForSameWeekIsConcurrentSubmission(CapturedOutput output) {
        adapter.insert(WeeklyReport.newReport(1L, KEY_A, W40, YEOKSAM_1, Set.of()));

        assertThatThrownBy(() -> adapter.insert(WeeklyReport.newReport(2L, KEY_A, W40, GARAK_1, Set.of(RESPIRATORY))))
            .isInstanceOfSatisfying(ReportException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(ReportErrorCode.CONCURRENT_SUBMISSION);
                assertThat(e.getCause()).isNull();
            });
        // DB 메시지(Duplicate entry / Unique index ... VALUES ('<키>', '<주>'))가 어떤 로그로도 나가지 않는다.
        assertThat(output.getAll()).doesNotContain(KEY_A);
        // 이긴 쪽 행만 남는다.
        assertThat(adapter.findByReporterKeyAndIsoWeek(KEY_A, W40).orElseThrow().districtCode()).isEqualTo(YEOKSAM_1);
    }

    @Test
    @DisplayName("같은 키라도 주가 다르면 · 같은 주라도 키가 다르면 따로 저장된다")
    void differentWeekOrKeyIsSeparateRow() {
        adapter.insert(WeeklyReport.newReport(1L, KEY_A, W40, YEOKSAM_1, Set.of()));
        adapter.insert(WeeklyReport.newReport(2L, KEY_A, W41, YEOKSAM_1, Set.of()));
        adapter.insert(WeeklyReport.newReport(3L, KEY_B, W40, YEOKSAM_1, Set.of()));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM weekly_report", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("다른 무결성 위반(PK 충돌)은 동시 제출로 바꾸지 않고 그대로 던진다 — 재시도 대상으로 오인하지 않게")
    void otherIntegrityViolationIsNotTranslated() {
        adapter.insert(WeeklyReport.newReport(1L, KEY_A, W40, YEOKSAM_1, Set.of()));

        assertThatThrownBy(() -> adapter.insert(WeeklyReport.newReport(1L, KEY_B, W41, GARAK_1, Set.of())))
            .isInstanceOf(DataIntegrityViolationException.class)
            .isNotInstanceOf(ReportException.class);
        // merge 로 기존 행을 덮어쓰지 않았다 (Persistable).
        assertThat(adapter.findByReporterKeyAndIsoWeek(KEY_A, W40)).isPresent();
        assertThat(adapter.findByReporterKeyAndIsoWeek(KEY_B, W41)).isEmpty();
    }

    @Test
    @DisplayName("수정은 행정동 · 증상군을 바꾸고 수정 횟수를 1씩 올리며 updated_at 을 갱신한다 — created_at 은 그대로")
    void updateCurrentChangesValuesAndIncrementsRevision() {
        adapter.insert(WeeklyReport.newReport(1L, KEY_A, W40, YEOKSAM_1, Set.of()));
        Timestamp createdAt = (Timestamp) row(KEY_A, W40).get("CREATED_AT");
        backdateUpdatedAt();

        Optional<WeeklyReport> first = update(KEY_A, W40, GARAK_1, Set.of(RESPIRATORY));
        Optional<WeeklyReport> second = update(KEY_A, W40, GARAK_1, Set.of(RESPIRATORY, ENTERIC));

        assertThat(first).get().satisfies(report -> {
            assertThat(report.districtCode()).isEqualTo(GARAK_1);
            assertThat(report.symptoms()).containsExactly(RESPIRATORY);
            assertThat(report.revisionCount()).isEqualTo(1);
            assertThat(report.updatedAt()).isAfter(OLD_UPDATED_AT);
        });
        assertThat(second).get().satisfies(report -> {
            assertThat(report.id()).isEqualTo(1L);
            assertThat(report.symptoms()).containsExactly(RESPIRATORY, ENTERIC);
            assertThat(report.revisionCount()).isEqualTo(2);
        });
        Map<String, Object> row = row(KEY_A, W40);
        assertThat(number(row, "REVISION_COUNT")).isEqualTo(2);
        assertThat(number(row, "SYMPTOM_MASK")).isEqualTo(3);
        assertThat(row).containsEntry("CREATED_AT", createdAt);
        assertThat(((Timestamp) row.get("UPDATED_AT")).toLocalDateTime()).isAfter(OLD_UPDATED_AT);
    }

    @Test
    @DisplayName("수정은 다른 주 · 다른 키의 행을 건드리지 않는다")
    void updateCurrentDoesNotTouchOtherRows() {
        adapter.insert(WeeklyReport.newReport(1L, KEY_A, W40, YEOKSAM_1, Set.of()));
        adapter.insert(WeeklyReport.newReport(2L, KEY_A, W41, YEOKSAM_1, Set.of()));
        adapter.insert(WeeklyReport.newReport(3L, KEY_B, W40, YEOKSAM_1, Set.of()));
        backdateUpdatedAt();

        update(KEY_A, W40, GARAK_1, Set.of(ENTERIC));

        for (Map<String, Object> untouched : List.of(row(KEY_A, W41), row(KEY_B, W40))) {
            assertThat(untouched).containsEntry("DISTRICT_CODE", YEOKSAM_1);
            assertThat(number(untouched, "SYMPTOM_MASK")).isZero();
            assertThat(number(untouched, "REVISION_COUNT")).isZero();
            assertThat(((Timestamp) untouched.get("UPDATED_AT")).toLocalDateTime()).isEqualTo(OLD_UPDATED_AT);
        }
    }

    @Test
    @DisplayName("고칠 행이 없으면(그 사이 취소됨) 빈 값이다")
    void updateCurrentWithoutRowIsEmpty() {
        assertThat(update(KEY_A, W40, GARAK_1, Set.of())).isEmpty();
    }

    @Test
    @DisplayName("동시 수정에서도 수정 횟수 증가가 사라지지 않는다 — 읽어서 더하지 않고 쿼리 안에서 올린다")
    void concurrentUpdatesKeepEveryIncrement() throws Exception {
        adapter.insert(WeeklyReport.newReport(1L, KEY_A, W40, YEOKSAM_1, Set.of()));
        int threads = 4;
        int updatesPerThread = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    for (int j = 0; j < updatesPerThread; j++) {
                        update(KEY_A, W40, GARAK_1, Set.of(RESPIRATORY));
                    }
                    return null;
                });
            }
            for (Future<Void> future : executor.invokeAll(tasks, 30, TimeUnit.SECONDS)) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(adapter.findByReporterKeyAndIsoWeek(KEY_A, W40).orElseThrow().revisionCount()).isEqualTo(threads * updatesPerThread);
    }

    @Test
    @DisplayName("수정 횟수는 SMALLINT 상한(32767)에서 멈춘다 — 상한에서 고쳐도 범위 초과 없이 값만 바뀐다")
    void revisionCountSaturatesAtSmallintMax() {
        adapter.insert(WeeklyReport.newReport(1L, KEY_A, W40, YEOKSAM_1, Set.of()));
        jdbcTemplate.update("UPDATE weekly_report SET revision_count = ?", WeeklyReportEntity.MAX_REVISION_COUNT - 1);

        assertThat(update(KEY_A, W40, GARAK_1, Set.of())).get().extracting(WeeklyReport::revisionCount).isEqualTo(32767);
        Optional<WeeklyReport> saturated = update(KEY_A, W40, YEOKSAM_1, Set.of(ENTERIC));

        assertThat(saturated).get().satisfies(report -> {
            assertThat(report.revisionCount()).isEqualTo(32767);
            assertThat(report.districtCode()).isEqualTo(YEOKSAM_1);
            assertThat(report.symptoms()).containsExactly(ENTERIC);
        });
        assertThat(number(row(KEY_A, W40), "REVISION_COUNT")).isEqualTo(32767);
    }

    @Test
    @DisplayName("트랜잭션 밖에서 수정 · 삭제를 부르면 거부한다 — 갱신과 재조회가 따로 커밋되지 않게")
    void updateAndDeleteRequireTransaction() {
        adapter.insert(WeeklyReport.newReport(1L, KEY_A, W40, YEOKSAM_1, Set.of()));

        assertThatThrownBy(() -> adapter.updateCurrent(KEY_A, W40, GARAK_1, Set.of(RESPIRATORY)))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> adapter.deleteByReporterKeyAndIsoWeek(KEY_A, W40))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> adapter.deleteAllByReporterKey(KEY_A))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(number(row(KEY_A, W40), "REVISION_COUNT")).isZero();
    }

    @Test
    @DisplayName("파기는 그 키의 모든 주 행을 지우고 다른 키 행은 남긴다 — 지운 건수를 주고, 다시 부르면 0 (멱등)")
    void deleteAllRemovesEveryWeekOfKey() {
        ReportWeek w01 = ReportWeek.parse("2026-W01");
        adapter.insert(WeeklyReport.newReport(1L, KEY_A, w01, YEOKSAM_1, Set.of()));
        adapter.insert(WeeklyReport.newReport(2L, KEY_A, W40, YEOKSAM_1, Set.of(RESPIRATORY)));
        adapter.insert(WeeklyReport.newReport(3L, KEY_A, W41, GARAK_1, Set.of(ENTERIC)));
        adapter.insert(WeeklyReport.newReport(4L, KEY_B, W40, YEOKSAM_1, Set.of()));
        adapter.insert(WeeklyReport.newReport(5L, KEY_B, W41, YEOKSAM_1, Set.of()));

        assertThat(deleteAll(KEY_A)).isEqualTo(3);
        assertThat(deleteAll(KEY_A)).isZero();

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM weekly_report WHERE reporter_key = ?", Integer.class, KEY_A)).isZero();
        assertThat(adapter.findByReporterKeyAndIsoWeek(KEY_B, W40)).isPresent();
        assertThat(adapter.findByReporterKeyAndIsoWeek(KEY_B, W41)).isPresent();
    }

    @Test
    @DisplayName("행이 하나도 없는 키를 파기해도 실패하지 않고 0 이다")
    void deleteAllWithoutRowsIsZero() {
        adapter.insert(WeeklyReport.newReport(1L, KEY_B, W40, YEOKSAM_1, Set.of()));

        assertThat(deleteAll(KEY_A)).isZero();
        assertThat(adapter.findByReporterKeyAndIsoWeek(KEY_B, W40)).isPresent();
    }

    @Test
    @DisplayName("삭제는 그 키 · 주 행만 지우고 지운 건수를 준다 — 없으면 0 (멱등 취소)")
    void deleteReturnsDeletedCount() {
        adapter.insert(WeeklyReport.newReport(1L, KEY_A, W40, YEOKSAM_1, Set.of()));
        adapter.insert(WeeklyReport.newReport(2L, KEY_A, W41, YEOKSAM_1, Set.of()));
        adapter.insert(WeeklyReport.newReport(3L, KEY_B, W40, YEOKSAM_1, Set.of()));

        assertThat(delete(KEY_A, W40)).isEqualTo(1);
        assertThat(delete(KEY_A, W40)).isZero();

        assertThat(adapter.findByReporterKeyAndIsoWeek(KEY_A, W40)).isEmpty();
        assertThat(adapter.findByReporterKeyAndIsoWeek(KEY_A, W41)).isPresent();
        assertThat(adapter.findByReporterKeyAndIsoWeek(KEY_B, W40)).isPresent();
    }

    @Test
    @DisplayName("취소한 주에는 다시 첫 보고를 저장할 수 있다")
    void insertAfterDelete() {
        adapter.insert(WeeklyReport.newReport(1L, KEY_A, W40, YEOKSAM_1, Set.of()));
        delete(KEY_A, W40);

        adapter.insert(WeeklyReport.newReport(2L, KEY_A, W40, GARAK_1, Set.of(SymptomGroup.ENTERIC)));

        assertThat(adapter.findByReporterKeyAndIsoWeek(KEY_A, W40).orElseThrow().id()).isEqualTo(2L);
    }

    private Optional<WeeklyReport> update(String reporterKey, ReportWeek isoWeek, String districtCode, Set<SymptomGroup> symptoms) {
        return tx.execute(status -> adapter.updateCurrent(reporterKey, isoWeek, districtCode, symptoms));
    }

    private int delete(String reporterKey, ReportWeek isoWeek) {
        Integer deleted = tx.execute(status -> adapter.deleteByReporterKeyAndIsoWeek(reporterKey, isoWeek));
        return deleted == null ? 0 : deleted;
    }

    private int deleteAll(String reporterKey) {
        Integer deleted = tx.execute(status -> adapter.deleteAllByReporterKey(reporterKey));
        return deleted == null ? 0 : deleted;
    }

    // H2 는 TINYINT · SMALLINT 를 드라이버 버전에 따라 Byte · Short · Integer 로 줄 수 있어 숫자로만 비교한다.
    private static int number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).intValue();
    }

    private void backdateUpdatedAt() {
        jdbcTemplate.update("UPDATE weekly_report SET updated_at = ?", Timestamp.valueOf(OLD_UPDATED_AT));
    }

    private Map<String, Object> row(String reporterKey, ReportWeek isoWeek) {
        return jdbcTemplate.queryForMap("SELECT * FROM weekly_report WHERE reporter_key = ? AND iso_week = ?", reporterKey, isoWeek.value());
    }
}
