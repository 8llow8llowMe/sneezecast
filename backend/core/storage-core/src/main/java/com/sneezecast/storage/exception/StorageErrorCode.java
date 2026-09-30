package com.sneezecast.storage.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 스토리지 공통 에러코드.
 *
 * <p>이 enum 을 쓰는 서비스는 반드시 ExceptionHandler 에 {@code StorageException} 핸들러를 등록해야 한다.
 * 등록하지 않으면 공통 Response 래퍼가 아니라 Spring 기본 500 응답이 나간다.
 */
@Getter
@RequiredArgsConstructor
public enum StorageErrorCode {

    FILE_REQUIRED("STORAGE_001", "업로드할 파일이 없습니다.", HttpStatus.BAD_REQUEST),
    FILE_TOO_LARGE("STORAGE_002", "허용 크기를 초과한 파일입니다.", HttpStatus.BAD_REQUEST),
    UNSUPPORTED_FILE_TYPE("STORAGE_003", "지원하지 않는 파일 형식입니다. (jpg, png, gif, webp 이미지만 가능)", HttpStatus.BAD_REQUEST),
    FILE_UPLOAD_FAILED("STORAGE_004", "파일 업로드에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_OBJECT_KEY("STORAGE_005", "유효하지 않은 파일 키입니다.", HttpStatus.BAD_REQUEST),
    FORBIDDEN_OBJECT_KEY("STORAGE_006", "본인이 업로드한 파일만 사용할 수 있습니다.", HttpStatus.FORBIDDEN),
    TOO_MANY_FILES("STORAGE_007", "허용 개수를 초과한 파일입니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
