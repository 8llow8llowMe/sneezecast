package com.sneezecast.domainlayer.report.application.exception;

import lombok.Getter;

@Getter
public class ReportException extends RuntimeException {

    private final ReportErrorCode errorCode;

    public ReportException(ReportErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ReportException(ReportErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }

    public ReportException(ReportErrorCode errorCode, Object... args) {
        super(String.format(errorCode.getMessage(), args));
        this.errorCode = errorCode;
    }
}
