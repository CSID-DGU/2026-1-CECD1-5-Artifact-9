package com.artifact.diagnosis.prescription;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "처방", description = "처방 저장/조회 API")
@RestController
@RequestMapping("/api/v1/visits/{visitId}/prescription")
@RequiredArgsConstructor
public class PrescriptionController {

    private final PrescriptionService prescriptionService;

    @Operation(summary = "처방 저장",
               description = "의사가 KCD 상병코드 확정 + 약품 처방을 저장합니다. visit 상태: DIAGNOSED → PRESCRIBED")
    @PostMapping
    public PrescriptionResponse save(
            @PathVariable Long visitId,
            @Valid @RequestBody PrescriptionRequest request) {
        return prescriptionService.save(visitId, request);
    }

    @Operation(summary = "처방 조회",
               description = "해당 접수의 최종 처방(상병코드 + 약품 목록)을 조회합니다.")
    @GetMapping
    public PrescriptionResponse get(@PathVariable Long visitId) {
        return prescriptionService.get(visitId);
    }
}
