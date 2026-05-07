package com.artifact.diagnosis.visit;

import com.artifact.diagnosis.image.ImageStorageService;
import com.artifact.diagnosis.patient.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 진료 접수(Visit) 비즈니스 로직.
 *
 * 학기 1 범위:
 *   - 접수 생성 (status=RECEIVED)
 *   - 단건/상태별 목록 조회
 *   - 이미지 첨부 (S3 업로드 + status→IMAGE_UPLOADED)
 */
@Service
@RequiredArgsConstructor
@Transactional
public class VisitService {

    private final VisitRepository visitRepository;
    private final PatientRepository patientRepository;
    private final ImageStorageService imageStorageService;

    /** 접수 생성. 환자 존재 여부를 먼저 확인해 명확한 404를 반환한다. */
    public VisitResponse create(VisitCreateRequest req) {
        patientRepository.findById(req.patientId())
                .orElseThrow(() -> new NoSuchElementException("환자를 찾을 수 없습니다. id=" + req.patientId()));

        Visit visit = Visit.builder()
                .patientId(req.patientId())
                .visitDate(LocalDateTime.now())
                .status(VisitStatus.RECEIVED)
                .build();

        return VisitResponse.from(visitRepository.save(visit));
    }

    /** 단건 조회. 없으면 NoSuchElementException → GlobalExceptionHandler가 404 반환. */
    @Transactional(readOnly = true)
    public VisitResponse findById(Long id) {
        return visitRepository.findById(id)
                .map(VisitResponse::from)
                .orElseThrow(() -> new NoSuchElementException("접수를 찾을 수 없습니다. id=" + id));
    }

    /** 상태별 목록 조회. 접수일 오름차순 — 대기열 화면에 먼저 온 환자가 위에 표시된다. */
    @Transactional(readOnly = true)
    public List<VisitResponse> findByStatus(VisitStatus status) {
        return visitRepository.findByStatusOrderByVisitDateAsc(status)
                .stream()
                .map(VisitResponse::from)
                .toList();
    }

    /**
     * 이미지 첨부. S3 업로드 후 Visit 상태를 IMAGE_UPLOADED로 전이한다.
     * RECEIVED 상태가 아니면 Visit.attachImage()가 IllegalStateException → 409.
     */
    public VisitResponse attachImage(Long visitId, MultipartFile file) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new NoSuchElementException("접수를 찾을 수 없습니다. id=" + visitId));

        String imageUrl = imageStorageService.upload(file);
        visit.attachImage(imageUrl); // Visit.java 도메인 메서드 — 상태 전이 포함

        return VisitResponse.from(visit); // Dirty Checking으로 자동 UPDATE
    }
}
