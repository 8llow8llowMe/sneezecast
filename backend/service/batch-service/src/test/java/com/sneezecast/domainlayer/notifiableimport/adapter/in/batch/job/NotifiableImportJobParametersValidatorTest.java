package com.sneezecast.domainlayer.notifiableimport.adapter.in.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;

class NotifiableImportJobParametersValidatorTest {

    private static final String RUN_AT = "2026-10-06T05:00:00";

    private final NotifiableImportJobParametersValidator validator = new NotifiableImportJobParametersValidator();

    @Test
    @DisplayName("runAt 이 없으면 잡을 시작하지 않는다")
    void rejectsMissingRunAt() {
        assertThatThrownBy(() -> validator.validate(new JobParametersBuilder().addString("year", "2026").toJobParameters()))
            .isInstanceOf(JobParametersInvalidException.class)
            .hasMessageContaining("'runAt'");
        assertThatThrownBy(() -> validator.validate(new JobParametersBuilder().addString("runAt", " ").toJobParameters()))
            .isInstanceOf(JobParametersInvalidException.class);
        assertThatThrownBy(() -> validator.validate(new JobParameters())).isInstanceOf(JobParametersInvalidException.class);
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(JobParametersInvalidException.class);
    }

    @ParameterizedTest(name = "runAt={0}")
    @ValueSource(strings = {"2026-10-06", "2026-10-06 05:00:00", "2026-10-06T05:00:00+09:00", "2026-13-01T05:00:00", "now"})
    @DisplayName("runAt 이 ISO 로컬 날짜 · 시각이 아니면 거절한다 — 날짜만 · 공백 구분 · 오프셋 포함")
    void rejectsMalformedRunAt(String runAt) {
        assertThatThrownBy(() -> validator.validate(parameters(runAt, null)))
            .isInstanceOf(JobParametersInvalidException.class)
            .hasMessageContaining("'runAt'");
    }

    @ParameterizedTest(name = "runAt={0}")
    @ValueSource(strings = {RUN_AT, "2026-10-06T05:00", "2026-10-06T05:00:00.123"})
    @DisplayName("스케줄 발화 형식(초까지)과 초 생략 · 소수 초를 받고, year 가 없으면 runAt 의 연도가 올해다")
    void acceptsIsoRunAt(String runAt) {
        assertThatCode(() -> validator.validate(parameters(runAt, null))).doesNotThrowAnyException();

        LocalDateTime parsed = NotifiableImportJobParametersValidator.runAt(runAt);
        assertThat(parsed.toLocalDate()).hasToString("2026-10-06");
        assertThat(NotifiableImportJobParametersValidator.currentYear(null, parsed)).isEqualTo(2026);
    }

    @ParameterizedTest(name = "year={0}")
    @ValueSource(strings = {"", "26", "20255", "2025.0", "two", "-2025"})
    @DisplayName("year 를 주면 4자리 정수여야 한다")
    void rejectsMalformedYear(String year) {
        assertThatThrownBy(() -> validator.validate(parameters(RUN_AT, year)))
            .isInstanceOf(JobParametersInvalidException.class)
            .hasMessageContaining("'year'");
    }

    @ParameterizedTest(name = "year={0}")
    @ValueSource(strings = {"1999", "2027"})
    @DisplayName("year 가 2000 ~ runAt 의 연도 밖이면 거절한다 — 미래 연도를 올해로 볼 수 없다")
    void rejectsYearOutOfRange(String year) {
        assertThatThrownBy(() -> validator.validate(parameters(RUN_AT, year)))
            .isInstanceOf(JobParametersInvalidException.class)
            .hasMessageContaining("between 2000 and 2026");
    }

    @Test
    @DisplayName("year 가 없을 때도 runAt 의 연도가 2000 미만이면 거절한다")
    void rejectsRunAtBeforeMinYear() {
        assertThatThrownBy(() -> validator.validate(parameters("1999-12-31T05:00:00", null)))
            .isInstanceOf(JobParametersInvalidException.class)
            .hasMessageContaining("between 2000 and 1999");
    }

    @ParameterizedTest(name = "year={0}")
    @ValueSource(strings = {"2000", "2025", "2026", " 2025 "})
    @DisplayName("2000 ~ runAt 의 연도는 통과하고, tasklet 은 같은 규칙으로 그 값을 올해로 읽는다 (백필)")
    void acceptsYearInRange(String year) {
        assertThatCode(() -> validator.validate(parameters(RUN_AT, year))).doesNotThrowAnyException();

        assertThat(NotifiableImportJobParametersValidator.currentYear(year, NotifiableImportJobParametersValidator.runAt(RUN_AT)))
            .isEqualTo(Integer.parseInt(year.trim()));
    }

    @Test
    @DisplayName("숫자 타입으로 들어온 year 도 받는다")
    void acceptsLongYear() {
        JobParameters parameters = new JobParametersBuilder().addString("runAt", RUN_AT).addLong("year", 2025L).toJobParameters();

        assertThatCode(() -> validator.validate(parameters)).doesNotThrowAnyException();
        assertThat(NotifiableImportJobParametersValidator.currentYear(2025L, LocalDateTime.parse(RUN_AT))).isEqualTo(2025);
    }

    private static JobParameters parameters(String runAt, String year) {
        JobParametersBuilder builder = new JobParametersBuilder().addString("runAt", runAt);
        if (year != null) {
            builder.addString("year", year);
        }
        return builder.toJobParameters();
    }
}
