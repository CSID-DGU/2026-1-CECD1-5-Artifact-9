package com.artifact.diagnosis.print;

import com.artifact.diagnosis.common.security.MedicalAccess;
import com.artifact.diagnosis.common.security.StaffAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 감열지 수동 재출력 API.
 *
 * 접수·진료완료·증명서 발급 시에는 자동으로 출력되지만, 용지가 걸렸거나 환자가
 * 종이를 잃어버린 경우를 위해 화면에서 다시 뽑을 수 있어야 한다.
 *
 * <h2>왜 프론트가 print-agent 를 직접 부르지 않는가</h2>
 * 배포된 프론트엔드는 HTTPS 로 서비스되는데, print-agent 는 접수 데스크 맥북에서
 * 평문 HTTP(localhost:5051)로 돈다. 브라우저가 mixed content 로 차단하기 때문에
 * 반드시 백엔드를 거쳐야 한다.
 *
 * <h2>응답 형태</h2>
 * 실패해도 HTTP 200 에 {@code ok:false} 로 돌려준다. 프린터가 꺼져 있는 것은
 * 서버 오류가 아니라 화면에 안내할 상황이고, 오류 토스트보다 "프린터를 확인하세요"
 * 라는 문구를 그대로 보여주는 편이 낫다.
 */
@Tag(name = "감열지 출력", description = "영수증 프린터 수동 재출력 API")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PrintController {

    private final PrintService printService;

    @Operation(summary = "접수증(대기번호표) 재출력",
               description = "키오스크 QR 이 들어간 접수증을 다시 출력합니다.")
    @ApiResponse(responseCode = "200", description = "요청 처리됨 (ok 필드로 성공 여부 확인)")
    @ApiResponse(responseCode = "404", description = "접수 없음")
    @StaffAccess
    @PostMapping("/visits/{visitId}/print/ticket")
    public PrintOutcome printTicket(
            @Parameter(description = "접수 ID", example = "1") @PathVariable Long visitId,
            // 자동 출력(VisitController.create)과 같은 주소가 찍혀야 한다.
            // 재출력한 종이만 다른 곳을 가리키면 그게 더 나쁘다.
            @RequestHeader(value = PrintService.KIOSK_BASE_URL_HEADER, required = false)
            String kioskBaseUrl) {
        return printService.printTicket(visitId, kioskBaseUrl);
    }

    @Operation(summary = "진료 요약서 재출력",
               description = "진단·처방과 AI 코멘트가 들어간 요약서를 다시 출력합니다. 처방이 저장돼 있어야 합니다.")
    @ApiResponse(responseCode = "200", description = "요청 처리됨 (ok 필드로 성공 여부 확인)")
    @ApiResponse(responseCode = "404", description = "접수 또는 처방 없음")
    @MedicalAccess
    @PostMapping("/visits/{visitId}/print/visit-summary")
    public PrintOutcome printVisitSummary(
            @Parameter(description = "접수 ID", example = "1") @PathVariable Long visitId) {
        return printService.printVisitSummary(visitId);
    }

    @Operation(summary = "증명서 발급 확인증 재출력",
               description = "법정 서식(A4)이 아니라 발급 사실을 알리는 안내용 출력물입니다. "
                           + "감열지는 보존용으로 쓸 수 없으므로 원본 증명서는 기존 A4 인쇄를 사용하세요.")
    @ApiResponse(responseCode = "200", description = "요청 처리됨 (ok 필드로 성공 여부 확인)")
    @ApiResponse(responseCode = "404", description = "증명서 없음")
    @StaffAccess
    @PostMapping("/certificates/{certificateId}/print/slip")
    public PrintOutcome printCertificateSlip(
            @Parameter(description = "증명서 ID", example = "1") @PathVariable Long certificateId) {
        return printService.printCertificateSlip(certificateId);
    }
}
