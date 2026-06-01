package com.artifact.diagnosis.patient;

import com.artifact.diagnosis.visit.VisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * 환자 등록/조회 서비스.
 *
 * 학기 1 범위:
 *   - 신규 등록 (중복 검사 없음. 같은 사람이 두 번 등록되면 별도 레코드.)
 *   - 단건 조회 (등록 직후 폼 검증용)
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PatientService {

    private final PatientRepository patientRepository;
    private final VisitRepository visitRepository;

    /** 환자 등록 → 저장된 엔티티를 응답 DTO 로 변환해서 반환. */
    public PatientResponse register(PatientCreateRequest req) {
        if (req.phone() != null && !req.phone().isBlank()) {
            Optional<Patient> existing = patientRepository
                    .findFirstByNameAndPhone(req.name().trim(), req.phone().trim());
            if (existing.isPresent()) {
                return PatientResponse.from(existing.get());
            }
        }
        
        Patient saved = patientRepository.save(
                Patient.builder()
                        .name(req.name())
                        .birthDate(req.birthDate())
                        .gender(req.gender())
                        .phone(req.phone())
                        .memo(req.memo())
                        .build()
        );
        return PatientResponse.from(saved);
    }

    /** 단건 조회. 없으면 NoSuchElementException → 글로벌 핸들러가 404 응답. */
    @Transactional(readOnly = true)
    public PatientResponse findById(Long id) {
        return patientRepository.findById(id)
                .map(PatientResponse::from)
                .orElseThrow(() -> new NoSuchElementException("환자를 찾을 수 없습니다. id=" + id));
    }

    /** 이름 부분 검색. 조회 화면에서 환자를 찾을 때 사용. */
    @Transactional(readOnly = true)
    public List<PatientResponse> searchByName(String name) {
        return patientRepository.findByNameContaining(name)
                .stream()
                .map(PatientResponse::from)
                .toList();
    }

    /** 차트번호, 이름, 내원일 조건을 모두 만족하는 환자를 조회한다. */
    @Transactional(readOnly = true)
    public List<PatientResponse> search(Long patientId, String name, LocalDate visitDate) {
        Set<Long> candidateIds = null;

        if (patientId != null) {
            candidateIds = new LinkedHashSet<>();
            if (patientRepository.existsById(patientId)) {
                candidateIds.add(patientId);
            }
        }

        if (name != null && !name.isBlank()) {
            Set<Long> nameMatchedIds = new LinkedHashSet<>(
                    patientRepository.findByNameContaining(name.trim()).stream()
                            .map(Patient::getId)
                            .toList()
            );
            candidateIds = intersect(candidateIds, nameMatchedIds);
        }

        if (visitDate != null) {
            LocalDateTime start = visitDate.atStartOfDay();
            LocalDateTime end = visitDate.plusDays(1).atStartOfDay();
            Set<Long> dateMatchedIds = new LinkedHashSet<>(
                    visitRepository.findByVisitDateBetweenOrderByVisitDateAsc(start, end).stream()
                            .map(visit -> visit.getPatientId())
                            .toList()
            );
            candidateIds = intersect(candidateIds, dateMatchedIds);
        }

        if (candidateIds == null || candidateIds.isEmpty()) {
            return List.of();
        }

        return patientRepository.findAllById(candidateIds).stream()
                .map(PatientResponse::from)
                .toList();
    }

    private Set<Long> intersect(Set<Long> current, Set<Long> next) {
        if (current == null) {
            return next;
        }
        current.retainAll(next);
        return current;
    }
}
