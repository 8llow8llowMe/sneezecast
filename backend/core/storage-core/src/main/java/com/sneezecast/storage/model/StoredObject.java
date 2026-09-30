package com.sneezecast.storage.model;

/**
 * 업로드 결과.
 *
 * <p>DB 에는 {@code objectKey} 만 저장하고 {@code publicUrl} 은 응답 조립용이다.
 * 엔드포인트/도메인이 바뀌어도 저장된 데이터를 마이그레이션할 필요가 없다.
 */
public record StoredObject(

    String objectKey,

    String publicUrl,

    String contentType,

    long size

) {

}
