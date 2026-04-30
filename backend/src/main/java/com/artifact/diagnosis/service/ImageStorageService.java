package com.artifact.diagnosis.service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 이미지 저장 추상화 인터페이스.
 *
 * 학기 1: S3ImageStorageService (AWS S3 + Pre-signed URL)
 * 추후 다른 스토리지로 교체 시 이 인터페이스만 유지하고 구현체만 추가하면 됨.
 */
public interface ImageStorageService {

    /**
     * 이미지 파일을 저장하고 접근 가능한 URL을 반환한다.
     *
     * @param file multipart 파일
     * @return 저장된 이미지의 접근 URL (S3 구현체는 1시간 유효 Pre-signed URL)
     */
    String upload(MultipartFile file);
}
