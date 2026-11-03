package com.artifact.diagnosis.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.http.HttpResponse;

/**
 * FastAPI 추론({@code /predict-base64})이 200 이 아닌 응답을 줬을 때 무엇으로 올릴지.
 *
 * 진료실 분석({@link AnalysisService})과 키오스크 예비분석({@code KioskService})이 이 한 곳을 같이 쓴다. 전에는
 * 두 서비스가 같은 판정을 각자 들고 있었다 — 한쪽만 고치면 같은 AI 서버 상태에 두 화면이 다른 상태 코드를 받는다
 * (실제로 503 처리가 한동안 진료실에만 있었다). 화면마다 다른 것은 503 문구의 안내 한 마디뿐이라 인자로 받는다.
 *
 * <pre>
 *   400 + detail.code = "invalid_image"  → {@link InvalidImageException}         400  보낸 사진을 열 수 없다. 다른 사진이면 풀린다
 *   503                                  → {@link AiServiceUnavailableException} 503  등록은 됐지만 가중치가 없는 태스크
 *   그 밖 — 다른 400·401·422·500 …       → RuntimeException                      500  백엔드 버그나 설정 오류
 * </pre>
 *
 * <b>FastAPI 의 400 을 모두 화면에 400 으로 넘기지 않는다.</b> FastAPI 는 모르는 태스크 이름(get_task_model)과 깨진
 * base64(predict_base64)에도 400 을 낸다. 둘 다 사진이 아니라 백엔드가 요청을 잘못 만든 것이다 — {@link AnalysisType}
 * 과 FastAPI 의 TASKS 가 어긋났거나(배포 순서가 엇갈린 경우 포함) 인코딩이 틀렸다. 이것을 400 으로 넘기면 화면은
 * "사진을 다시 찍어 달라"고 안내하고, 의사·환자는 몇 번을 다시 찍어도 같은 오류를 본다. 고칠 사람은 개발자이므로
 * 500 과 ERROR 로그(GlobalExceptionHandler#handleEtc)로 남겨 장애로 드러나게 한다. 401(내부 시크릿 불일치)과
 * 422(요청 본문 모양 불일치)도 같은 이유로 500 이다.
 * 둘을 가르는 표지가 FastAPI 본문의 {@code detail.code} 다(fastapi/main.py 의 INVALID_IMAGE) — 상태 코드는 둘 다 400 이라
 * 그것만으로는 구별되지 않는다.
 */
@Slf4j
public final class FastApiErrors {

    /** fastapi/main.py 의 INVALID_IMAGE 와 같은 문자열. 두 값이 같은지는 fastapi/tests/test_model_contract.py [8] 이 대조한다. */
    private static final String INVALID_IMAGE = "invalid_image";

    private FastApiErrors() {
    }

    /**
     * 200 이 아닌 FastAPI 응답을 던질 예외로 바꾼다. 호출부는 돌려받은 것을 그대로 던진다.
     *
     * @param task              요청한 태스크 코드. 503 문구와 로그에 쓴다
     * @param context           로그에서 어느 화면의 호출인지 가르는 말 ("분석", "예비분석")
     * @param unavailableAdvice 503 문구 끝에 붙일 안내. 진료실은 직원이 서버 상태를 볼 수 있고, 태블릿 앞에는
     *                          환자가 있어 접수처로 보낸다
     */
    public static RuntimeException toException(HttpResponse<String> response, String task, String context,
                                               String unavailableAdvice, ObjectMapper objectMapper) {
        int status = response.statusCode();
        String body = response.body();

        if (status == 400 && isInvalidImage(body, objectMapper)) {
            // 보낸 쪽의 사진 문제라 ERROR 도 스택트레이스도 남기지 않는다. 그래도 한 줄은 남긴다 — FastAPI 가 적어 준 사유
            // (HEIC, 몇 바이트에서 잘림 …)가 화면 문구에는 실리지 않으므로 여기가 유일한 기록이고, 같은 사유가 되풀이되면
            // (예: 어떤 브라우저가 HEIC 를 그대로 올린다) 화면 쪽에서 고칠 일이다.
            log.warn("FastAPI 가 사진을 열지 못함({}) task={} body={}", context, task, body);
            return new InvalidImageException();
        }
        if (status == 503) {
            // 등록은 됐지만 가중치 파일이 없어 로드되지 않은 태스크가 이렇게 온다(fastapi/main.py get_task_model —
            // CI·개발 환경에는 facial.pth 가 없다). 우리 버그가 아니라 그쪽 준비 상태의 문제이므로 500 이 아니라 503 이다.
            // 로그에 FastAPI 본문을 남겨 어느 태스크가 빠졌는지 보이게 한다.
            log.error("FastAPI 503({}) task={} body={}", context, task, body);
            return new AiServiceUnavailableException(
                    "AI 분석 서버에 '" + task + "' 모델이 준비되어 있지 않습니다. " + unavailableAdvice);
        }
        return new RuntimeException("FastAPI 오류(" + context + ") " + status + ": " + body);
    }

    /**
     * 본문이 {@code {"detail": {"code": "invalid_image", ...}}} 인가. detail 이 문자열(태스크 이름 오류 등)이거나
     * 배열(422)이면 아니다.
     */
    private static boolean isInvalidImage(String body, ObjectMapper objectMapper) {
        try {
            return INVALID_IMAGE.equals(objectMapper.readTree(body).path("detail").path("code").asText());
        } catch (JsonProcessingException e) {
            // FastAPI 가 아닌 무언가(앞단 프록시의 HTML 오류 페이지 등)가 답했다 — 사진 문제라고 볼 근거가 없다.
            return false;
        }
    }
}
