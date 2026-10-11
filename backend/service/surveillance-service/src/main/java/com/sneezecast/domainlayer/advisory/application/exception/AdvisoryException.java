package com.sneezecast.domainlayer.advisory.application.exception;

import lombok.Getter;

@Getter
public class AdvisoryException extends RuntimeException {

    private final AdvisoryErrorCode errorCode;

    public AdvisoryException(AdvisoryErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public AdvisoryException(AdvisoryErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }

    public AdvisoryException(AdvisoryErrorCode errorCode, Object... args) {
        super(String.format(errorCode.getMessage(), args));
        this.errorCode = errorCode;
    }
}
