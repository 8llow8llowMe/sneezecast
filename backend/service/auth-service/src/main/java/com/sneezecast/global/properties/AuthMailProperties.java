package com.sneezecast.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증 메일 발신자 표시 설정. 둘 다 선택이다.
 *
 * @param fromName    발신자 표시 이름. 비면 {@value #DEFAULT_FROM_NAME}
 * @param fromAddress 발신 주소. 비면 SMTP 계정({@code spring.mail.username})에서 유도한다
 */
@ConfigurationProperties(prefix = "auth.mail")
public record AuthMailProperties(
    String fromName,
    String fromAddress
) {

    public static final String DEFAULT_FROM_NAME = "우리동네체온계";

    public AuthMailProperties {
        fromName = isBlank(fromName) ? DEFAULT_FROM_NAME : fromName.trim();
        fromAddress = isBlank(fromAddress) ? "" : fromAddress.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
