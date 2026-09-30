package com.sneezecast.storage.client;

import com.sneezecast.storage.exception.StorageErrorCode;
import com.sneezecast.storage.exception.StorageException;
import com.sneezecast.storage.model.FileUploadCommand;
import com.sneezecast.storage.model.ImageFileType;
import com.sneezecast.storage.model.StorageDomain;
import com.sneezecast.storage.model.StoredObject;
import com.sneezecast.storage.properties.StorageProperties;
import com.sneezecast.storage.util.ObjectKeyFactory;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 오브젝트 스토리지 접근 공용 클라이언트.
 *
 * <p>업로드/삭제/URL 조립을 한 곳에 모아 서비스마다 같은 코드를 복사하지 않게 한다.
 * 검증(크기·형식)도 여기서 수행하므로 호출부가 빠뜨릴 수 없다.
 */
@Slf4j
@RequiredArgsConstructor
public class ObjectStorageClient {

    private final MinioClient minioClient;
    private final StorageProperties storageProperties;

    /**
     * 이미지 파일을 업로드한다. 검증 실패 시 400 계열 {@link StorageException} 을 던진다.
     *
     * <p>주의: 이 메서드는 원격 I/O 이므로 {@code @Transactional} 블록 안에서 호출하지 않는다.
     * (DB 커넥션을 잡은 채 대기하게 되고, 커밋 실패 시 고아 객체가 남는다)
     */
    public StoredObject uploadImage(StorageDomain domain, long memberId, FileUploadCommand command) {
        byte[] content = requireContent(command);
        if (content.length > storageProperties.normalizedMaxFileBytes()) {
            throw new StorageException(StorageErrorCode.FILE_TOO_LARGE);
        }

        // 확장자·클라이언트 Content-Type 은 신뢰하지 않고 매직 바이트로만 판정한다.
        ImageFileType imageFileType = ImageFileType.detect(content)
            .orElseThrow(() -> new StorageException(StorageErrorCode.UNSUPPORTED_FILE_TYPE));

        String objectKey = ObjectKeyFactory.generate(domain, memberId, imageFileType);
        try (ByteArrayInputStream stream = new ByteArrayInputStream(content)) {
            minioClient.putObject(PutObjectArgs.builder()
                .bucket(storageProperties.bucket())
                .object(objectKey)
                .stream(stream, content.length, -1)
                .contentType(imageFileType.contentType())
                .build());
        } catch (Exception exception) {
            log.error("파일 업로드에 실패했습니다. domain={} memberId={} originalFilename={}",
                domain, memberId, command.originalFilename(), exception);
            throw new StorageException(StorageErrorCode.FILE_UPLOAD_FAILED, exception);
        }

        return new StoredObject(
            objectKey, storageProperties.toPublicUrl(objectKey), imageFileType.contentType(), content.length
        );
    }

    /**
     * 객체를 삭제한다. 실패해도 예외를 던지지 않는다.
     *
     * <p>삭제 실패로 본 요청(회원 프로필 수정 등)을 되돌리는 것은 사용자에게 손해다.
     * 남은 객체는 고아 정리 배치가 회수한다.
     */
    public void deleteQuietly(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(storageProperties.bucket())
                .object(objectKey)
                .build());
        } catch (Exception exception) {
            log.warn("파일 삭제에 실패해 고아 객체로 남깁니다. objectKey={} reason={}", objectKey, exception.getMessage());
        }
    }

    /**
     * 트랜잭션 커밋 후에 객체를 삭제한다. 롤백되면 삭제하지 않는다.
     *
     * <p>"DB 는 롤백됐는데 파일은 이미 지워져 깨진 데이터가 되는" 상황을 막는다.
     * 트랜잭션이 없으면 즉시 삭제한다.
     */
    public void deleteAfterCommit(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteQuietly(objectKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteQuietly(objectKey);
            }
        });
    }

    public void deleteAllAfterCommit(Collection<String> objectKeys) {
        if (objectKeys == null) {
            return;
        }
        objectKeys.forEach(this::deleteAfterCommit);
    }

    /**
     * prefix 아래에서 {@code minimumAge} 보다 오래된 객체 키를 나열한다. 고아 객체 회수 배치용이다.
     *
     * <p>방금 업로드해 아직 DB 에 연결되지 않은 객체를 지우지 않도록 <b>나이 조건이 필수</b>다.
     * 업로드 → 연결 사이의 시간차보다 충분히 큰 값을 넘겨야 한다.
     *
     * <p>조회 실패 시 예외를 던지지 않고 빈 목록을 반환한다. 정리 배치가 실패해도 서비스 기능에는
     * 영향이 없어야 하고, 다음 주기에 다시 시도하면 되기 때문이다.
     */
    public List<String> listObjectKeysOlderThan(String prefix, Duration minimumAge) {
        List<String> objectKeys = new ArrayList<>();
        ZonedDateTime threshold = ZonedDateTime.now().minus(minimumAge);
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(storageProperties.bucket())
                .prefix(prefix)
                .recursive(true)
                .build());
            for (Result<Item> result : results) {
                Item item = result.get();
                if (item.isDir()) {
                    continue;
                }
                ZonedDateTime lastModified = item.lastModified();
                if (lastModified == null || lastModified.isBefore(threshold)) {
                    objectKeys.add(item.objectName());
                }
            }
        } catch (Exception exception) {
            log.warn("객체 목록 조회에 실패해 이번 주기 정리를 건너뜁니다. prefix={} reason={}", prefix, exception.getMessage());
            return List.of();
        }
        return objectKeys;
    }

    public String toPublicUrl(String objectKey) {
        return storageProperties.toPublicUrl(objectKey);
    }

    private byte[] requireContent(FileUploadCommand command) {
        return Optional.ofNullable(command)
            .map(FileUploadCommand::content)
            .filter(content -> content.length > 0)
            .orElseThrow(() -> new StorageException(StorageErrorCode.FILE_REQUIRED));
    }
}
