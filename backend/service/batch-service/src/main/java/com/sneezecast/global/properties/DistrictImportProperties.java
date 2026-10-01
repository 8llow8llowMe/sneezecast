package com.sneezecast.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 행정동 마스터 적재 설정.
 *
 * @param maxRetireRatio 한 번에 폐지해도 되는 현행 코드 비율의 상한 (0 ~ 1). 넘으면 {@code allowMassRetire=true} 없이는 실패한다.
 *                       2024 → 2025 는 3,559개 중 3개(0.1%)였다 (entity-design §3-1)
 */
@ConfigurationProperties(prefix = "district-import")
public record DistrictImportProperties(@DefaultValue("0.02") double maxRetireRatio) {

    public DistrictImportProperties {
        if (Double.isNaN(maxRetireRatio) || maxRetireRatio < 0 || maxRetireRatio > 1) {
            throw new IllegalArgumentException("district-import.max-retire-ratio must be between 0 and 1. value=" + maxRetireRatio);
        }
    }
}
