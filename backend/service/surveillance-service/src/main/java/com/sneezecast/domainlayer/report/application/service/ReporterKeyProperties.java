package com.sneezecast.domainlayer.report.application.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code reporter_key} 산출용 pepper. 값은 env {@code REPORTER_KEY_PEPPER} 로만 받고 기본값을 두지 않는다 (Vault 의 surveillance 경로에만 있다).
 *
 * <p><b>pepper 는 교체하지 않는다</b> — 바꾸면 저장된 모든 보고의 {@code reporter_key} 가 달라진다 (architecture-guide §6, §9).
 */
@ConfigurationProperties(prefix = "surveillance.reporter-key")
public record ReporterKeyProperties(
    String pepper
) {

    /** record 기본 toString 은 필드 값을 그대로 찍는다. 설정 덤프·디버그 로그로 pepper 가 새지 않게 가린다. */
    @Override
    public String toString() {
        return "ReporterKeyProperties[pepper=****]";
    }
}
