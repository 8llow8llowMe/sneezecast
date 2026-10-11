package com.sneezecast.storage.config;

import com.sneezecast.storage.client.ObjectStorageClient;
import com.sneezecast.storage.init.StorageBucketInitializer;
import com.sneezecast.storage.properties.StorageProperties;
import io.minio.MinioClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;

/**
 * 스토리지 빈 등록.
 *
 * <p>{@code @Configuration} 을 붙이지 않고 각 서비스의 {@code XxxServiceBeansConfig} 가
 * {@code @Import} 로 명시 등록하는 core 모듈 관례를 따른다.
 */
public class StorageConfigurer {

    /**
     * MinIO 호출 timeout. SDK 기본값(OkHttp 각 5분)이면 저장소가 멈췄을 때 업로드 · 삭제 요청이 게이트웨이 timeout 을 넘겨 매달린다. 같은 사설망의
     * MinIO 라 접속은 짧게, 쓰기 · 읽기는 상한 크기(수 MB) 업로드가 끝날 만큼 둔다.
     */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration WRITE_TIMEOUT = Duration.ofSeconds(10);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public MinioClient minioClient(StorageProperties storageProperties) {
        MinioClient minioClient = MinioClient.builder()
            .endpoint(storageProperties.endpoint())
            .credentials(storageProperties.accessKey(), storageProperties.secretKey())
            .build();
        // SDK 가 쓰는 OkHttpClient 에 timeout 을 건다(MinIO API — OkHttp 버전에 묶이지 않는다). 인자 순서는 connect · write · read, 단위는 밀리초다.
        minioClient.setTimeout(CONNECT_TIMEOUT.toMillis(), WRITE_TIMEOUT.toMillis(), READ_TIMEOUT.toMillis());
        return minioClient;
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
