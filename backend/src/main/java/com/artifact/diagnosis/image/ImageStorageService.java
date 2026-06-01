package com.artifact.diagnosis.image;

import org.springframework.web.multipart.MultipartFile;

/**
 * 이미지 저장 추상화 인터페이스.
 * 구현체: S3ImageStorageService (AWS S3), LocalImageStorageService (로컬 개발용)
 */
public interface ImageStorageService {

    /**
     * 이미지 파일을 저장하고 스토리지 키를 반환한다.
     * 반환된 키는 DB에 저장하며, URL 노출 없이 영구적으로 유효하다.
     */
    String upload(MultipartFile file);

    /**
     * raw 바이트를 지정 키로 저장한다. GradCAM 히트맵 등 프로그래매틱 업로드용.
     */
    String uploadBytes(String key, byte[] data, String contentType);

    /**
     * 스토리지 키로 이미지 바이트를 읽어 반환한다.
     * VisitImageService.getImageContent() 에서 브라우저로 스트리밍한다.
     */
    byte[] download(String key);
}
