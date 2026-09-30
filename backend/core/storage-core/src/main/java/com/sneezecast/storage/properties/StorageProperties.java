package com.sneezecast.storage.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 오브젝트 스토리지(MinIO) 설정.
 *
 * @param endpoint     백엔드가 접속할 주소. MinIO 와 다른 호스트에 있으므로 컨테이너명이 아니라
 *                     사설 IP(예: http://192.168.0.12:9000)를 쓴다.
 * @param publicUrl    브라우저가 객체를 조회할 공개 주소 (예: https://minio.8llow8llowme.com).
 *                     DB 에는 key 만 저장하고 응답 시 이 값으로 URL 을 조립한다.
 * @param bucket       버킷 이름
 * @param accessKey    MinIO access key
 * @param secretKey    MinIO secret key
 * @param maxFileBytes 파일 1개 최대 크기 (서버측 명시 검증. multipart 설정에만 의존하지 않는다)
 */
@ConfigurationProperties(prefix = "infra.storage")
public record StorageProperties(
    String endpoint,
    String publicUrl,
    String bucket,
    String accessKey,
    String secretKey,
    Long maxFileBytes
) {

    private static final long DEFAULT_MAX_FILE_BYTES = 5L * 1024 * 1024;

    public long normalizedMaxFileBytes() {
        if (maxFileBytes == null || maxFileBytes <= 0) {
            return DEFAULT_MAX_FILE_BYTES;
        }
        return maxFileBytes;
    }

    /**
     * 공개 조회 URL 을 조립한다. path-style 접근이라 {publicUrl}/{bucket}/{key} 형식이다.
     * key 는 생성 규칙상 ASCII 안전 문자만 포함하므로 별도 인코딩이 필요 없다.
     */
    public String toPublicUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        String base = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        return "%s/%s/%s".formatted(base, bucket, objectKey);
    }
}
