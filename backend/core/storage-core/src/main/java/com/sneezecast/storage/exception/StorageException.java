package com.sneezecast.storage.exception;

import lombok.Getter;

@Getter
public class StorageException extends RuntimeException {

    private final StorageErrorCode errorCode;

    public StorageException(StorageErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public StorageException(StorageErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
