package com.sneezecast.domainlayer.report.adapter.out.persistence;

import com.sneezecast.domainlayer.report.adapter.out.persistence.entity.WeeklyReportEntity;
import com.sneezecast.domainlayer.report.adapter.out.persistence.repository.WeeklyReportRepository;
import com.sneezecast.domainlayer.report.application.exception.ReportErrorCode;
import com.sneezecast.domainlayer.report.application.exception.ReportException;
import com.sneezecast.domainlayer.report.application.mapper.WeeklyReportMapper;
import com.sneezecast.domainlayer.report.application.port.out.WeeklyReportRepositoryPort;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import com.sneezecast.domainlayer.report.domain.model.WeeklyReport;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class WeeklyReportRepositoryAdapter implements WeeklyReportRepositoryPort {

    private final WeeklyReportRepository weeklyReportRepository;
    private final WeeklyReportMapper weeklyReportMapper;
    private final Clock clock;

    @Override
    public Optional<WeeklyReport> findByReporterKeyAndIsoWeek(String reporterKey, ReportWeek isoWeek) {
        return weeklyReportRepository.findByReporterKeyAndIsoWeek(reporterKey, isoWeek.value()).map(weeklyReportMapper::toDomainFromEntity);
    }

    /**
     * {@code saveAndFlush} 로 INSERT 를 바로 내보내 unique 위반이 이 자리에서 드러나게 한다. 커밋 시점까지 미루면 예외가 트랜잭션 프록시 밖에서
     * {@code DataIntegrityViolationException} 그대로 터져 WebFacade 가 동시 제출과 구분할 수 없다.
     *
     * <p>원인 예외를 붙이지 않는다 — DB 메시지에 {@code reporter_key} · 주 값이 실려 로그로 새기 때문이다. 제약 이름으로 원인이 이미 확정된다.
     * 같은 이유로 Hibernate 의 SQL 오류 로그({@code SqlExceptionHelper})도 application.yml 에서 끈다.
     */
    @Override
    public WeeklyReport insert(WeeklyReport report) {
        try {
            WeeklyReportEntity saved = weeklyReportRepository.saveAndFlush(weeklyReportMapper.toEntityFromDomain(report));
            return weeklyReportMapper.toDomainFromEntity(saved);
        } catch (DataIntegrityViolationException exception) {
            if (isReporterWeekUniqueViolation(exception)) {
                throw new ReportException(ReportErrorCode.CONCURRENT_SUBMISSION);
            }
            throw exception;
        }
    }

    /**
     * 갱신 쿼리 뒤 같은 키로 다시 읽어 돌려준다 (쿼리가 영속성 컨텍스트를 비워 DB 값을 읽는다). 트랜잭션이 없으면 갱신과 재조회가 따로 커밋돼
     * 그 사이 다른 요청의 값이 섞일 수 있어 {@code MANDATORY} 로 호출자 트랜잭션을 강제한다.
     *
     * <p>{@code updatedAt} 은 Auditing 과 같은 기준(JVM 기본 시간대의 지역 시각, 배포 환경은 {@code -Duser.timezone=Asia/Seoul})으로 쓰되
     * 순간은 {@link Clock} 빈에서 받는다 — 같은 행의 {@code createdAt} 과 시간대가 갈리지 않게 한다.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<WeeklyReport> updateCurrent(String reporterKey, ReportWeek isoWeek, String districtCode, Set<SymptomGroup> symptoms) {
        LocalDateTime updatedAt = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
        int updated = weeklyReportRepository.updateCurrent(reporterKey, isoWeek.value(), districtCode, weeklyReportMapper.toSymptomMask(symptoms), updatedAt);
        if (updated == 0) {
            return Optional.empty();
        }
        return findByReporterKeyAndIsoWeek(reporterKey, isoWeek);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteByReporterKeyAndIsoWeek(String reporterKey, ReportWeek isoWeek) {
        return weeklyReportRepository.deleteByReporterKeyAndIsoWeek(reporterKey, isoWeek.value());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteAllByReporterKey(String reporterKey) {
        return weeklyReportRepository.deleteAllByReporterKey(reporterKey);
    }

    /** MySQL 은 {@code for key 'weekly_report.uk_...'}, H2 는 {@code PUBLIC.UK_... ON ...} 로 싣는다 — 대소문자를 무시하고 본다. */
    private boolean isReporterWeekUniqueViolation(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains(WeeklyReportEntity.REPORTER_KEY_ISO_WEEK_UNIQUE_INDEX);
    }
}
