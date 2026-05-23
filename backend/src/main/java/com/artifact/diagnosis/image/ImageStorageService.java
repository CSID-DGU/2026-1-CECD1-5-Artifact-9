package com.artifact.diagnosis.image;

import org.springframework.web.multipart.MultipartFile;

/**
 * 이미지 저장 추상화 인터페이스.
 *
 * 학기 1: S3ImageStorageService (AWS S3 + Pre-signed URL)
 * 추후 다른 스토리지로 교체 시 이 인터페이스만 유지하고 구현체만 추가하면 됨.
 */
public interface ImageStorageService {

    /**
     * 이미지 파일을 저장하고 스토리지 키를 반환한다.
     * 반환된 키는 DB에 저장하며, URL 노출 없이 영구적으로 유효하다.
     *
     * @param file multipart 파일
     * @return 스토리지 키 (S3: objects key, 로컬: 파일명)
     */
    String upload(MultipartFile file);
<<<<<<< Updated upstream
=======

    /**
     * 스토리지 키로 일시적인 접근 URL을 생성한다.
     * 조회 시점마다 호출해야 하며, DB에 저장하면 안 된다.
     *
     * @param key DB에 저장된 스토리지 키
     * @return 접근 가능한 URL (S3: Pre-signed URL, 로컬: API 경로)
     */
    String generatePresignedUrl(String key);

    /**
     * 스토리지 키로 이미지 바이트를 읽어 반환한다.
     *
     * @param key DB에 저장된 스토리지 키
     * @return 이미지 바이트 배열
     */
    byte[] download(String key);
>>>>>>> Stashed changes
}
