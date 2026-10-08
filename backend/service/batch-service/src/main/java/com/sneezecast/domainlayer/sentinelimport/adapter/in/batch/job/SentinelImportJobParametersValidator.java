package com.sneezecast.domainlayer.sentinelimport.adapter.in.batch.job;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code sentinelImportJob} 파라미터 검증. 잡을 시작하기 전에(JobInstance 를 만들기 전에) 거절한다.
 *
 * <ul>
 *   <li>{@code runAt} — 필수. ISO 로컬 날짜 · 시각(스케줄 발화가 넘기는 {@code 2026-10-09T06:00:00} 꼴, 초 생략 · 소수 초도 받는다). 재실행을
 *       가르는 식별 파라미터이자 적재 이력의 {@code run_started_at} 이다.</li>
 *   <li>{@code baseDate} — 선택. 계획의 기준일({@code yyyy-MM-dd})이고, 기본은 {@code runAt} 의 날짜다. 그 날이 든 주를 끝으로 최근 N주와 그
 *       날의 절기를 받는다 — 최근 N주보다 오래 빠졌을 때 백필에 쓴다. 2000-01-01 ~ {@code runAt} 의 날짜. 명령줄 값은 문자열로 들어온다.</li>
 * </ul>
 * 해석 규칙은 tasklet 도 같은 메서드({@link #runAt}, {@link #baseDate})로 읽어 검증과 실행이 어긋나지 않게 한다.
 */
@Component
public class SentinelImportJobParametersValidator implements JobParametersValidator {

    public static final String RUN_AT = "runAt";
    public static final String BASE_DATE = "baseDate";

    static final LocalDate MIN_BASE_DATE = LocalDate.of(2000, 1, 1);
    private static final Pattern RUN_AT_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T.+");
    private static final Pattern BASE_DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    @Override
    public void validate(@Nullable JobParameters parameters) throws JobParametersInvalidException {
        Object runAtValue = value(parameters, RUN_AT);
        if (runAtValue == null || runAtValue.toString().isBlank()) {
            throw new JobParametersInvalidException("sentinelImportJob requires job parameter 'runAt' (e.g. runAt=2026-10-09T06:00:00)");
        }
        // LocalDateTime.parse · LocalDate.parse 는 부호 붙은 5자리 이상 연도(+12026-01-01)도 받으므로 꼴을 먼저 본다.
        if (!RUN_AT_PATTERN.matcher(runAtValue.toString().trim()).matches()) {
            throw malformedRunAt(runAtValue);
        }
        LocalDateTime runAt;
        try {
            runAt = runAt(runAtValue);
        } catch (DateTimeParseException exception) {
            throw malformedRunAt(runAtValue);
        }

        Object baseDateValue = value(parameters, BASE_DATE);
        if (baseDateValue != null && !BASE_DATE_PATTERN.matcher(baseDateValue.toString().trim()).matches()) {
            throw malformedBaseDate(baseDateValue);
        }
        LocalDate baseDate;
        try {
            baseDate = baseDate(baseDateValue, runAt);
        } catch (DateTimeParseException exception) {
            throw malformedBaseDate(baseDateValue);
        }
        LocalDate maxBaseDate = runAt.toLocalDate();
        if (baseDate.isBefore(MIN_BASE_DATE) || baseDate.isAfter(maxBaseDate)) {
            throw new JobParametersInvalidException("job parameter 'baseDate' (default: date of runAt) must be between %s and %s. baseDate=%s"
                .formatted(MIN_BASE_DATE, maxBaseDate, baseDate));
        }
    }

    /** {@link #validate} 를 통과한 값만 들어온다고 가정한다. */
    public static LocalDateTime runAt(Object value) {
        return LocalDateTime.parse(value.toString().trim());
    }

    /** {@code baseDate} 가 없으면 {@code runAt} 의 날짜다. {@link #validate} 를 통과한 값만 들어온다고 가정한다. */
    public static LocalDate baseDate(@Nullable Object baseDateValue, LocalDateTime runAt) {
        return baseDateValue == null ? runAt.toLocalDate() : LocalDate.parse(baseDateValue.toString().trim());
    }

    private static JobParametersInvalidException malformedRunAt(Object runAtValue) {
        return new JobParametersInvalidException(
            "job parameter 'runAt' must be an ISO local date-time (e.g. 2026-10-09T06:00:00). runAt=" + runAtValue.toString().trim());
    }

    private static JobParametersInvalidException malformedBaseDate(Object baseDateValue) {
        return new JobParametersInvalidException("job parameter 'baseDate' must be an ISO date (yyyy-MM-dd). baseDate=" + baseDateValue.toString().trim());
    }

    @Nullable
    private static Object value(@Nullable JobParameters parameters, String name) {
        JobParameter<?> parameter = parameters == null ? null : parameters.getParameter(name);
        return parameter == null ? null : parameter.getValue();
    }
}
