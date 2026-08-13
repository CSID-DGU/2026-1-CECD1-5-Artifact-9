package com.artifact.diagnosis.common.jwt;

import java.security.Principal;

/**
 * 서버가 JWT 서명을 검증한 뒤 직접 꺼낸 신원.
 *
 * <p>요청 body·쿼리 파라미터로 들어온 회원 ID와 달리 <b>클라이언트가 바꿔 넣을 수 없다.</b>
 * "누가 했는가"를 기록하거나 검사하는 자리에서는 요청값 대신 반드시 이 값을 쓴다 —
 * 처방의 작성자(prescription.member_id)는 진료기록의 법적 책임 주체라 특히 그렇다.
 *
 * <p>컨트롤러에서는 {@code @AuthenticationPrincipal AuthPrincipal principal} 로 받는다.
 * {@link Principal}을 구현하므로 {@code Authentication.getName()} 은 기존과 동일하게 loginId를 돌려준다.
 */
public record AuthPrincipal(Long memberId, String loginId, String role) implements Principal {

    @Override
    public String getName() {
        return loginId;
    }
}
