package com.sneezecast.domainlayer.sentinelimport.application.service.processor;

import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import com.sneezecast.domainlayer.official.domain.enums.OfficialMetric;
import com.sneezecast.domainlayer.official.domain.enums.OfficialPeriodType;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialRegionLevel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import com.sneezecast.domainlayer.official.domain.model.KdcaWeek;
import com.sneezecast.domainlayer.official.domain.model.OfficialRecord;
import com.sneezecast.domainlayer.sentinelimport.application.exception.SentinelImportErrorCode;
import com.sneezecast.domainlayer.sentinelimport.application.exception.SentinelImportException;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelIliRow;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelPathogenRow;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * 표본감시 원천 행 → {@code official_surveillance} 행 변환 (entity-design §3-2). DB 도 원천도 만지지 않는다.
 *
 * <ul>
 *   <li>급성호흡기 · 장관감염증: 신고 수 · 연령 ALL · 전국({@code 00} / {@code 전국}) · WEEK. 병원체 코드 · 이름 · 분류는 원천 그대로(계는
 *       {@code TOTAL}).</li>
 *   <li>인플루엔자: 감염병 키 {@value #ILI_DISEASE_KEY} · 분류 없음 · 의사환자 분율(1,000명당) · 행의 연령대 · 전국 · WEEK.</li>
 * </ul>
 * 기간은 질병관리청 주차({@link KdcaWeek})다. 변환에서 나는 {@link IllegalArgumentException}(예: 52주인 해의 53주)은 그 요청의
 * {@code RESPONSE_INVALID} 다 — 원천이 기대와 다른 값을 준 것이고, 조용히 틀린 기간으로 적재하지 않는다.
 */
@Component
public class SentinelRecordProcessor {

    /** 전수신고의 전국 이름과 같은 값이다. 도메인 간 코드를 공유하지 않아 따로 둔다. */
    static final String NATION_REGION_NAME = "전국";
    static final String ILI_DISEASE_KEY = "ILI";
    static final String ILI_DISEASE_NAME = "인플루엔자 의사환자 분율";

    /** 급성호흡기 · 장관감염증 요청의 행. 프로그램은 요청의 것이다. */
    public List<OfficialRecord> pathogenRecords(SentinelRequest request, List<SentinelPathogenRow> rows) {
        OfficialProgram program = request.program().getOfficialProgram();
        return convert(request, rows, row -> pathogenRecord(program, row));
    }

    /** 인플루엔자 절기 요청의 행. */
    public List<OfficialRecord> influenzaRecords(SentinelRequest request, List<SentinelIliRow> rows) {
        OfficialProgram program = request.program().getOfficialProgram();
        return convert(request, rows, row -> influenzaRecord(program, row));
    }

    private static OfficialRecord pathogenRecord(OfficialProgram program, SentinelPathogenRow row) {
        // 그 해 주 수를 넘는 주차(52주인 해의 53주)는 여기서 IllegalArgumentException 이다.
        KdcaWeek week = new KdcaWeek(row.year(), row.week());
        return new OfficialRecord(OfficialSource.KDCA_SENTINEL, program, row.diseaseKey(), row.diseaseName(), row.diseaseGroup(),
            OfficialMetric.CASE_COUNT, OfficialAgeGroup.ALL, OfficialRegionLevel.NATION, OfficialRecord.NATION_REGION_CODE, NATION_REGION_NAME,
            OfficialPeriodType.WEEK, row.year(), row.week(), week.start(), week.end(), row.value());
    }

    private static OfficialRecord influenzaRecord(OfficialProgram program, SentinelIliRow row) {
        KdcaWeek week = new KdcaWeek(row.year(), row.week());
        return new OfficialRecord(OfficialSource.KDCA_SENTINEL, program, ILI_DISEASE_KEY, ILI_DISEASE_NAME, null,
            OfficialMetric.ILI_PER_1000, row.ageGroup(), OfficialRegionLevel.NATION, OfficialRecord.NATION_REGION_CODE, NATION_REGION_NAME,
            OfficialPeriodType.WEEK, row.year(), row.week(), week.start(), week.end(), row.value());
    }

    private static <T> List<OfficialRecord> convert(SentinelRequest request, List<T> rows, Function<T, OfficialRecord> mapper) {
        List<OfficialRecord> records = new ArrayList<>(rows.size());
        for (T row : rows) {
            try {
                records.add(mapper.apply(row));
            } catch (IllegalArgumentException exception) {
                // 메시지는 연도 · 주차 · 병원체 키 정도다 (원천 경계에서 길이를 이미 잘랐다). 쿠키 · URL · 본문은 여기까지 오지 않는다.
                throw new SentinelImportException(SentinelImportErrorCode.RESPONSE_INVALID, exception, request.requestKey(), exception.getMessage());
            }
        }
        return records;
    }
}
