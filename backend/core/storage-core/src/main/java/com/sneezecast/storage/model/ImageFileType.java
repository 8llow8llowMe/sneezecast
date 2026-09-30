package com.sneezecast.storage.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * 허용 이미지 형식.
 *
 * <p>확장자나 클라이언트가 보낸 Content-Type 은 위조할 수 있으므로 <b>매직 바이트로만</b> 판정한다.
 * 판정 결과의 contentType 을 오브젝트 메타데이터로 쓰기 때문에,
 * text/html 이나 image/svg+xml 을 이미지로 위장해 올려도 공개 URL 에서 스크립트로 실행되지 않는다.
 */
public enum ImageFileType {

    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    GIF("gif", "image/gif"),
    WEBP("webp", "image/webp");

    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] GIF_SIGNATURE = {0x47, 0x49, 0x46, 0x38};
    private static final byte[] RIFF_SIGNATURE = {0x52, 0x49, 0x46, 0x46};
    private static final byte[] WEBP_SIGNATURE = {0x57, 0x45, 0x42, 0x50};

    private final String extension;
    private final String contentType;

    ImageFileType(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /**
     * 파일 앞부분 바이트로 이미지 형식을 판정한다. 알 수 없으면 empty.
     */
    public static Optional<ImageFileType> detect(byte[] content) {
        if (content == null || content.length < 12) {
            return Optional.empty();
        }
        if (startsWith(content, JPEG_SIGNATURE)) {
            return Optional.of(JPEG);
        }
        if (startsWith(content, PNG_SIGNATURE)) {
            return Optional.of(PNG);
        }
        if (startsWith(content, GIF_SIGNATURE)) {
            return Optional.of(GIF);
        }
        // WEBP 는 RIFF 컨테이너라 0~3=RIFF, 8~11=WEBP 두 곳을 함께 확인해야 한다.
        if (startsWith(content, RIFF_SIGNATURE)
            && Arrays.equals(Arrays.copyOfRange(content, 8, 12), WEBP_SIGNATURE)) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        return Arrays.equals(Arrays.copyOf(content, signature.length), signature);
    }
}
