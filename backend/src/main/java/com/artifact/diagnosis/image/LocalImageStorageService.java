package com.artifact.diagnosis.image;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "image.storage.type", havingValue = "local")
public class LocalImageStorageService implements ImageStorageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp"
    );

    private final Path uploadRoot;

    public LocalImageStorageService(
            @Value("${image.local.upload-dir:/tmp/artifact-images}") String uploadDir) {
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @Override
    public String upload(MultipartFile file) {
        validate(file);
        String storedFilename = createStoredFilename(file.getOriginalFilename());
        Path target = uploadRoot.resolve(storedFilename).normalize();
        try {
            Files.createDirectories(uploadRoot);
            file.transferTo(target);
        } catch (IOException e) {
            throw new RuntimeException("로컬 이미지 저장에 실패했습니다.", e);
        }
        return storedFilename;
    }

    @Override
    public byte[] download(String key) {
        Path target = uploadRoot.resolve(key).normalize();
        if (!target.startsWith(uploadRoot)) {
            throw new IllegalArgumentException("잘못된 이미지 경로입니다.");
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new RuntimeException("로컬 이미지 파일을 읽는 중 오류가 발생했습니다.", e);
        }
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

    private String createStoredFilename(String originalFilename) {
        LocalDate today = LocalDate.now();
        return "%d-%02d-%02d-%s%s".formatted(
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
