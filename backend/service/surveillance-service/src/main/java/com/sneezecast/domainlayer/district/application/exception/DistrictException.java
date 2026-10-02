package com.sneezecast.domainlayer.district.application.exception;

import lombok.Getter;

@Getter
public class DistrictException extends RuntimeException {

    private final DistrictErrorCode errorCode;

    public DistrictException(DistrictErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public DistrictException(DistrictErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }

    public DistrictException(DistrictErrorCode errorCode, Object... args) {
        super(String.format(errorCode.getMessage(), args));
        this.errorCode = errorCode;
    }
}
