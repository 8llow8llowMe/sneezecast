package com.sneezecast.apigateway.exception;

import lombok.Getter;

@Getter
public class GatewayException extends RuntimeException {

    private final GatewayErrorCode errorCode;

    public GatewayException(GatewayErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
