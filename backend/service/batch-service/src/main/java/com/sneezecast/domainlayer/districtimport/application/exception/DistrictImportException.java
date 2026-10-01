package com.sneezecast.domainlayer.districtimport.application.exception;

import lombok.Getter;

/**
 * 상세는 {@code Object... messageArgs} 로 받아 에러코드 메시지의 자리 표시자에 넣는다. 메시지 앞에 코드를 붙여 배치 메타데이터의
 * EXIT_MESSAGE 만 보고도 원인을 가를 수 있게 한다.
 */
@Getter
public class DistrictImportException extends RuntimeException {

    private final DistrictImportErrorCode errorCode;

    public DistrictImportException(DistrictImportErrorCode errorCode, Object... messageArgs) {
        super(format(errorCode, messageArgs));
        this.errorCode = errorCode;
    }

    public DistrictImportException(DistrictImportErrorCode errorCode, Throwable cause, Object... messageArgs) {
        super(format(errorCode, messageArgs), cause);
        this.errorCode = errorCode;
    }

    private static String format(DistrictImportErrorCode errorCode, Object... messageArgs) {
        return "[%s] %s".formatted(errorCode.getCode(), errorCode.getMessage().formatted(messageArgs));
    }
}
