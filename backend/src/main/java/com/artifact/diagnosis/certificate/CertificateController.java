package com.artifact.diagnosis.certificate;

import com.artifact.diagnosis.common.jwt.AuthPrincipal;
import com.artifact.diagnosis.common.security.DoctorAccess;
import com.artifact.diagnosis.common.security.StaffAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "제증명", description = "발급대장 조회·재발급·무효 처리 API")
@RestController
@RequestMapping("/api/v1/certificates")
@RequiredArgsConstructor
public class CertificateController {

    private final CertificateService certificateService;

    @Operation(summary = "환자별 발급이력",
               description = "환자가 지금까지 발급받아 간 증명서 목록입니다. 실제 병원의 제증명 발급대장에 해당합니다.")
    @StaffAccess
    @GetMapping
    public List<CertificateSummaryResponse> findByPatient(@RequestParam Long patientId) {
        return certificateService.findByPatient(patientId);
    }

    @Operation(summary = "증명서 단건 조회",
               description = "발급 당시의 문서 내용 전체(content)를 포함해 반환합니다. "
                           + "이 응답만으로 발급된 서류를 그대로 다시 그릴 수 있습니다.")
    @StaffAccess
    @GetMapping("/{certificateId}")
    public CertificateResponse get(@PathVariable Long certificateId) {
        return certificateService.get(certificateId);
    }

    @Operation(summary = "재발급",
               description = "분실 등의 사유로 같은 서류를 다시 발급합니다. 지금 데이터로 새로 만들지 않고 "
                           + "원본 스냅샷을 그대로 복사하므로, 그 사이 처방이 수정되었더라도 "
                           + "이미 환자에게 나간 서류와 내용이 일치합니다. 발급번호만 새로 부여됩니다.")
    @StaffAccess // 원본과 동일한 권한 검사를 CertificateService 가 다시 수행한다.
    @PostMapping("/{certificateId}/reissue")
    public CertificateResponse reissue(
            @PathVariable Long certificateId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return certificateService.reissue(certificateId, principal);
    }

    @Operation(summary = "무효 처리",
               description = "잘못 발급된 증명서를 무효 처리합니다. 발급 기록은 삭제하지 않고 상태만 VOID 로 바꿉니다 — "
                           + "기록이 사라지면 그 서류가 발급되었다는 사실 자체를 확인할 수 없게 되기 때문입니다. "
                           + "무효 처리된 증명서는 재발급할 수 없습니다.")
    @DoctorAccess // 발급 기록을 무효화하는 것은 진료기록 정정에 준하는 행위다.
    @PostMapping("/{certificateId}/void")
    public CertificateResponse voidCertificate(
            @PathVariable Long certificateId,
            @Valid @RequestBody CertificateVoidRequest request) {
        return certificateService.voidCertificate(certificateId, request);
    }
}
