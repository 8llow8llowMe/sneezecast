package com.sneezecast.domainlayer.districtimport.adapter.in.batch.job;

import java.time.Clock;
import java.time.Year;
import java.time.ZoneId;
import java.util.regex.Pattern;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code districtImportJob} 파라미터 검증. 잡을 시작하기 전에(JobInstance 를 만들기 전에) 거절한다.
 *
 * <ul>
 *   <li>{@code year} — 필수. 4자리 정수, 2000 ~ 올해(KST). 명령줄 값은 문자열로 들어온다.</li>
 *   <li>{@code runAt} — 재실행용 식별 파라미터. 필수가 아니고 값을 해석하지 않는다.</li>
 *   <li>{@code allowMassRetire} — 선택. 정확히 {@code true} 일 때만 참이다 ({@code TRUE} · {@code yes} · {@code 1} 은 거짓). 대규모 폐지를
 *       허용하는 스위치라 오타가 허용 쪽으로 떨어지면 안 된다.</li>
 * </ul>
 * 해석 규칙은 tasklet 도 같은 메서드({@link #year}, {@link #allowMassRetire})로 읽어 검증과 실행이 어긋나지 않게 한다.
 */
@Component
public class DistrictImportJobParametersValidator implements JobParametersValidator {

    public static final String YEAR = "year";
    public static final String RUN_AT = "runAt";
    public static final String ALLOW_MASS_RETIRE = "allowMassRetire";

    static final int MIN_YEAR = 2000;
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\d{4}");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final Clock clock;

    public DistrictImportJobParametersValidator() {
        this(Clock.system(KST));
    }

    DistrictImportJobParametersValidator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void validate(@Nullable JobParameters parameters) throws JobParametersInvalidException {
        JobParameter<?> parameter = parameters == null ? null : parameters.getParameter(YEAR);
        Object value = parameter == null ? null : parameter.getValue();
        if (value == null || value.toString().isBlank()) {
            throw new JobParametersInvalidException("districtImportJob requires job parameter 'year' (e.g. year=2025)");
        }
        String text = value.toString().trim();
        if (!YEAR_PATTERN.matcher(text).matches()) {
            throw new JobParametersInvalidException("job parameter 'year' must be a 4-digit year. year=" + text);
        }
        int year = Integer.parseInt(text);
        int currentYear = Year.now(clock.withZone(KST)).getValue();
        if (year < MIN_YEAR || year > currentYear) {
            throw new JobParametersInvalidException("job parameter 'year' must be between %d and %d. year=%d".formatted(MIN_YEAR, currentYear, year));
        }
    }

    /** {@link #validate} 를 통과한 값만 들어온다고 가정한다. */
    public static int year(Object value) {
        return Integer.parseInt(value.toString().trim());
    }

    public static boolean allowMassRetire(@Nullable Object value) {
        return value != null && "true".equals(value.toString());
    }
}
