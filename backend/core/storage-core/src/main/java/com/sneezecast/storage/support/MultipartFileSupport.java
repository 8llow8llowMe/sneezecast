package com.sneezecast.storage.support;

import com.sneezecast.storage.exception.StorageErrorCode;
import com.sneezecast.storage.exception.StorageException;
import com.sneezecast.storage.model.FileUploadCommand;
import java.io.IOException;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/**
 * web 타입인 {@code MultipartFile} 을 어댑터 경계에서 도메인 자료형으로 변환한다.
 * 애플리케이션 계층 시그니처에 {@code MultipartFile} 이 새어 나가지 않게 하는 것이 목적이다.
 */
public final class MultipartFileSupport {

    private MultipartFileSupport() {
    }

    public static FileUploadCommand toCommand(MultipartFile multipartFile) {
        if (multipartFile == null || multipartFile.isEmpty()) {
            throw new StorageException(StorageErrorCode.FILE_REQUIRED);
        }
        try {
            return new FileUploadCommand(multipartFile.getBytes(), multipartFile.getOriginalFilename());
        } catch (IOException exception) {
            throw new StorageException(StorageErrorCode.FILE_UPLOAD_FAILED, exception);
        }
    }

    public static List<FileUploadCommand> toCommands(List<MultipartFile> multipartFiles, int maxCount) {
        if (multipartFiles == null || multipartFiles.isEmpty()) {
            throw new StorageException(StorageErrorCode.FILE_REQUIRED);
        }
        if (multipartFiles.size() > maxCount) {
            throw new StorageException(StorageErrorCode.TOO_MANY_FILES);
        }
        return multipartFiles.stream().map(MultipartFileSupport::toCommand).toList();
    }
}
