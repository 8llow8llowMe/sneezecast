package com.sneezecast.storage.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.storage.exception.StorageErrorCode;
import com.sneezecast.storage.exception.StorageException;
import com.sneezecast.storage.model.ImageFileType;
import com.sneezecast.storage.model.StorageDomain;
import org.junit.jupiter.api.Test;

class ObjectKeyFactoryTest {

    @Test
    void generate_buildsServerControlledKeyWithoutOriginalFilename() {
        String key = ObjectKeyFactory.generate(StorageDomain.MEMBER_PROFILE, 42L, ImageFileType.PNG);

        assertThat(key).matches("^members/profiles/42/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.png$");
    }

    @Test
    void validateOwnership_acceptsOwnKey() {
        String key = ObjectKeyFactory.generate(StorageDomain.MEMBER_PROFILE, 7L, ImageFileType.JPEG);

        assertThatCode(() -> ObjectKeyFactory.validateOwnership(key, StorageDomain.MEMBER_PROFILE, 7L))
            .doesNotThrowAnyException();
    }

    @Test
    void validateOwnership_rejectsOtherMembersKey() {
        String othersKey = ObjectKeyFactory.generate(StorageDomain.MEMBER_PROFILE, 999L, ImageFileType.JPEG);

        assertThatThrownBy(() -> ObjectKeyFactory.validateOwnership(othersKey, StorageDomain.MEMBER_PROFILE, 7L))
            .isInstanceOf(StorageException.class)
            .extracting(exception -> ((StorageException) exception).getErrorCode())
            .isEqualTo(StorageErrorCode.FORBIDDEN_OBJECT_KEY);
    }

    @Test
    void validateOwnership_rejectsKeyOutsideDomainPrefix() {
        // 도메인이 하나뿐이라 generate 로는 다른 prefix 키를 만들 수 없다. 형식은 맞지만 prefix 가 다른 키를 직접 만든다.
        String ownKey = ObjectKeyFactory.generate(StorageDomain.MEMBER_PROFILE, 7L, ImageFileType.JPEG);
        String foreignKey = ownKey.replaceFirst("^" + StorageDomain.MEMBER_PROFILE.prefix(), "others/files");

        assertThatThrownBy(() -> ObjectKeyFactory.validateOwnership(foreignKey, StorageDomain.MEMBER_PROFILE, 7L))
            .isInstanceOf(StorageException.class)
            .extracting(exception -> ((StorageException) exception).getErrorCode())
            .isEqualTo(StorageErrorCode.INVALID_OBJECT_KEY);
    }

    @Test
    void validateOwnership_rejectsPathTraversalAndMalformedKeys() {
        for (String malformed : new String[] {
            null,
            "",
            "members/profiles/7/2026/08/../../../etc/passwd",
            "members/profiles/7/2026/08/not-a-uuid.png",
            "../members/profiles/7/2026/08/00000000-0000-0000-0000-000000000000.png",
            "https://minio.example.com/bucket/members/profiles/7/2026/08/00000000-0000-0000-0000-000000000000.png"
        }) {
            assertThatThrownBy(() -> ObjectKeyFactory.validateOwnership(malformed, StorageDomain.MEMBER_PROFILE, 7L))
                .isInstanceOf(StorageException.class);
        }
    }
}
