package com.artifact.diagnosis.docshare;

import com.artifact.diagnosis.common.security.PublicEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 감열지 QR 로 들어오는 환자용 문서 열람 API.
 *
 * 이 경로를 여는 근거는 {@link DocumentShareService} 주석에 정리해 두었다. 요약하면,
 * QR 을 찍는 사람은 병원 계정이 없는 환자라 인증을 요구할 수 없고, 대신 문서 한 건만
 * 여는 base62 12자 난수 토큰과 유효기간으로 범위를 좁힌다.
 *
 * <b>여기에 엔드포인트를 추가할 때 지킬 것.</b>
 * <ul>
 *   <li>경로 변수는 토큰만 받는다. certificateId·visitId 처럼 추측 가능한 값을 받으면
 *       그 순간 아무나 남의 문서를 열 수 있게 된다.</li>
 *   <li>읽기 전용만 둔다. 이 경로로는 무엇도 바꾸지 않는다. 아래 {@code verify} 가 POST 인 것은
 *       생년월일을 URL(쿼리스트링)에 실으면 접근 로그와 프록시 기록에 그대로 남기 때문이지,
 *       무언가를 저장하기 때문이 아니다.</li>
 *   <li>SecurityConfig 의 permitAll 목록에도 같은 경로가 있어야 한다.</li>
 * </ul>
 */
@Tag(name = "문서 공개 열람", description = "감열지 QR 로 접근하는 환자용 문서 열람 API (인증 없음)")
@RestController
@RequestMapping("/api/public/documents")
@RequiredArgsConstructor
@PublicEndpoint // 환자에게는 JWT 가 없다. 대신 문서별 share token 이 접근 대상을 한정한다 — 위 주석 참고.
public class DocumentShareController {

    private final DocumentShareService documentShareService;

    @Operation(summary = "증명서 열람 확인 단계",
               description = "발급확인증 QR 의 토큰이 살아 있는지 확인하고 링크 만료 시각만 돌려준다. "
                           + "증명서 내용은 포함되지 않는다 — 아래 verify 를 통과해야 나간다. "
                           + "토큰이 유효하지 않으면 404, 유효기간이 지났으면 410.")
    @GetMapping("/certificate/{token}")
    public SharedDocumentGateResponse certificateGate(
            @Parameter(description = "감열지 출력 시 발급된 열람 토큰") @PathVariable String token) {
        return documentShareService.openCertificateGate(token);
    }

    @Operation(summary = "증명서 본인 확인 후 열람",
               description = "환자 생년월일이 발급 당시 값과 일치할 때에만 증명서 1건을 돌려준다. "
                           + "생년월일이 틀리면 403, 반복해서 틀리면 429, "
                           + "토큰이 유효하지 않으면 404, 유효기간이 지났으면 410.")
    @PostMapping("/certificate/{token}/verify")
    public SharedCertificateResponse certificate(
            @Parameter(description = "감열지 출력 시 발급된 열람 토큰") @PathVariable String token,
            @Valid @RequestBody ShareVerificationRequest request) {
        return documentShareService.verifyCertificate(token, request.birthDate());
    }

    @Operation(summary = "진료요약 열람",
               description = "진료요약서 QR 의 토큰으로 진료 1건의 요약을 조회한다. "
                           + "토큰이 유효하지 않으면 404, 유효기간이 지났으면 410.")
    @GetMapping("/visit-summary/{token}")
    public SharedVisitSummaryResponse visitSummary(
            @Parameter(description = "감열지 출력 시 발급된 열람 토큰") @PathVariable String token) {
        return documentShareService.readVisitSummary(token);
    }
}
