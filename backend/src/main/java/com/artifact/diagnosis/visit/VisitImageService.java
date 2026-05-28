package com.artifact.diagnosis.visit;

import com.artifact.diagnosis.image.ImageStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 내원 이미지 업로드/조회 서비스.
 * IN_PROGRESS 상태인 visit에만 업로드 허용.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class VisitImageService {

    private final VisitRepository visitRepository;
    private final VisitImageRepository visitImageRepository;
    private final ImageStorageService imageStorageService;

    /**
     * 이미지 1장 업로드. 반복 호출로 여러 장 추가 가능.
     * IN_PROGRESS 또는 ANALYZED 상태에서만 허용 — 재진료 시 추가 사진 가능.
     */
    public VisitImageResponse upload(Long visitId, MultipartFile file) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new NoSuchElementException("접수를 찾을 수 없습니다. id=" + visitId));

        if (visit.getStatus() != VisitStatus.IN_PROGRESS
                && visit.getStatus() != VisitStatus.ANALYZED) {
            throw new IllegalStateException(
                    "이미지 업로드는 진료중(IN_PROGRESS) 또는 분석완료(ANALYZED) 상태에서만 가능합니다. 현재 상태: "
                    + visit.getStatus());
        }

        String key = imageStorageService.upload(file);
        VisitImage image = VisitImage.builder()
                .visitId(visitId)
                .imageUrl(key)
                .uploadedAt(LocalDateTime.now())
                .build();

        VisitImage saved = visitImageRepository.save(image);
        return new VisitImageResponse(
                saved.getId(),
                saved.getVisitId(),
                imageStorageService.generatePresignedUrl(saved.getImageUrl()),
                saved.getUploadedAt()
        );
    }

    /** 특정 접수의 이미지 전체 목록 조회. 조회 시점에 presigned URL을 새로 발급한다. */
    @Transactional(readOnly = true)
    public List<VisitImageResponse> findByVisitId(Long visitId) {
        if (!visitRepository.existsById(visitId)) {
            throw new NoSuchElementException("접수를 찾을 수 없습니다. id=" + visitId);
        }
        return visitImageRepository.findByVisitIdOrderByUploadedAtAsc(visitId)
                .stream()
                .map(img -> new VisitImageResponse(
                        img.getId(),
                        img.getVisitId(),
                        imageStorageService.generatePresignedUrl(img.getImageUrl()),
                        img.getUploadedAt()
                ))
                .toList();
    }
}
