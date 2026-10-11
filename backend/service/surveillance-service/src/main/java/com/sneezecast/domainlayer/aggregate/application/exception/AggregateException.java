package com.sneezecast.domainlayer.aggregate.application.exception;

import lombok.Getter;

@Getter
public class AggregateException extends RuntimeException {

    private final AggregateErrorCode errorCode;

    public AggregateException(AggregateErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public AggregateException(AggregateErrorCode errorCode, Object... args) {
        super(String.format(errorCode.getMessage(), args));
        this.errorCode = errorCode;
    }
}
