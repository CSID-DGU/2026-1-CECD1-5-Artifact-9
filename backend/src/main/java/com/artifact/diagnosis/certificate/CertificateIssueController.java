package com.artifact.diagnosis.certificate;

import com.artifact.diagnosis.common.jwt.AuthPrincipal;
import com.artifact.diagnosis.common.security.DoctorAccess;
import com.artifact.diagnosis.common.security.StaffAccess;
import com.artifact.diagnosis.print.PrintService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "제증명", description = "내원 단위 증명서 발급 API")
@RestController
@RequestMapping("/api/v1/visits/{visitId}/certificates")
@RequiredArgsConstructor
public class CertificateIssueController {

    private final CertificateService certificateService;
    private final PrintService printService;

    @Operation(summary = "AI 초안 생성",
               description = "증명서의 서술 항목(소견·향후 치료계획·의뢰 사유) 초안을 LLM으로 생성합니다. "
                           + "병명·약품명·날짜·면허번호 같은 사실 항목은 초안 대상이 아니며 발급 시 서버가 DB에서 채웁니다. "
                           + "저장하지 않고 화면 입력칸을 채워줄 뿐이며, 실패해도 오류가 아니라 "
                           + "generated=false 로 응답합니다 — 의사가 직접 작성해 발급할 수 있어야 하기 때문입니다.")
    @DoctorAccess // 서술 항목이 있는 서류(진단서·소견서·의뢰서)는 모두 의사 전용이다.
    @PostMapping("/draft")
    public CertificateDraftResponse draft(
            @PathVariable Long visitId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CertificateDraftRequest request) {
        return certificateService.draft(visitId, principal, request);
    }

    @Operation(summary = "증명서 발급",
               description = "증명서를 발급하고 발급대장에 기록합니다. 발급 시점의 문서 내용 전체가 "
                           + "스냅샷으로 보존되어, 이후 처방이 수정되어도 이미 발급된 서류는 그대로 남습니다.\n\n"
                           + "진단명이 들어가는 서류(진단서·소견서·진료의뢰서)는 해당 환자를 직접 진료한 의사만 "
                           + "발급할 수 있습니다(의료법 제17조). 다른 계정이 시도하면 403을 반환합니다.\n\n"
                           + "처방전·진료확인서는 원무과(STAFF)도 발급할 수 있으나, 문서에 서명되는 의사는 "
                           + "발급 버튼을 누른 사람이 아니라 실제 진료한 의사입니다.")
    @StaffAccess // 종류별 추가 제한(의료법 제17조)은 CertificateService 가 판단한다.
    @PostMapping
    public CertificateResponse issue(
            @PathVariable Long visitId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CertificateIssueRequest request) {
        CertificateResponse issued = certificateService.issue(visitId, principal, request);
        // 감열지 '발급 확인증' 자동 출력. 법정 서식(A4)을 대체하지 않는 안내용 출력물이며,
        // 원본 증명서 인쇄 흐름(프론트 Certificate.tsx 의 window.print())은 그대로다.
        // CertificateService 는 클래스 단위 @Transactional 이라, 커밋이 끝난 여기서 부른다.
        printService.printCertificateSlipAsync(issued);
        return issued;
    }

    @Operation(summary = "내원별 발급이력",
               description = "해당 접수에서 발급된 증명서 목록입니다.")
    @StaffAccess
    @GetMapping
    public List<CertificateSummaryResponse> findByVisit(@PathVariable Long visitId) {
        return certificateService.findByVisit(visitId);
    }
}
