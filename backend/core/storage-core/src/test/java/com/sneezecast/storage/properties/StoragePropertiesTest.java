package com.sneezecast.storage.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StoragePropertiesTest {

    private StorageProperties properties(String publicUrl, Long maxFileBytes) {
        return new StorageProperties("http://192.168.0.12:9000", publicUrl, "sneezecast", "key", "secret", maxFileBytes);
    }

    @Test
    void toPublicUrl_buildsPathStyleUrl() {
        StorageProperties properties = properties("https://minio.sneezecast.com", null);

        assertThat(properties.toPublicUrl("members/profiles/1/2026/08/abc.png"))
            .isEqualTo("https://minio.sneezecast.com/sneezecast/members/profiles/1/2026/08/abc.png");
    }

    @Test
    void toPublicUrl_handlesTrailingSlashAndNullKey() {
        StorageProperties properties = properties("https://minio.sneezecast.com/", null);

        assertThat(properties.toPublicUrl("a/b.png")).isEqualTo("https://minio.sneezecast.com/sneezecast/a/b.png");
        assertThat(properties.toPublicUrl(null)).isNull();
        assertThat(properties.toPublicUrl(" ")).isNull();
    }

    @Test
    void normalizedMaxFileBytes_fallsBackToDefaultWhenUnset() {
        assertThat(properties("https://x", null).normalizedMaxFileBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(properties("https://x", 0L).normalizedMaxFileBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(properties("https://x", 1024L).normalizedMaxFileBytes()).isEqualTo(1024L);
    }
}
