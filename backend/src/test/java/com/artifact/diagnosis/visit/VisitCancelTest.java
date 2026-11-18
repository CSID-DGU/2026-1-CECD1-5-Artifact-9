package com.artifact.diagnosis.visit;

import com.artifact.diagnosis.common.jwt.JwtUtil;
import com.artifact.diagnosis.member.MemberRole;
import com.artifact.diagnosis.patient.Gender;
import com.artifact.diagnosis.patient.Patient;
import com.artifact.diagnosis.patient.PatientRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 접수 취소(DELETE /api/v1/visits/{id}) — 처방 저장 전까지 취소되고, 진료를 시작한 접수는 진료실 직책만 취소한다.
 *
 * 부스에서 진료를 시작해 놓고 방문자가 떠난 접수가 진료 대기 목록에 며칠씩 '진료중'으로 남았다. 예전에는 접수 대기만
 * 취소돼 이런 접수를 정리할 방법이 없었다.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:artifact_visit_cancel;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // 마이그레이션 SQL 은 MySQL 방언이라 H2 에서 죽는다 — 스키마는 엔티티에서 만든다.
        "spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "cloud.aws.credentials.access-key=test-access-key",
        "cloud.aws.credentials.secret-key=test-secret-key",
        "cloud.aws.s3.bucket=test-bucket",
        "image.storage.type=local",
        "jwt.secret=test-only-dummy-signing-key-not-used-anywhere-else-0123456789",
        "gemini.api.key=",
        "anthropic.api.key=",
        "print.agent.enabled=false",
        "fastapi.internal-secret=test-only-internal-secret"
})
class VisitCancelTest {

    private static final AtomicInteger TOKEN_SEQ = new AtomicInteger();

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired PatientRepository patientRepository;
    @Autowired VisitRepository visitRepository;

    /** 진료를 시작했지만 처방 전인 접수 — 진료실(간호사·의사·관리자)은 취소한다. */
    @ParameterizedTest
    @EnumSource(value = VisitStatus.class, names = {"IN_PROGRESS", "ANALYZED", "DIAGNOSED"})
    void clinicCancelsAStartedVisit(VisitStatus status) throws Exception {
        for (MemberRole role : new MemberRole[]{MemberRole.NURSE, MemberRole.DOCTOR, MemberRole.ADMIN}) {
            Visit visit = visitIn(status);
            cancel(visit, role, status().isOk());
            assertThat(statusOf(visit)).isEqualTo(VisitStatus.CANCELLED);
        }
    }

    /** 접수 직원은 진료를 시작한 접수를 취소하지 못한다 — 의사가 보고 있는 진료를 창구에서 지우지 않게. */
    @ParameterizedTest
    @EnumSource(value = VisitStatus.class, names = {"IN_PROGRESS", "ANALYZED", "DIAGNOSED"})
    void staffCannotCancelAStartedVisit(VisitStatus status) throws Exception {
        Visit visit = visitIn(status);
        cancel(visit, MemberRole.STAFF, status().isForbidden());
        assertThat(statusOf(visit)).isEqualTo(status);
    }

    /** 접수 대기는 전처럼 모든 직책이 취소한다. */
    @Test
    void anyoneCancelsAReceivedVisit() throws Exception {
        for (MemberRole role : MemberRole.values()) {
            Visit visit = visitIn(VisitStatus.RECEIVED);
            cancel(visit, role, status().isOk());
            assertThat(statusOf(visit)).isEqualTo(VisitStatus.CANCELLED);
        }
    }

    /** 처방을 저장한 진료기록은 취소로 덮지 않는다. */
    @ParameterizedTest
    @EnumSource(value = VisitStatus.class, names = {"PRESCRIBED", "COMPLETED"})
    void prescribedVisitCannotBeCancelled(VisitStatus status) throws Exception {
        Visit visit = visitIn(status);
        cancel(visit, MemberRole.DOCTOR, status().isConflict());
        assertThat(statusOf(visit)).isEqualTo(status);
    }

    /** AI 분석 결과를 저장하는 쪽과 부딪치지 않게, 분석 중에는 막는다. 끝난 뒤(ANALYZED)에는 취소된다 — 위 테스트. */
    @Test
    void visitUnderAnalysisCannotBeCancelled() throws Exception {
        Visit visit = visitIn(VisitStatus.ANALYZING);
        cancel(visit, MemberRole.DOCTOR, status().isConflict());
        assertThat(statusOf(visit)).isEqualTo(VisitStatus.ANALYZING);
    }

    @Test
    void cancellingTwiceIsAConflict() throws Exception {
        Visit visit = visitIn(VisitStatus.IN_PROGRESS);
        cancel(visit, MemberRole.DOCTOR, status().isOk());
        cancel(visit, MemberRole.DOCTOR, status().isConflict());
    }

    @Test
    void cancelledVisitLeavesTheClinicQueue() throws Exception {
        Visit visit = visitIn(VisitStatus.IN_PROGRESS);
        cancel(visit, MemberRole.DOCTOR, status().isOk());
        assertThat(visitRepository.findByStatusOrderByVisitDateAsc(VisitStatus.IN_PROGRESS))
                .extracting(Visit::getId).doesNotContain(visit.getId());
    }

    private void cancel(Visit visit, MemberRole role, ResultMatcher expected) throws Exception {
        var result = mockMvc.perform(delete("/api/v1/visits/{id}", visit.getId())
                        .header("Authorization", "Bearer " + jwtUtil.generate(1L, "cancel-" + role.name().toLowerCase(), role.name())))
                .andExpect(expected);
        // 성공이면 바뀐 상태를, 실패면 화면이 그대로 보여 줄 사유를 돌려준다.
        if (result.andReturn().getResponse().getStatus() >= 400) {
            result.andExpect(jsonPath("$.message").isNotEmpty());
        } else {
            result.andExpect(jsonPath("$.status").value("CANCELLED"));
        }
    }

    private VisitStatus statusOf(Visit visit) {
        return visitRepository.findById(visit.getId()).orElseThrow().getStatus();
    }

    private Visit visitIn(VisitStatus status) {
        Patient patient = patientRepository.save(Patient.builder()
                .name("취소테스트").birthDate(LocalDate.of(1990, 1, 1)).gender(Gender.F).build());
        return visitRepository.save(Visit.builder()
                .patientId(patient.getId())
                .visitDate(LocalDateTime.now())
                .status(status)
                .kioskToken("CANCEL%06d".formatted(TOKEN_SEQ.incrementAndGet())) // CHAR(12)
                .build());
    }
}
