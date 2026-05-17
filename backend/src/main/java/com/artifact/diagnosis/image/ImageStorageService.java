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
     * 이미지 파일을 저장하고 접근 가능한 URL을 반환한다.
     *
     * @param file multipart 파일
     * @return 저장된 이미지의 접근 URL (S3 구현체는 1시간 유효 Pre-signed URL)
     */
    String upload(MultipartFile file);

    /**
     * 저장된 이미지 URL로부터 원본 바이트를 읽어 반환한다.
     * S3 구현체는 URL에서 오브젝트 키를 파싱해 SDK로 직접 다운로드한다.
     *
     * @param imageUrl DB에 저장된 이미지 URL
     * @return 이미지 바이트 배열
     */
    byte[] download(String imageUrl);
}
