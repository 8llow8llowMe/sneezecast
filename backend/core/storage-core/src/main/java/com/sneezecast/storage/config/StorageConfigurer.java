package com.sneezecast.storage.config;

import com.sneezecast.storage.client.ObjectStorageClient;
import com.sneezecast.storage.init.StorageBucketInitializer;
import com.sneezecast.storage.properties.StorageProperties;
import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;

/**
 * 스토리지 빈 등록.
 *
 * <p>{@code @Configuration} 을 붙이지 않고 각 서비스의 {@code XxxServiceBeansConfig} 가
 * {@code @Import} 로 명시 등록하는 core 모듈 관례를 따른다.
 */
public class StorageConfigurer {

    @Bean
    public MinioClient minioClient(StorageProperties storageProperties) {
        return MinioClient.builder()
            .endpoint(storageProperties.endpoint())
            .credentials(storageProperties.accessKey(), storageProperties.secretKey())
            .build();
    }

    @Bean
    public ObjectStorageClient objectStorageClient(MinioClient minioClient, StorageProperties storageProperties) {
        return new ObjectStorageClient(minioClient, storageProperties);
    }

    @Bean
    public StorageBucketInitializer storageBucketInitializer(MinioClient minioClient, StorageProperties storageProperties) {
        return new StorageBucketInitializer(minioClient, storageProperties);
    }
}
