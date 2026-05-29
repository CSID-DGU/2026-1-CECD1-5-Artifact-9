package com.artifact.diagnosis.visit;

import com.artifact.diagnosis.patient.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 진료 접수(Visit) 비즈니스 로직.
 *
 *   - 접수 생성 (status=RECEIVED)
 *   - 단건/상태별 목록 조회
 *   - 진료 시작 (status=IN_PROGRESS) — 이후 이미지 업로드 및 AI 분석 가능
 */
@Service
@RequiredArgsConstructor
@Transactional
public class VisitService {

    private final VisitRepository visitRepository;
    private final PatientRepository patientRepository;

    /** 접수 생성. 환자 존재 여부를 먼저 확인해 명확한 404를 반환한다. */
    public VisitResponse create(VisitCreateRequest req) {
        patientRepository.findById(req.patientId())
                .orElseThrow(() -> new NoSuchElementException("환자를 찾을 수 없습니다. id=" + req.patientId()));

        Visit visit = Visit.builder()
                .patientId(req.patientId())
                .visitDate(LocalDateTime.now())
                .status(VisitStatus.RECEIVED)
                .receptionMemo(req.receptionMemo())
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

    /** 환자별 진료 이력. 최신 순. */
    @Transactional(readOnly = true)
    public List<VisitResponse> findByPatientId(Long patientId) {
        return visitRepository.findByPatientIdOrderByVisitDateDesc(patientId)
                .stream()
                .map(VisitResponse::from)
                .toList();
    }

    /** 진료 시작. RECEIVED → IN_PROGRESS 전이. 이후 이미지 업로드 가능. */
    public VisitResponse startConsultation(Long id) {
        Visit visit = visitRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("접수를 찾을 수 없습니다. id=" + id));
        visit.startConsultation();
        return VisitResponse.from(visit);
    }

    /** 진단 확정. ANALYZED → DIAGNOSED 전이. 처방 작성 가능 상태로 변경. */
    public VisitResponse markDiagnosed(Long id) {
        Visit visit = visitRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("접수를 찾을 수 없습니다. id=" + id));
        visit.markDiagnosed();
        return VisitResponse.from(visit);
    }

    /** 진료 완료. PRESCRIBED → COMPLETED 전이. */
    public VisitResponse markCompleted(Long id) {
        Visit visit = visitRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("접수를 찾을 수 없습니다. id=" + id));
        visit.markCompleted();
        return VisitResponse.from(visit);
    }
}
