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

/**
 * 로컬 파일시스템 기반 이미지 저장 구현체 (image.storage.type=local).
 * 업로드: 지정 디렉터리에 날짜/UUID 파일명으로 저장 → 상대 경로(key) 반환 (DB 저장용)
 * 다운로드: 저장된 파일을 byte[] 로 읽어 반환
 */
@Service
@ConditionalOnProperty(name = "image.storage.type", havingValue = "local")
public class LocalImageStorageService implements ImageStorageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp"
    );

    // ALLOWED_CONTENT_TYPES 와 짝을 맞춘 확장자 화이트리스트. validate() 가 이미 Content-Type 을
    // 이 넷으로 제한하고 있는데 정작 저장 파일명의 확장자는 원본 파일명에서 그대로 잘라 쓰고
    // 있었다 — 목록 밖 값이면 확장자를 붙이지 않는다(경로 조작 방지의 심층 방어 목적).
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp");

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
        // uploadBytes()/download() 와 동일한 가드. storedFilename 은 지금은 UUID 기반이라
        // 이 조건에 걸릴 일이 없지만, 그 전제가 나중에 바뀌어도 조용히 뚫리지 않도록 지킨다.
        if (!target.startsWith(uploadRoot)) {
            throw new IllegalArgumentException("잘못된 경로입니다.");
        }
        try {
            Files.createDirectories(uploadRoot);
            file.transferTo(target);
        } catch (IOException e) {
            throw new RuntimeException("로컬 이미지 저장에 실패했습니다.", e);
        }
        return storedFilename;
    }

    @Override
    public String uploadBytes(String key, byte[] data, String contentType) {
        Path target = uploadRoot.resolve(key).normalize();
        if (!target.startsWith(uploadRoot)) {
            throw new IllegalArgumentException("잘못된 경로입니다.");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        } catch (IOException e) {
            throw new RuntimeException("로컬 히트맵 저장 실패.", e);
        }
        return key;
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
        String extension = originalFilename.substring(dotIndex).toLowerCase();
        return ALLOWED_EXTENSIONS.contains(extension) ? extension : "";
    }
}
