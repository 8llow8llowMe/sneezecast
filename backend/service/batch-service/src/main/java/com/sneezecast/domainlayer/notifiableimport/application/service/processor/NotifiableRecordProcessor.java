package com.sneezecast.domainlayer.notifiableimport.application.service.processor;

import com.sneezecast.domainlayer.notifiableimport.application.exception.NotifiableImportErrorCode;
import com.sneezecast.domainlayer.notifiableimport.application.exception.NotifiableImportException;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableRegionMeasure;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableRegionRow;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableRequest;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableWeeklyRow;
import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import com.sneezecast.domainlayer.official.domain.enums.OfficialMetric;
import com.sneezecast.domainlayer.official.domain.enums.OfficialPeriodType;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialRegionLevel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import com.sneezecast.domainlayer.official.domain.model.KdcaWeek;
import com.sneezecast.domainlayer.official.domain.model.OfficialRecord;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * 전수신고 원천 행 → {@code official_surveillance} 행 변환 (entity-design §3-2). DB 도 원천도 만지지 않는다.
 *
 * <ul>
 *   <li>주별 전국({@code /PeriodBasic}): 발생 수 · 연령 ALL · 전국({@code 00} / {@code 전국}) · WEEK. 기간은 질병관리청 주차({@link KdcaWeek}).</li>
 *   <li>시도 연별({@code /Region}): 지표는 요청의 {@code measure} · 시도(원천 코드 · 이름 그대로) · YEAR · 주차 0 · 1월 1일 ~ 12월 31일.</li>
 * </ul>
 * 변환에서 나는 {@link IllegalArgumentException}(예: 52주인 해의 53주)은 그 요청의 {@code KDCA_RESPONSE_INVALID} 다 — 원천이 기대와 다른 값을 준
 * 것이고, 조용히 틀린 기간으로 적재하지 않는다.
 */
@Component
public class NotifiableRecordProcessor {

    static final String NATION_REGION_NAME = "전국";

    public List<OfficialRecord> weeklyRecords(NotifiableRequest request, List<NotifiableWeeklyRow> rows) {
        return convert(request, rows, this::weeklyRecord);
    }

    public List<OfficialRecord> regionRecords(NotifiableRequest request, List<NotifiableRegionRow> rows) {
        OfficialMetric metric = metric(request.measure());
        return convert(request, rows, row -> regionRecord(metric, row));
    }

    private OfficialRecord weeklyRecord(NotifiableWeeklyRow row) {
        // 그 해 주 수를 넘는 주차(52주인 해의 53주)는 여기서 IllegalArgumentException 이다.
        KdcaWeek week = new KdcaWeek(row.periodYear(), row.periodWeek());
        return new OfficialRecord(OfficialSource.KDCA_NOTIFIABLE, OfficialProgram.NOTIFIABLE, row.diseaseKey(), row.diseaseName(), row.diseaseGroup(),
            OfficialMetric.CASE_COUNT, OfficialAgeGroup.ALL, OfficialRegionLevel.NATION, OfficialRecord.NATION_REGION_CODE, NATION_REGION_NAME,
            OfficialPeriodType.WEEK, row.periodYear(), row.periodWeek(), week.start(), week.end(), row.value());
    }

    private OfficialRecord regionRecord(OfficialMetric metric, NotifiableRegionRow row) {
        return new OfficialRecord(OfficialSource.KDCA_NOTIFIABLE, OfficialProgram.NOTIFIABLE, row.diseaseKey(), row.diseaseName(), row.diseaseGroup(),
            metric, OfficialAgeGroup.ALL, OfficialRegionLevel.SIDO, row.sidoCode(), row.sidoName(),
            OfficialPeriodType.YEAR, row.year(), 0, LocalDate.of(row.year(), Month.JANUARY, 1), LocalDate.of(row.year(), Month.DECEMBER, 31),
            row.value());
    }

    private static OfficialMetric metric(NotifiableRegionMeasure measure) {
        return switch (measure) {
            case CASE_COUNT -> OfficialMetric.CASE_COUNT;
            case INCIDENCE_PER_100K -> OfficialMetric.INCIDENCE_PER_100K;
        };
    }

    private static <T> List<OfficialRecord> convert(NotifiableRequest request, List<T> rows, Function<T, OfficialRecord> mapper) {
        List<OfficialRecord> records = new ArrayList<>(rows.size());
        for (T row : rows) {
            try {
                records.add(mapper.apply(row));
            } catch (IllegalArgumentException exception) {
                // 메시지는 연도 · 주차 · 감염병 키 정도다 (원천 경계에서 길이를 이미 잘랐다). 인증키 · URL 은 여기까지 오지 않는다.
                throw new NotifiableImportException(NotifiableImportErrorCode.KDCA_RESPONSE_INVALID, exception, request.requestKey(),
                    exception.getMessage());
            }
        }
        return records;
    }
}
