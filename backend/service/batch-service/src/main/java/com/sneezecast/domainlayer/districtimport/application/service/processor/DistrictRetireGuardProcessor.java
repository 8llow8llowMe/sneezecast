package com.sneezecast.domainlayer.districtimport.application.service.processor;

import com.sneezecast.domainlayer.districtimport.application.exception.DistrictImportErrorCode;
import com.sneezecast.domainlayer.districtimport.application.exception.DistrictImportException;
import com.sneezecast.global.properties.DistrictImportProperties;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 대규모 폐지 보호 규칙 (entity-design §3-1).
 *
 * <p>폐지되면 그 동을 고른 회원 전원이 재선택해야 한다. 잘린 응답이나 광주 · 전남 통합 같은 대규모 코드 변경이 조용히 반영되지 않게,
 * 사라지는 현행 코드 비율이 임계값을 넘으면 운영자가 {@code allowMassRetire=true} 로 다시 돌리기 전까지 막는다. 포트를 부르지 않는 순수 판정이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistrictRetireGuardProcessor {

    private final DistrictImportProperties properties;

    /**
     * @param year            적재 기준 연도 (메시지용)
     * @param activeCount     적재 전 현행 코드 수
     * @param retiringCount   새 스냅샷에 없어 폐지될 현행 코드 수
     * @param allowMassRetire 운영자 허용 여부
     * @throws DistrictImportException {@code MASS_RETIRE_BLOCKED} — 비율이 임계값을 넘고 허용이 없을 때
     */
    public void check(int year, int activeCount, int retiringCount, boolean allowMassRetire) {
        double ratio = retireRatio(activeCount, retiringCount);
        double maxRatio = properties.maxRetireRatio();
        if (ratio <= maxRatio) {
            return;
        }
        if (!allowMassRetire) {
            throw new DistrictImportException(DistrictImportErrorCode.MASS_RETIRE_BLOCKED, year, retiringCount, activeCount, format(ratio), format(maxRatio));
        }
        log.warn("District mass retire allowed by job parameter. year={} retiring={} active={} ratio={} maxRatio={}",
            year, retiringCount, activeCount, format(ratio), format(maxRatio));
    }

    /** 현행이 하나도 없으면(첫 적재) 폐지할 것도 없으므로 0 이다. */
    static double retireRatio(int activeCount, int retiringCount) {
        if (activeCount == 0) {
            return 0;
        }
        return (double) retiringCount / activeCount;
    }

    private static String format(double ratio) {
        return String.format(Locale.ROOT, "%.4f", ratio);
    }
}
