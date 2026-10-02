package com.sneezecast.domainlayer.notifiableimport.adapter.in.batch.job;

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
 * {@code notifiableImportJob} 파라미터 검증. 잡을 시작하기 전에(JobInstance 를 만들기 전에) 거절한다.
 *
 * <ul>
 *   <li>{@code runAt} — 필수. ISO 로컬 날짜 · 시각(스케줄 발화가 넘기는 {@code 2026-10-06T05:00:00} 꼴, 초 생략 · 소수 초도 받는다). 재실행을
 *       가르는 식별 파라미터이자 적재 이력의 {@code run_started_at} 이다.</li>
 *   <li>{@code year} — 선택. 올해로 볼 연도(계획은 그 해와 전년)이고, 기본은 {@code runAt} 의 연도다. 지난해를 다시 받는 백필에 쓴다.
 *       4자리 정수, 2000 ~ {@code runAt} 의 연도. 명령줄 값은 문자열로 들어온다.</li>
 * </ul>
 * 해석 규칙은 tasklet 도 같은 메서드({@link #runAt}, {@link #currentYear})로 읽어 검증과 실행이 어긋나지 않게 한다.
 */
@Component
public class NotifiableImportJobParametersValidator implements JobParametersValidator {

    public static final String RUN_AT = "runAt";
    public static final String YEAR = "year";

    static final int MIN_YEAR = 2000;
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\d{4}");

    @Override
    public void validate(@Nullable JobParameters parameters) throws JobParametersInvalidException {
        Object runAtValue = value(parameters, RUN_AT);
        if (runAtValue == null || runAtValue.toString().isBlank()) {
            throw new JobParametersInvalidException("notifiableImportJob requires job parameter 'runAt' (e.g. runAt=2026-10-06T05:00:00)");
        }
        LocalDateTime runAt;
        try {
            runAt = runAt(runAtValue);
        } catch (DateTimeParseException exception) {
            throw new JobParametersInvalidException(
                "job parameter 'runAt' must be an ISO local date-time (e.g. 2026-10-06T05:00:00). runAt=" + runAtValue.toString().trim());
        }

        Object yearValue = value(parameters, YEAR);
        int maxYear = runAt.getYear();
        if (yearValue != null && !YEAR_PATTERN.matcher(yearValue.toString().trim()).matches()) {
            throw new JobParametersInvalidException("job parameter 'year' must be a 4-digit year. year=" + yearValue.toString().trim());
        }
        int year = currentYear(yearValue, runAt);
        if (year < MIN_YEAR || year > maxYear) {
            throw new JobParametersInvalidException("job parameter 'year' (default: year of runAt) must be between %d and %d. year=%d"
                .formatted(MIN_YEAR, maxYear, year));
        }
    }

    /** {@link #validate} 를 통과한 값만 들어온다고 가정한다. */
    public static LocalDateTime runAt(Object value) {
        return LocalDateTime.parse(value.toString().trim());
    }

    /** {@code year} 가 없으면 {@code runAt} 의 연도다. {@link #validate} 를 통과한 값만 들어온다고 가정한다. */
    public static int currentYear(@Nullable Object yearValue, LocalDateTime runAt) {
        return yearValue == null ? runAt.getYear() : Integer.parseInt(yearValue.toString().trim());
    }

    @Nullable
    private static Object value(@Nullable JobParameters parameters, String name) {
        JobParameter<?> parameter = parameters == null ? null : parameters.getParameter(name);
        return parameter == null ? null : parameter.getValue();
    }
}
