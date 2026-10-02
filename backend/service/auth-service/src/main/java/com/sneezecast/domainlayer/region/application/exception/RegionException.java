package com.sneezecast.domainlayer.region.application.exception;

import lombok.Getter;

@Getter
public class RegionException extends RuntimeException {

    private final RegionErrorCode errorCode;

    public RegionException(RegionErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public RegionException(RegionErrorCode errorCode, Object... args) {
        super(String.format(errorCode.getMessage(), args));
        this.errorCode = errorCode;
    }

    /** 원격 호출 · DB 제약 위반처럼 원인이 따로 있는 오류. 사용자 메시지는 그대로이고 원인은 로그용으로만 남긴다. */
    public RegionException(RegionErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
