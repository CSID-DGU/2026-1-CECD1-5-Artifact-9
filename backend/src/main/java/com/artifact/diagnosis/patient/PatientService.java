package com.artifact.diagnosis.patient;

import com.artifact.diagnosis.visit.VisitRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * 환자 등록/조회 서비스.
 *
 *   - 신규 등록 (이름+전화번호 일치 시 기존 환자 반환, 아니면 신규 생성)
 *   - 단건 / 이름 검색 / 차트번호·이름·내원일 복합 조건 조회
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PatientService {

    private final PatientRepository patientRepository;
    private final VisitRepository visitRepository;

    /**
     * 신규 환자 등록. 검색 후 일치하는 환자가 없을 때만 호출한다.
     * 기존 dedup 로직 제거 — 검색 → 선택 흐름이 중복 방지를 담당한다.
     */
    public PatientResponse register(PatientCreateRequest req) {
        Patient saved = patientRepository.save(
                Patient.builder()
                        .name(req.name().trim())
                        .birthDate(req.birthDate())
                        .gender(req.gender())
                        .phone(req.phone() != null ? req.phone().trim() : null)
                        .memo(req.memo())
                        .build()
        );
        return PatientResponse.from(saved);
    }

    /**
     * 접수 화면용 환자 검색.
     * 이름 부분일치(필수) + 생년월일·성별·전화번호 정확매칭(선택 AND 조건).
     * 각 환자의 최종진료일을 포함해 동명이인 구분 정보를 반환한다.
     */
    @Transactional(readOnly = true)
    public List<PatientSearchResponse> searchForReception(
            String name, LocalDate birthDate, Gender gender, String phone) {

        Specification<Patient> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.like(root.get("name"), "%" + name + "%"));
            if (birthDate != null) {
                predicates.add(cb.equal(root.get("birthDate"), birthDate));
            }
            if (gender != null) {
                predicates.add(cb.equal(root.get("gender"), gender));
            }
            if (phone != null && !phone.isBlank()) {
                predicates.add(cb.equal(root.get("phone"), phone.trim()));
            }
            if (query != null) {
                query.orderBy(cb.asc(root.get("name")));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return patientRepository.findAll(spec).stream()
                .map(p -> {
                    LocalDate lastVisitDate = visitRepository
                            .findTopByPatientIdOrderByVisitDateDesc(p.getId())
                            .map(v -> v.getVisitDate().toLocalDate())
                            .orElse(null);
                    return PatientSearchResponse.from(p, lastVisitDate);
                })
                .toList();
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
