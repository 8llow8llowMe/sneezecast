package com.sneezecast.storage.init;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StorageBucketInitializerTest {

    @Test
    void publicReadPolicy_allowsAnonymousGetObjectOnlyUnderUploadPrefixes() {
        String policy = StorageBucketInitializer.publicReadPolicy("sneezecast");

        assertThat(policy).contains("\"Action\": [\"s3:GetObject\"]");
        assertThat(policy).contains("\"Resource\": [\"arn:aws:s3:::sneezecast/members/profiles/*\"]");
        assertThat(policy).as("버킷 전체 · 목록 조회는 열지 않는다").doesNotContain("arn:aws:s3:::sneezecast/*").doesNotContain("s3:ListBucket");
    }
}
