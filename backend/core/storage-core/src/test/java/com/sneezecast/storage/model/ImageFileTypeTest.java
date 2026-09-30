package com.sneezecast.storage.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ImageFileTypeTest {

    @Test
    void detect_recognizesSupportedImagesByMagicBytes() {
        assertThat(ImageFileType.detect(padded(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})))
            .contains(ImageFileType.JPEG);
        assertThat(ImageFileType.detect(padded(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})))
            .contains(ImageFileType.PNG);
        assertThat(ImageFileType.detect(padded("GIF89a".getBytes(StandardCharsets.US_ASCII))))
            .contains(ImageFileType.GIF);
    }

    @Test
    void detect_recognizesWebpOnlyWhenRiffContainerHasWebpMarker() {
        byte[] webp = "RIFF____WEBPVP8 ".getBytes(StandardCharsets.US_ASCII);
        assertThat(ImageFileType.detect(webp)).contains(ImageFileType.WEBP);

        // RIFF 지만 WAVE(오디오)면 이미지가 아니다
        byte[] wave = "RIFF____WAVEfmt ".getBytes(StandardCharsets.US_ASCII);
        assertThat(ImageFileType.detect(wave)).isEmpty();
    }

    @Test
    void detect_rejectsDisguisedNonImages() {
        // 확장자/Content-Type 을 이미지로 위장해도 내용이 HTML/스크립트면 걸러진다 (stored XSS 방지)
        assertThat(ImageFileType.detect("<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(ImageFileType.detect("<svg onload=alert(1)></svg>".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(ImageFileType.detect(new byte[] {0x4D, 0x5A, 0x00, 0x00, 0, 0, 0, 0, 0, 0, 0, 0})).isEmpty(); // exe
    }

    @Test
    void detect_returnsEmptyForNullOrTooShortContent() {
        assertThat(ImageFileType.detect(null)).isEqualTo(Optional.empty());
        assertThat(ImageFileType.detect(new byte[] {(byte) 0xFF, (byte) 0xD8})).isEmpty();
    }

    /** 매직 바이트 판정은 최소 12바이트를 요구하므로 뒤를 0으로 채운다. */
    private byte[] padded(byte[] signature) {
        byte[] content = new byte[16];
        System.arraycopy(signature, 0, content, 0, signature.length);
        return content;
    }
}
