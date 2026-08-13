package com.artifact.diagnosis.prescription;

import com.artifact.diagnosis.common.jwt.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "처방", description = "처방 저장/조회 API")
@RestController
@RequestMapping("/api/v1/visits/{visitId}/prescription")
@RequiredArgsConstructor
public class PrescriptionController {

    private final PrescriptionService prescriptionService;
    private final GeminiService geminiService;

    @Operation(summary = "처방 저장",
               description = "의사가 KCD 상병코드 확정 + 약품 처방을 저장합니다. "
                           + "저장 행위 자체가 진단 확정이므로 visit 상태는 IN_PROGRESS/ANALYZED/DIAGNOSED 어느 쪽이든 "
                           + "한 트랜잭션에서 PRESCRIBED 로 전이합니다(AI 분석 없이 바로 처방하는 경로 포함). "
                           + "이미 PRESCRIBED 면 기존 처방을 대체하는 재처방으로 처리합니다. "
               + "처방 작성자는 요청 body가 아니라 인증 토큰에서 서버가 직접 결정합니다 — 즉 로그인한 계정으로만 처방됩니다.")
    @PostMapping
    public PrescriptionResponse save(
            @PathVariable Long visitId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody PrescriptionRequest request) {
        return prescriptionService.save(visitId, principal.memberId(), request);
    }

    @Operation(summary = "처방 조회",
               description = "해당 접수의 최종 처방(상병코드 + 약품 목록)을 조회합니다.")
    @GetMapping
    public PrescriptionResponse get(@PathVariable Long visitId) {
        return prescriptionService.get(visitId);
    }

    @PostMapping("/comment")
    public PrescriptionCommentResponse comment(
            @PathVariable Long visitId,
            @RequestBody PrescriptionCommentRequest request) {
        return geminiService.generateComment(request);
    }
}
