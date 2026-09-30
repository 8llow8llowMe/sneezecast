package com.sneezecast.storage.util;

import com.sneezecast.storage.exception.StorageErrorCode;
import com.sneezecast.storage.exception.StorageException;
import com.sneezecast.storage.model.ImageFileType;
import com.sneezecast.storage.model.StorageDomain;
import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 오브젝트 키 생성/검증.
 *
 * <p>키는 전적으로 서버가 생성한다. 원본 파일명을 키에 섞지 않기 때문에
 * 경로 조작(../), URL 인코딩 깨짐, 파일명 길이로 인한 컬럼 오버플로우가 원천적으로 발생하지 않는다.
 */
public final class ObjectKeyFactory {

    // 생성 규칙과 정확히 일치하는 키만 통과시킨다 (경로 조작·타 도메인 키 차단).
    private static final Pattern KEY_PATTERN =
        Pattern.compile("^[a-z/]+/(\\d+)/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.[a-z]{3,4}$");

    private ObjectKeyFactory() {
    }

    public static String generate(StorageDomain domain, long memberId, ImageFileType imageFileType) {
        LocalDate today = LocalDate.now();
        return "%s/%d/%04d/%02d/%s.%s".formatted(
            domain.prefix(), memberId, today.getYear(), today.getMonthValue(),
            UUID.randomUUID(), imageFileType.extension()
        );
    }

    /**
     * 클라이언트가 보낸 키가 해당 도메인에서 이 회원이 올린 것인지 검증한다.
     * 형식이 어긋나면 {@code INVALID_OBJECT_KEY}, 남의 키면 {@code FORBIDDEN_OBJECT_KEY} 를 던진다.
     */
    public static void validateOwnership(String objectKey, StorageDomain domain, long memberId) {
        if (objectKey == null || objectKey.isBlank() || !KEY_PATTERN.matcher(objectKey).matches()) {
            throw new StorageException(StorageErrorCode.INVALID_OBJECT_KEY);
        }
        if (!objectKey.startsWith(domain.prefix() + "/")) {
            throw new StorageException(StorageErrorCode.INVALID_OBJECT_KEY);
        }
        if (!objectKey.startsWith("%s/%d/".formatted(domain.prefix(), memberId))) {
            throw new StorageException(StorageErrorCode.FORBIDDEN_OBJECT_KEY);
        }
    }
}
