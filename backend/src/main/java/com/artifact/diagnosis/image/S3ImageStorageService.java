package com.artifact.diagnosis.image;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * AWS S3 기반 이미지 저장 구현체.
 *
 * 업로드 흐름: 파일 검증 → S3 PutObject → 1시간 유효 Pre-signed URL 반환.
 * S3 key 구조: images/YYYY/MM/DD/{UUID}{.ext}
 */
@Service
@ConditionalOnProperty(name = "image.storage.type", havingValue = "s3", matchIfMissing = true)
public class S3ImageStorageService implements ImageStorageService {

    private static final Logger log = LoggerFactory.getLogger(S3ImageStorageService.class);
    private static final Duration PRESIGNED_URL_DURATION = Duration.ofHours(1);
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp"
    );

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucket;

    public S3ImageStorageService(
            S3Client s3Client,
            S3Presigner s3Presigner,
            @Value("${cloud.aws.s3.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.bucket = bucket;
    }

    @Override
    public String upload(MultipartFile file) {
        validate(file);

        String key = createObjectKey(file.getOriginalFilename());
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(file.getContentType())
                .contentLength(file.getSize())
                .build();

        try {
            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException e) {
            log.error("Failed to read uploaded image file.", e);
            throw new RuntimeException("이미지 파일을 읽는 중 오류가 발생했습니다.", e);
        } catch (S3Exception e) {
            log.error("Failed to upload image to S3. bucket={}, key={}, awsStatus={}, awsErrorCode={}, awsMessage={}",
                    bucket, key, e.statusCode(), e.awsErrorDetails().errorCode(), e.awsErrorDetails().errorMessage(), e);
            throw new RuntimeException("S3 이미지 업로드에 실패했습니다: " + e.awsErrorDetails().errorMessage(), e);
        } catch (RuntimeException e) {
            log.error("Failed to upload image to S3. bucket={}, key={}", bucket, key, e);
            throw new RuntimeException("S3 이미지 업로드에 실패했습니다.", e);
        }

        return key;
    }

    @Override
    public String generatePresignedUrl(String key) {
        return createPresignedUrl(key);
    }

    /** null·빈파일·허용되지 않은 Content-Type 검사. 실패 시 IllegalArgumentException → 400. */
    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("업로드할 이미지 파일이 비어 있습니다.");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("지원하지 않는 이미지 형식입니다.");
        }
    }

    /** 날짜 경로 + UUID로 S3 오브젝트 키를 생성. 파일명 충돌 방지. */
    private String createObjectKey(String originalFilename) {
        LocalDate today = LocalDate.now();
        return "images/%d/%02d/%02d/%s%s".formatted(
                today.getYear(),
                today.getMonthValue(),
                today.getDayOfMonth(),
                UUID.randomUUID(),
                extensionOf(originalFilename));
    }

    /** 원본 파일명에서 확장자(.jpg 등)를 소문자로 추출. 없으면 빈 문자열. */
    private String extensionOf(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "";
        }

        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == originalFilename.length() - 1) {
            return "";
        }

        return originalFilename.substring(dotIndex).toLowerCase();
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

    /** S3 key로 1시간 유효한 GET Pre-signed URL을 생성해 반환. */
    private String createPresignedUrl(String key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(PRESIGNED_URL_DURATION)
                .getObjectRequest(getObjectRequest)
                .build();
        PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);

        return presignedRequest.url().toString();
    }
}
