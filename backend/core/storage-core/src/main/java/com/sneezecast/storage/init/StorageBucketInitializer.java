package com.sneezecast.storage.init;

import com.sneezecast.storage.model.StorageDomain;
import com.sneezecast.storage.properties.StorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketPolicyArgs;
import java.util.Arrays;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * 버킷과 공개 읽기 정책을 기동 시 보장한다.
 *
 * <p>공개 URL 로 이미지를 서빙하는 구조라 anonymous read 정책이 필요한데, 이를 콘솔 수동 설정에 맡기면
 * 서버를 재구축할 때 조용히 누락된다. 코드로 명시해 인프라와의 계약을 드러낸다.
 *
 * <p>쓰기는 정책에 포함하지 않는다(자격 증명 필요). 읽기만 열어 둔다. 범위는 버킷 전체가 아니라 {@link StorageDomain} prefix 아래 객체의
 * {@code s3:GetObject} 뿐이다 — 목록 조회({@code s3:ListBucket})는 열지 않아 키를 모르면 객체를 찾을 수 없고, 다른 용도로 같은 버킷에 둔 객체가
 * 공개되지 않는다. 기동할 때마다 이 정책으로 덮어쓰므로 콘솔 · {@code mc} 로 바꾼 정책은 다음 기동에 되돌아간다.
 */
@Slf4j
@RequiredArgsConstructor
public class StorageBucketInitializer {

    private final MinioClient minioClient;
    private final StorageProperties storageProperties;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeBucket() {
        String bucket = storageProperties.bucket();
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("스토리지 버킷을 생성했습니다. bucket={}", bucket);
            }
            minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                .bucket(bucket)
                .config(publicReadPolicy(bucket))
                .build());
        } catch (Exception exception) {
            // 스토리지는 부가 기능이므로 기동을 막지 않는다. 업로드 시점에 STORAGE_004 로 드러난다.
            log.error("스토리지 버킷 초기화에 실패했습니다. 업로드 기능이 동작하지 않을 수 있습니다. bucket={}", bucket, exception);
        }
    }

    /** 익명 읽기 정책 — 업로드 도메인 prefix 마다 {@code arn:aws:s3:::{bucket}/{prefix}/*} 의 GetObject 만 허용한다. */
    static String publicReadPolicy(String bucket) {
        String resources = Arrays.stream(StorageDomain.values())
            .map(domain -> "\"arn:aws:s3:::%s/%s/*\"".formatted(bucket, domain.prefix()))
            .collect(Collectors.joining(", "));
        return """
            {
              "Version": "2012-10-17",
              "Statement": [
                {
                  "Effect": "Allow",
                  "Principal": {"AWS": ["*"]},
                  "Action": ["s3:GetObject"],
                  "Resource": [%s]
                }
              ]
            }""".formatted(resources);
    }
}
