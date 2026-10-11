package com.sneezecast.domainlayer.member.adapter.in.web.exception;

import com.sneezecast.common.dto.Response;
import com.sneezecast.storage.exception.StorageErrorCode;
import com.sneezecast.storage.exception.StorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 스토리지 예외(프로필 이미지 업로드)를 {@code Response} 봉투로 바꾼다 — 코드는 storage-core 의 {@code STORAGE_00x} 를 그대로 쓴다. 등록하지 않으면 봉투
 * 밖의 Spring 기본 500 이 나간다({@link StorageErrorCode} 주석).
 *
 * <p>업로드를 받는 컨트롤러는 member 컨텍스트뿐이라 범위를 member 로 둔다. 다른 예외 유형과 겹치지 않지만 advice 가 여럿이라 순서를 준다
 * ({@link MemberRequestExceptionHandler} 다음).
 */
@Slf4j
@Order(1)
@RestControllerAdvice(basePackages = "com.sneezecast.domainlayer.member")
public class MemberStorageExceptionHandler {

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<Response<Void>> handleStorageException(StorageException exception) {
        StorageErrorCode errorCode = exception.getErrorCode();
        if (errorCode.getHttpStatus().is5xxServerError()) {
            log.error("storage request failed code={}", errorCode.getCode());
        }
        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .body(Response.fail(errorCode.getCode(), errorCode.getMessage()));
    }
}
