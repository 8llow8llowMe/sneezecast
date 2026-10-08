package com.sneezecast.domainlayer.sentinelimport.adapter.in.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;

class SentinelImportJobParametersValidatorTest {

    private static final String RUN_AT = "2026-10-09T06:00:00";

    private final SentinelImportJobParametersValidator validator = new SentinelImportJobParametersValidator();

    @Test
    @DisplayName("runAt 이 없으면 잡을 시작하지 않는다")
    void rejectsMissingRunAt() {
        assertThatThrownBy(() -> validator.validate(new JobParametersBuilder().addString("baseDate", "2026-10-09").toJobParameters()))
            .isInstanceOf(JobParametersInvalidException.class)
            .hasMessageContaining("'runAt'");
        assertThatThrownBy(() -> validator.validate(new JobParametersBuilder().addString("runAt", " ").toJobParameters()))
            .isInstanceOf(JobParametersInvalidException.class);
        assertThatThrownBy(() -> validator.validate(new JobParameters())).isInstanceOf(JobParametersInvalidException.class);
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(JobParametersInvalidException.class);
    }

    @ParameterizedTest(name = "runAt={0}")
    @ValueSource(strings = {"2026-10-09", "2026-10-09 06:00:00", "2026-10-09T06:00:00+09:00", "2026-13-01T06:00:00", "now",
        "+12026-01-01T00:00:00"})
    @DisplayName("runAt 이 ISO 로컬 날짜 · 시각이 아니면 거절한다 — 날짜만 · 공백 구분 · 오프셋 포함 · 부호 붙은 5자리 연도")
    void rejectsMalformedRunAt(String runAt) {
        assertThatThrownBy(() -> validator.validate(parameters(runAt, null)))
            .isInstanceOf(JobParametersInvalidException.class)
            .hasMessageContaining("'runAt'");
    }

    @ParameterizedTest(name = "runAt={0}")
    @ValueSource(strings = {RUN_AT, "2026-10-09T06:00", "2026-10-09T06:00:00.123"})
    @DisplayName("스케줄 발화 형식(초까지)과 초 생략 · 소수 초를 받고, baseDate 가 없으면 runAt 의 날짜가 기준일이다")
    void acceptsIsoRunAt(String runAt) {
        assertThatCode(() -> validator.validate(parameters(runAt, null))).doesNotThrowAnyException();

        LocalDateTime parsed = SentinelImportJobParametersValidator.runAt(runAt);
        assertThat(SentinelImportJobParametersValidator.baseDate(null, parsed)).isEqualTo(LocalDate.of(2026, 10, 9));
    }

    @ParameterizedTest(name = "baseDate={0}")
    @ValueSource(strings = {"", "2026-9-1", "20260901", "2026-09-01T00:00:00", "2026-02-30", "2026-13-01", "+12026-01-01", "yesterday"})
    @DisplayName("baseDate 를 주면 yyyy-MM-dd 의 실제 날짜여야 한다")
    void rejectsMalformedBaseDate(String baseDate) {
        assertThatThrownBy(() -> validator.validate(parameters(RUN_AT, baseDate)))
            .isInstanceOf(JobParametersInvalidException.class)
            .hasMessageContaining("'baseDate'")
            .hasMessageContaining("yyyy-MM-dd");
    }

    @ParameterizedTest(name = "baseDate={0}")
    @ValueSource(strings = {"1999-12-31", "2026-10-10"})
    @DisplayName("baseDate 가 2000-01-01 ~ runAt 의 날짜 밖이면 거절한다 — 아직 오지 않은 날을 기준으로 받을 수 없다")
    void rejectsBaseDateOutOfRange(String baseDate) {
        assertThatThrownBy(() -> validator.validate(parameters(RUN_AT, baseDate)))
            .isInstanceOf(JobParametersInvalidException.class)
            .hasMessageContaining("between 2000-01-01 and 2026-10-09");
    }

    @Test
    @DisplayName("baseDate 가 없을 때도 runAt 의 날짜가 2000-01-01 앞이면 거절한다")
    void rejectsRunAtBeforeMinDate() {
        assertThatThrownBy(() -> validator.validate(parameters("1999-12-31T06:00:00", null)))
            .isInstanceOf(JobParametersInvalidException.class)
            .hasMessageContaining("between 2000-01-01 and 1999-12-31");
    }

    @ParameterizedTest(name = "baseDate={0}")
    @ValueSource(strings = {"2000-01-01", "2026-08-14", "2026-10-09", " 2026-08-14 "})
    @DisplayName("2000-01-01 ~ runAt 의 날짜는 통과하고, tasklet 은 같은 규칙으로 그 값을 기준일로 읽는다 (백필)")
    void acceptsBaseDateInRange(String baseDate) {
        assertThatCode(() -> validator.validate(parameters(RUN_AT, baseDate))).doesNotThrowAnyException();

        assertThat(SentinelImportJobParametersValidator.baseDate(baseDate, SentinelImportJobParametersValidator.runAt(RUN_AT)))
            .isEqualTo(LocalDate.parse(baseDate.trim()));
    }

    @Test
    @DisplayName("LocalDate 타입으로 들어온 baseDate 도 받는다")
    void acceptsLocalDateBaseDate() {
        JobParameters parameters = new JobParametersBuilder().addString("runAt", RUN_AT).addLocalDate("baseDate", LocalDate.of(2026, 8, 14))
            .toJobParameters();

        assertThatCode(() -> validator.validate(parameters)).doesNotThrowAnyException();
        assertThat(SentinelImportJobParametersValidator.baseDate(LocalDate.of(2026, 8, 14), LocalDateTime.parse(RUN_AT)))
            .isEqualTo(LocalDate.of(2026, 8, 14));
    }

    private static JobParameters parameters(String runAt, String baseDate) {
        JobParametersBuilder builder = new JobParametersBuilder().addString("runAt", runAt);
        if (baseDate != null) {
            builder.addString("baseDate", baseDate);
        }
        return builder.toJobParameters();
    }
}
