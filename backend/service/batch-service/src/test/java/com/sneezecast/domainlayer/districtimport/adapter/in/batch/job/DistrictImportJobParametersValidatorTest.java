package com.sneezecast.domainlayer.districtimport.adapter.in.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;

class DistrictImportJobParametersValidatorTest {

    /** UTC 로는 아직 2026-12-31 이지만 KST 로는 2027-01-01 인 시각 — 올해 판정은 KST 다. */
    private static final Clock NEW_YEAR_KST = Clock.fixed(Instant.parse("2026-12-31T16:00:00Z"), ZoneOffset.UTC);

    private final DistrictImportJobParametersValidator validator = new DistrictImportJobParametersValidator(NEW_YEAR_KST);

    @Test
    @DisplayName("year 가 없으면 잡을 시작하지 않는다")
    void rejectsMissingYear() {
        JobParameters parameters = new JobParametersBuilder().addString("runAt", "2026-10-01T10:00:00").toJobParameters();

        assertThatThrownBy(() -> validator.validate(parameters))
            .isInstanceOf(JobParametersInvalidException.class)
            .hasMessageContaining("'year'");
        assertThatThrownBy(() -> validator.validate(new JobParameters())).isInstanceOf(JobParametersInvalidException.class);
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(JobParametersInvalidException.class);
    }

    @ParameterizedTest(name = "year={0}")
    @ValueSource(strings = {"", " ", "25", "20255", "2025.0", "two", "-2025"})
    @DisplayName("year 가 4자리 정수가 아니면 거절한다")
    void rejectsMalformedYear(String year) {
        JobParameters parameters = new JobParametersBuilder().addString("year", year).toJobParameters();

        assertThatThrownBy(() -> validator.validate(parameters)).isInstanceOf(JobParametersInvalidException.class);
    }

    @ParameterizedTest(name = "year={0}")
    @ValueSource(strings = {"1999", "2028"})
    @DisplayName("year 가 2000 ~ 올해(KST) 밖이면 거절한다")
    void rejectsYearOutOfRange(String year) {
        JobParameters parameters = new JobParametersBuilder().addString("year", year).toJobParameters();

        assertThatThrownBy(() -> validator.validate(parameters))
            .isInstanceOf(JobParametersInvalidException.class)
            .hasMessageContaining("between 2000 and 2027");
    }

    @ParameterizedTest(name = "year={0}")
    @ValueSource(strings = {"2000", "2025", "2027"})
    @DisplayName("2000 ~ 올해는 통과한다 — runAt · allowMassRetire 는 없어도 된다")
    void acceptsYearInRange(String year) {
        JobParameters parameters = new JobParametersBuilder().addString("year", year).toJobParameters();

        assertThatCode(() -> validator.validate(parameters)).doesNotThrowAnyException();
        assertThat(DistrictImportJobParametersValidator.year(year)).isEqualTo(Integer.parseInt(year));
    }

    @Test
    @DisplayName("숫자 타입으로 들어온 year 도 받는다")
    void acceptsLongYear() {
        JobParameters parameters = new JobParametersBuilder().addLong("year", 2025L).toJobParameters();

        assertThatCode(() -> validator.validate(parameters)).doesNotThrowAnyException();
        assertThat(DistrictImportJobParametersValidator.year(2025L)).isEqualTo(2025);
    }

    @Test
    @DisplayName("allowMassRetire 는 정확히 'true' 일 때만 참이다 — 오타가 대규모 폐지 허용으로 떨어지지 않는다")
    void allowMassRetireIsTrueOnlyForExactTrue() {
        assertThat(DistrictImportJobParametersValidator.allowMassRetire("true")).isTrue();
        assertThat(DistrictImportJobParametersValidator.allowMassRetire(Boolean.TRUE)).isTrue();
        assertThat(DistrictImportJobParametersValidator.allowMassRetire(null)).isFalse();
        assertThat(DistrictImportJobParametersValidator.allowMassRetire("TRUE")).isFalse();
        assertThat(DistrictImportJobParametersValidator.allowMassRetire("yes")).isFalse();
        assertThat(DistrictImportJobParametersValidator.allowMassRetire("1")).isFalse();
        assertThat(DistrictImportJobParametersValidator.allowMassRetire(" true")).isFalse();
    }
}
