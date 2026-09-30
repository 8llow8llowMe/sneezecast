package com.sneezecast.storage.model;

/**
 * 업로드 요청 자료형.
 *
 * <p>도메인/애플리케이션 계층이 {@code MultipartFile}(web 타입)에 의존하지 않도록 어댑터 경계에서 이 타입으로 변환한다.
 *
 * @param content          파일 내용. 매직 바이트 검증을 위해 전체를 메모리에 올린다
 *                         (허용 크기가 수 MB 수준이라 스트리밍 대비 이득이 크지 않고 검증이 단순해진다).
 * @param originalFilename 원본 파일명. 저장 키에는 쓰지 않고 로그/표시용으로만 쓴다.
 */
public record FileUploadCommand(

    byte[] content,

    String originalFilename

) {

    public long size() {
        return content == null ? 0L : content.length;
    }
}
