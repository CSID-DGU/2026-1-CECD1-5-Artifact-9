package com.artifact.diagnosis.image;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * AWS S3 기반 이미지 저장 구현체.
 * 업로드: S3 PutObject → S3 key 반환 (DB 저장용)
 * 다운로드: S3 GetObject → byte[] 반환 → VisitImageService가 브라우저로 스트리밍
 */
@Service
@ConditionalOnProperty(name = "image.storage.type", havingValue = "s3", matchIfMissing = true)
public class S3ImageStorageService implements ImageStorageService {

    private static final Logger log = LoggerFactory.getLogger(S3ImageStorageService.class);
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp"
    );

    private final S3Client s3Client;
    private final String bucket;

    public S3ImageStorageService(
            S3Client s3Client,
            @Value("${cloud.aws.s3.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    @Override
    public String upload(MultipartFile file) {
        validate(file);
        String key = createObjectKey(file.getOriginalFilename());
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(file.getContentType())
                .contentLength(file.getSize())
                .build();
        try {
            s3Client.putObject(putRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException e) {
            log.error("Failed to read uploaded image file.", e);
            throw new RuntimeException("이미지 파일을 읽는 중 오류가 발생했습니다.", e);
        } catch (S3Exception e) {
            log.error("S3 upload failed. bucket={}, key={}, code={}, msg={}",
                    bucket, key, e.awsErrorDetails().errorCode(), e.awsErrorDetails().errorMessage(), e);
            throw new RuntimeException("S3 이미지 업로드에 실패했습니다: " + e.awsErrorDetails().errorMessage(), e);
        }
        return key;
    }

    @Override
    public byte[] download(String key) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(request);
        return response.asByteArray();
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("업로드할 이미지 파일이 비어 있습니다.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("지원하지 않는 이미지 형식입니다.");
        }
    }

    private String createObjectKey(String originalFilename) {
        LocalDate today = LocalDate.now();
        return "images/%d/%02d/%02d/%s%s".formatted(
                today.getYear(), today.getMonthValue(), today.getDayOfMonth(),
                UUID.randomUUID(), extensionOf(originalFilename));
    }

    private String extensionOf(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) return "";
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == originalFilename.length() - 1) return "";
        return originalFilename.substring(dotIndex).toLowerCase();
    }
}
