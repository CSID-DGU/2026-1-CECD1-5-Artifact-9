from fastapi import Depends, FastAPI, File, Form, Header, UploadFile, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import hashlib
import hmac
import torch
import timm
from torchvision import transforms
from torchvision.ops import roi_align
from PIL import Image, ImageOps
import io
import base64
import os
import threading
import numpy as np
import torch.nn.functional as F

app = FastAPI(title="Artifact Medical AI", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# =============================================
# 태스크 레지스트리 — 어떤 가중치가 어떤 클래스를 내는가
# =============================================
# 2026-10 까지 이 서버는 모델 하나(피부 병변 8클래스)만 섬겼다. AI허브 안면부 피부질환
# 데이터(71863)로 두 번째 모델이 들어오면서 "모델 + 클래스 목록 + 가중치 파일 + 락 + CAM
# 레이어" 묶음을 태스크 단위로 등록한다. 계약은 docs/aihub-datasets-plan.md §3 이다.
#
# 규칙 — 어기면 서버는 멀쩡히 뜨고 병명만 조용히 틀린다(tests/test_model_contract.py 가 잡는다):
#  - classes 의 순서는 학습 때의 출력 인덱스다. **바꾸지 말고 뒤에만 추가**한다.
#  - 클래스 코드는 **모든 태스크를 통틀어 유일**해야 한다. 백엔드 disease 테이블을 태스크
#    구분 없이 공유하기 때문이다(disease_code 가 키).
#  - names_ko 는 DB 시드·Java 맵과 글자 단위로 같아야 한다. 원본은 계약 문서 §3-1 표.
#  - 기본 태스크(skin_lesion)는 **기존 동작 그대로**다. 요청에 task 가 없으면 이것이다.
DEFAULT_TASK = "skin_lesion"

TASKS: dict[str, dict] = {
    "skin_lesion": {
        "classes": ["akiec", "bcc", "bkl", "df", "mel", "nv", "vasc", "inflammatory"],
        "names_ko": {
            "akiec": "광선각화증/상피내암",
            "bcc":   "기저세포암",
            "bkl":   "양성 각화증성 병변",
            "df":    "피부섬유종",
            "mel":   "악성 흑색종",
            "nv":    "멜라닌세포모반",
            "vasc":  "혈관성 병변",
            "inflammatory": "염증성 피부질환",
        },
        "weights": "model.pth",
        "input_size": 224,
        # 확대 영역을 이 모델에 다시 넣어도 되는가(run_inference 의 확대 보기 참고).
        # 켜면 확대 히트맵을 그 영역 자체의 CAM 으로 그리고, 원본 신뢰도가 낮을 때 확대 영역의
        # 판정을 섞는다. 공식 홀드아웃 HAM 2,004장·SCIN 394장에서 재판정이 해가 없음을 확인했다.
        "reinfer_crops": True,
    },
    "facial": {
        # 코드는 알파벳순으로 고정했다(계약 §3-1). normal 은 KCD 가 없는 '정상 피부' 클래스다 —
        # 데이터셋에 정상 폴더가 있고, 정상을 빼고 학습하면 정상 피부에도 병명을 붙이게 된다.
        "classes": ["acne", "atopic", "normal", "psoriasis", "rosacea", "seb_derm"],
        "names_ko": {
            "acne":      "여드름",
            "atopic":    "아토피피부염",
            "normal":    "정상 피부",
            "psoriasis": "건선",
            "rosacea":   "주사(장미증)",
            "seb_derm":  "지루피부염",
        },
        "weights": "models/facial.pth",
        # 384 인 이유: 정면 사진은 1024x1024 얼굴 전체라 224 로 줄이면 작은 병변이 몇 픽셀로 뭉개진다.
        # 같은 데이터·같은 설정에서 해상도만 바꿔 재 보니 홀드아웃 1,200장 기준
        #   224: 전체 89.3% · 정면 79.8% · 측면 98.7%
        #   384: 전체 92.3% · 정면 84.7% · 측면 100%   (tests/baselines/facial-train-2026-10-28.json)
        # 학습 검증 전처리(training/aihub/train.py val_tf)가 이 값으로 Resize 하므로 **학습 때 쓴 크기와 같아야 한다.**
        # 다르면 서버는 멀쩡히 뜨고 점수만 조용히 떨어진다. 가중치를 바꾸면 리포트의 args.image_size 를 보고 맞춘다.
        "input_size": 384,
        # 끈다. 학습 사진은 얼굴 전체(정면·측면)라 확대한 피부 조각은 이 모델에게 낯선 입력이다.
        # SCIN 실사진 192장에서 확대 영역만으로 판정하면 정답률이 원본 24.5% → 20.3%(1번 영역)로
        # 떨어지고, 원본과 반반 섞어도 22.4% 다. 확대 보기는 전체 히트맵을 잘라 키운 것으로 보여 준다.
        "reinfer_crops": False,
    },
}

# 하위호환 별칭 — tests/evaluate.py · evaluate_cam.py · test_model_contract.py 와 이 파일의
# 기존 주석이 이 이름으로 기본 태스크를 가리킨다. 나머지 별칭(model, transform,
# MODEL_VERSION, CAM_LAYERS, CAM_LAYER_NAMES, _model_lock)은 모델을 로드한 뒤 아래에서 묶는다.
CLASSES = TASKS[DEFAULT_TASK]["classes"]
CLASS_NAMES_KO = TASKS[DEFAULT_TASK]["names_ko"]

# ─────────────────────────────────────────────────────────────────────────────
# 신뢰도 경고선 — 이 값 아래면 결과에 "확신 낮음" 등급이 붙는다. **막지는 않는다.**
#
# 2026-08-30 까지 이 값은 차단선(MIN_TOP1_CONFIDENCE)이었다. 그 설계를 버린 이유 셋:
#
# 1) 차단은 원래 목적을 어느 값에서도 달성하지 못했다.
#    이 값의 목적은 정확도가 아니라 "피부 병변 사진이 아닌 것"을 걸러내는 것이었다.
#    병변이 아닌 사진 543장(tests/make_ood.py)으로 재보니 0.35 에서 79.6%,
#    0.45 로 올려도 61.9% 가 그대로 통과해 병명을 받았다. 아무것도 없는 정상 피부
#    391장 중 137장(35.0%)이 악성·전암으로 통과했고, 단색 회색 사각형이 89.6%,
#    체크무늬가 100.0% 확신도로 inflammatory 를 받는다. OOD 필터로 작동하지 않는다.
#
# 2) 차단의 비용은 하필 "암을 놓치는" 쪽으로 나타났다.
#    홀드아웃 2,857장(tests/baselines/holdout.csv) 실측 —
#      차단 0.45 : 14.4% 가 답을 못 받음,  mel 재현율 86.3%,  위험군 89.9%
#      차단 0.35 :  4.5% 가 답을 못 받음,  mel 재현율 88.8%,  위험군 94.7%
#      차단 없음 :  전부 답을 받음,        mel 재현율 89.3%,  위험군 95.6%
#    차단을 걷어낼수록 놓치는 암이 줄어든다. 차단은 애매한 것부터 걷어내는데
#    애매한 쪽에 악성이 몰려 있기 때문이다. "거절하면 의사가 대신 본다"는 전제는
#    거절이 난이도를 따라갈 때만 성립하는데, 위 숫자가 그렇지 않다고 말한다.
#    (통과분 정확도만 차단할수록 올라가지만, 그건 어려운 문제를 안 풀고 얻은 점수다.
#     같은 100장에서 실제로 맞힌 장수는 차단하지 않는 쪽이 더 많다.)
#
# 3) 그런데 "확신이 낮다"는 신호 자체는 버리기 아까웠다.
#    0.45 미만 구간은 전체의 14.4% 인데 그 안의 정확도가 57.3% 다 (나머지는 88.3%).
#    전체 오답 461건 중 175건(38.0%)이 이 14.4% 안에 몰려 있다 — 오답 농축 2.6배.
#    이 선은 "답을 주지 말아야 할 경계"가 아니라 "답을 의심해야 할 경계"였다.
#
# 그래서 차단을 없애고 같은 자리에 경고를 세웠다. 재현율은 오히려 오르고(2번),
# 오답의 38.0% 와 엉뚱한 사진의 38.1% 에 표시가 붙는다 — 차단이 잡던 20.4% 보다 낫다.
#
# 조정하려면 느낌으로 고치지 말고 tests/evaluate.py 를 --ood-dir 과 함께 돌려
# 「임계값의 양쪽 비용」 표를 다시 뽑을 것. 근거 수치는 tests/baselines/README.md.
#
# 경고 **문구**는 여기서 만들지 않고 등급만 내려보낸다. 문구는 심각도(악성/양성)에
# 따라 갈려야 하는데 심각도의 원본은 disease 테이블이고, 그건 백엔드가 소유한다.
# 여기에 사본을 두면 언젠가 한쪽만 바뀐다.
LOW_CONFIDENCE_THRESHOLD = float(os.getenv("LOW_CONFIDENCE_THRESHOLD", "0.45"))

# 경고 등급 문자열. DB(analysis_result.confidence_level)에 그대로 저장되므로
# 값을 바꾸면 백엔드 매핑과 마이그레이션(V6)도 함께 바꿔야 한다.
CONFIDENCE_LOW = "low"
CONFIDENCE_NORMAL = "normal"

# =============================================
# 내부 호출 인증 — 백엔드만 추론을 부를 수 있게 한다
# =============================================
# 이 서버에는 로그인이 없다. docker-compose 에서 ports 를 빼 두었지만 그건 "호스트에 열지
# 않는다"일 뿐, **같은 도커 네트워크 안에 있는 것은 무엇이든 그냥 호출할 수 있다**는 뜻이다.
# 컨테이너가 늘어나는 EC2 환경에서는 그 경계가 더 흐려진다.
#
# 그래서 백엔드와 이 서버만 아는 값을 헤더로 주고받고, 맞지 않으면 추론을 거절한다.
# 완전한 인증은 아니지만, "네트워크에 들어오기만 하면 GPU를 마음껏 쓴다"를 막는 것이 목적이다.
#
# 값이 없으면 **서버가 아예 뜨지 않는다.** 기본값을 두면 그 기본값이 곧 공개된 값이 되고
# (JWT 서명키에서 이미 겪었다), 무엇보다 "조용히 무방비"인 상태가 제일 위험하다.
INTERNAL_API_SECRET = os.getenv("INTERNAL_API_SECRET", "")
if not INTERNAL_API_SECRET:
    raise RuntimeError(
        "INTERNAL_API_SECRET 이 설정되지 않았습니다. 추론 엔드포인트가 무방비로 열리므로 "
        "기동을 중단합니다. 값 생성: openssl rand -base64 32"
    )


def verify_internal_secret(x_internal_secret: str = Header(default="")) -> None:
    """백엔드가 보낸 공유 시크릿을 확인한다.

    `==` 가 아니라 hmac.compare_digest 를 쓰는 이유: 문자열 비교는 앞에서부터 맞춰보다
    틀리는 순간 멈추기 때문에, 응답 시간 차이로 값을 한 글자씩 알아낼 수 있다(타이밍 공격).
    compare_digest 는 항상 같은 시간이 걸린다.
    """
    if not hmac.compare_digest(x_internal_secret, INTERNAL_API_SECRET):
        raise HTTPException(status_code=401, detail="내부 호출 인증에 실패했습니다.")


device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

# =============================================
# 히트맵 표시 기준 — 환경변수로 뺀 이유
# =============================================
# 현장(부스/진료실)에서 색이 너무 옅다/짙다는 조정이 코드 수정 없이
# docker compose 재기동만으로 끝나야 한다. LOW_CONFIDENCE_THRESHOLD 와 같은 방식이다.
#
# 표시 방식은 jet(빨강 = 모델이 근거로 삼은 곳, 파랑 = 그렇지 않은 곳)이다. 두 태스크·두 화면
# (진료실·키오스크)이 같은 방식으로 칠한다.
#   2026-10 까지는 "바닥값 아래는 칠하지 않고 노랑→빨강만 쓴다"였다. 그 전의 jet 균일 알파
#   (0.5*원본 + 0.5*히트맵)에서 병변이 짙은 파랑으로 보인다는 지적을 받아 바꾼 것인데,
#   **원인은 색이 아니라 CAM 이었다** — 당시 맵(conv_head Grad-CAM++)은 클래스를 보지 않아
#   병변 자리가 실제로 낮은 값이었다(_select_cam_layers 참고). 그 뒤 촘촘한 격자를 섞자
#   바닥값 렌더링에서 "빨간 점만 흩어진" 그림이 됐다(ISIC 1,100장에서 표시 영역이 평균
#   41조각). 지금 CAM 은 병변을 짚으므로(적중 81.6% → 91.5%) jet 으로 돌아온다.
# ALPHA_MIN / ALPHA_MAX: CAM 0 과 1 에서의 불투명도. 사이는 선형이다. 낮은 쪽이 0 이 아닌
#   이유는 "유사하지 않을수록 파랑"을 화면에 남기기 위해서다 — 대신 원본이 비치도록 옅게 둔다.
#   1.0 에 가깝게 올리면 병변 자체가 색에 가려 의사가 눈으로 확인할 수 없다.
# SMOOTH: 표시 직전 가우시안 흐림의 sigma (짧은 변에 대한 비율). 격자 칸 경계를 지워
#   태스크마다 다른 격자(224 입력 14칸, 384 입력 24칸)가 같은 모양으로 보이게 한다.
# CROPS: 히트맵에서 고른 확대 영역 수(최대). 0 이면 확대 보기를 만들지 않는다.
HEATMAP_ALPHA_MIN = float(os.getenv("HEATMAP_ALPHA_MIN", "0.25"))
HEATMAP_ALPHA_MAX = float(os.getenv("HEATMAP_ALPHA_MAX", "0.60"))
HEATMAP_SMOOTH = float(os.getenv("HEATMAP_SMOOTH", "0.03"))
HEATMAP_CROPS = int(os.getenv("HEATMAP_CROPS", "3"))

# 확대 영역을 고르고 그리는 기준. 위치는 ISIC 2018 Task1 정답 마스크 1,100장으로 쟀다.
# _CROP_MIN_PEAK: 2·3번째 영역이 되려면 그 봉우리가 이 값 이상이어야 한다(1번은 늘 최댓값이다).
#   2번째 영역이 병변을 10% 이상 담는 비율이 봉우리 0.5~0.6 에서 53%, 0.8 이상에서 86% 다.
#   0.7 에서 자르면 2번째의 84% 가 병변을 담는다(자르지 않으면 79%). 이미지당 영역은 1.9 → 1.7개.
# _CROP_SMOOTH: 확대 히트맵의 흐림(확대 영역 짧은 변에 대한 비율). 확대하면 병변이 화면을 채워
#   근거가 여러 점으로 갈라진다 — 전체와 같은 0.03 이면 0.5 이상 영역이 평균 4.7조각(병변 적중 89.5%),
#   0.08 이면 2.1조각(92.0%)이다. 기록은 tests/baselines/README.md ③.
_CROP_MIN_PEAK = 0.7
_CROP_SMOOTH = 0.08

# 응답·저장된 히트맵이 어떤 방식으로 만들어졌는지. 표시 방식이 바뀌면 이 값을 올린다 —
# 예전 결과(바닥값·노랑빨강)와 새 결과가 한 화면에 섞였을 때 구별할 근거가 된다.
HEATMAP_STYLE = "layercam-flip-jet-v2"

# 표시용 정규화의 하단 기준점. lo 분위를 0, 99 분위를 1 로 보낸다.
# conv_head Grad-CAM++ 를 쓸 때는 이 뺄셈이 결정적이었다 — 그 맵은 상위 절반이
# 최대값의 84% 에 몰려 있어 p99 로만 나누면 쓸 수 있는 대비가 0.16 밖에 안 남았다.
# 지금 쓰는 LayerCAM 은 희소해서 p50 이 거의 0 이고, 따라서 이 뺄셈은 사실상
# 무동작이다(실측 (p99-p50)/p99 의 p10 = 0.88). 그래도 남겨 둔다 — 올바른 일반형이고
# 격자 해상도에서만 돌아 비용이 0 이며, 나중에 다른 레이어/방식으로 바꿀 때 다시 필요하다.
_CAM_NORM_LO = 0.50


def _select_cam_layers(
    model: torch.nn.Module, input_size: int,
) -> list[tuple[str, torch.nn.Module]]:
    """
    CAM 을 뽑을 레이어를 서버 시작 시 한 번만 자동 선택한다.

    **conv_head 를 쓰지 않는다.** 처음에는 "깊은 쪽이 무엇을 알고 얕은 쪽이 어디를
    안다"는 통념대로 conv_head + 14x14 블록을 썼다. 측정은 반대였다 — conv_head 에서는
    Grad-CAM++ 도 LayerCAM 도 **클래스를 보지 않았다**: 예측 클래스로 뽑은 맵과 최소
    로짓(가장 아니라고 본) 클래스로 뽑은 맵의 상관이 0.98 / 0.96 이다. 같은 방식이
    blocks.2~5 에서는 0.16~0.33 으로 정상 작동한다.

    구조상 당연한 결과다. conv_head 뒤에는 bn -> SiLU -> global pool -> linear 뿐이라
    d(logit_k)/d(conv_head) 가 (클래스별 채널 스칼라 w_k[c]) x (클래스와 무관한 공간항)
    으로 분해된다. relu(grad) 로 채널을 고르는 두 방식은 그 공간항만 1280 채널에 걸쳐
    평균하므로 클래스 신호가 묻힌다. (표준 Grad-CAM 은 활성에 선형이라 1x1 conv 를
    그대로 통과한다 — conv_head 와 마지막 블록의 Grad-CAM 은 **수학적으로 같은 맵**이고,
    실측 상관도 소수점 셋째 자리까지 일치해 측정 자체의 교차검증이 됐다.)

    그래서 마지막 격자의 **2배** 격자를 내는 블록 하나를 쓴다. 224 입력에서 14x14(blocks.4),
    384 입력(안면부)에서 24x24 다. 입력의 1/16 이라 두 태스크에서 칸 하나가 덮는 비율이 같다.

    2026-10 까지는 여기에 그보다 4배 촘촘한 블록(224 에서 56x56 blocks.1)을 섞었다.
    블러 기준 삭제·삽입 충실도가 b4+b1 +0.229 대 b4 단독 +0.136 으로 높았기 때문이다.
    그런데 **병변 정답 마스크로 재 보니 반대였다**(ISIC 2018 Task1 검증·시험 1,100장,
    무작위 기준 마스크 면적 27.9%):
      b4+b1 (옛 방식)        적중 81.6%  마스크 안 에너지 0.511  표시 영역 평균 41.4 조각
      b4 단독                적중 92.4%  0.608                    4.0 조각
      b4 + 좌우 반전 평균     적중 93.6%  0.635                    3.7 조각
      위 + 표시 흐림(3%)      적중 91.5%  0.622                    2.9 조각   <- 지금(서빙 코드로 잼)
      Grad-CAM(채널 평균)    적중 37.9%  0.333   <- 이 모델에서는 거의 무작위다
    촘촘한 블록은 병변 밖 질감에도 반응해 최댓점을 끌어가고, 바닥값 렌더링에서 "빨간 점만
    흩어진" 그림을 만들었다. 충실도 지표는 그 흩어짐을 가리지 못했다(지표 자체가 후보를
    잘 가르지 못한다는 것도 그때 확인했다). 위치를 직접 재는 쪽을 따른다.
    좌우 반전 평균은 _compute_gradcam 이 한다.

    블록 인덱스를 상수로 박지 않는다 — timm 버전에 따라 blocks 구성이 바뀌고,
    나중에 입력 해상도를 올리면 모든 단계의 격자 크기가 같이 바뀐다.
    실제로 한 번 재서 고르면 둘 다에 깨지지 않는다.
    """
    sizes: dict[int, int] = {}
    handles = [
        blk.register_forward_hook(
            lambda _m, _i, out, idx=idx: sizes.__setitem__(idx, int(out.shape[-1]))
        )
        for idx, blk in enumerate(model.blocks)
    ]
    try:
        with torch.no_grad():
            model(torch.zeros(1, 3, input_size, input_size, device=device))
    finally:
        for h in handles:
            h.remove()

    layers: list[tuple[str, torch.nn.Module]] = []
    if sizes:
        final = sizes[max(sizes)]              # 마지막 블록 출력 = conv_head 입력 해상도
        cand = sorted(i for i, s in sizes.items() if s == final * 2)   # 224 입력에서 14x14
        if cand:
            layers.append((f"blocks.{cand[-1]}", model.blocks[cand[-1]]))
    if not layers:
        # 해상도가 올라가는 블록을 못 찾은 경우의 최후 수단. conv_head 로는 가지 않는다
        # (위 독스트링 참고 — 그 위치에서 LayerCAM 은 클래스를 보지 않는다).
        last = max(sizes) if sizes else len(model.blocks) - 1
        layers.append((f"blocks.{last}", model.blocks[last]))
    return layers


class TaskModel:
    """
    로드된 태스크 하나 — 모델과, 그 모델을 안전하게 쓰기 위한 것들의 묶음.

    태스크마다 model 객체가 하나씩이고 서버 전체가 그것을 공유한다. Grad-CAM 은 그 model
    객체에 forward hook 을 등록해 중간 activation 을 꺼내는 방식이라, A 요청이 hook 을
    붙인 상태에서 B 요청이 forward 를 돌리면 **A 의 hook 이 B 의 activation 으로 발화**한다.
    (B 의 forward 는 no_grad 라 "cannot register a hook on a tensor that doesn't
     require gradient" 로 터지거나, 조용히 서로의 히트맵을 뒤바꾼다)

    저장 위치를 요청별로 분리하는 것(contextvars 등)으로는 해결되지 않는다.
    hook 등록과 backward 자체가 공유 객체에 걸리므로, 모델을 건드리는 구간을
    통째로 직렬화하는 것이 유일한 해법이다. 그 락(lock)은 **태스크마다 따로** 둔다 —
    hook 과 backward 가 걸리는 것은 각 태스크의 model 객체이므로 다른 태스크의 요청까지
    한 줄로 세울 이유가 없다. 교차오염 방지 규칙(락 안에서 forward+backward, hook 은
    finally 에서 제거)은 태스크마다 동일하게 적용된다.
    """

    def __init__(self, name: str, spec: dict, weights_path: str) -> None:
        self.name = name
        self.classes: list[str] = spec["classes"]
        self.names_ko: dict[str, str] = spec["names_ko"]
        self.input_size: int = int(spec["input_size"])
        self.weights_path = weights_path

        self.model = timm.create_model(
            "efficientnet_b0", pretrained=False, num_classes=len(self.classes))
        self.model.load_state_dict(
            torch.load(weights_path, map_location=device, weights_only=True))
        self.model.to(device)
        self.model.eval()

        # 가중치 파일 자체의 해시로 버전을 만든다 — 사람이 적는 문자열 상수는 파일을 바꿔도
        # 저절로 안 바뀌므로 언젠가 거짓말을 하게 된다. 해시는 가중치 파일이 바뀌는 즉시,
        # 그리고 그럴 때만 바뀐다.
        with open(weights_path, "rb") as f:
            weights_hash = hashlib.sha256(f.read()).hexdigest()
        self.model_version = f"efficientnet_b0-{weights_hash[:12]}"

        self.lock = threading.Lock()

        self.transform = transforms.Compose([
            transforms.Resize((self.input_size, self.input_size)),
            transforms.ToTensor(),
            transforms.Normalize([0.485, 0.456, 0.406],
                                 [0.229, 0.224, 0.225]),
        ])

        self.cam_layers = _select_cam_layers(self.model, self.input_size)
        self.cam_layer_names = [layer_name for layer_name, _ in self.cam_layers]
        self.reinfer_crops: bool = bool(spec.get("reinfer_crops", False))


def _weights_path_for(name: str) -> str:
    """
    태스크의 가중치 경로. 환경변수 MODEL_PATH_<TASK 대문자>(예: MODEL_PATH_FACIAL)가 있으면
    그것, 없으면 TASKS 에 등록된 상대경로다.

    덮어쓰기를 두는 이유: 모델을 갈아끼워 비교할 때(tests/evaluate.py --compare) 저장소의
    파일을 바꾸지 않고도 다른 가중치를 가리킬 수 있어야 한다. 상대경로는 작업 디렉터리
    (fastapi/) 기준이다 — evaluate.py 와 test_model_contract.py 가 임포트 전에 그리로
    chdir 하는 이유다.

    **빈 문자열은 "덮어쓰기 없음"으로 본다.** docker compose 는 .env 에 `MODEL_PATH_FACIAL=` 처럼
    값이 비어 있어도 변수를 빈 문자열로 넘긴다(GEMINI_MODEL 에서 이미 겪은 함정 — docker-compose.yml
    주석 참고). 그걸 경로 "" 로 해석하면 저장소에 가중치가 있어도 그 태스크가 꺼지고, 기본 태스크면
    기동이 막힌다. 에러 메시지에도 빈 경로만 찍혀 원인을 찾기 어렵다.
    """
    override = os.getenv(f"MODEL_PATH_{name.upper()}", "").strip()
    return override or TASKS[name]["weights"]


def _load_tasks() -> tuple[dict[str, TaskModel], dict[str, str]]:
    """
    등록된 태스크를 전부 로드해 본다. 반환: (로드된 것, 로드하지 못한 것의 사유).

    기본 태스크는 지금까지처럼 가중치가 없거나 깨져 있으면 **기동 실패**다. 모델 없이
    /health 가 ok 를 돌려주는 서버는 "조용히 무방비"(INTERNAL_API_SECRET 주석 참고)와
    같은 종류의 상태라, 죽어서 배포가 실패하는 쪽이 낫다.

    나머지 태스크는 파일이 없거나 state_dict 가 안 맞아도 서버를 죽이지 않는다 —
    CI 에는 facial.pth 가 없고(가중치는 학습이 끝난 뒤 별도 커밋으로 들어온다), 키오스크가
    기대는 기본 태스크를 새 모델 파일 하나가 깨뜨려서는 안 된다. 대신 **이유를 숨기지
    않는다**: 로그 한 줄과 /health 의 tasks.<name>.error 로 드러내고, 그 태스크로 들어온
    요청은 503 을 받는다. 미로드가 조용히 묻히면 "안면부 분석이 왜 안 되지"를 EC2 에
    들어가 로그를 뒤져야만 알게 된다.
    """
    loaded: dict[str, TaskModel] = {}
    errors: dict[str, str] = {}
    for name, spec in TASKS.items():
        path = _weights_path_for(name)
        if not os.path.isfile(path):
            if name == DEFAULT_TASK:
                raise FileNotFoundError(
                    f"기본 태스크 '{name}' 의 가중치 파일이 없습니다: {path}")
            errors[name] = "가중치 파일 없음"
            print(f"[TASKS] {name}: 가중치 파일이 없어 로드하지 않습니다 ({path})")
            continue
        try:
            loaded[name] = TaskModel(name, spec, path)
        except Exception as exc:                 # noqa: BLE001 — 사유를 그대로 노출한다
            if name == DEFAULT_TASK:
                raise
            # size mismatch 메시지는 여러 줄이라 /health 에 넣기 전에 한 줄로 접는다.
            errors[name] = f"{type(exc).__name__}: {' '.join(str(exc).split())[:300]}"
            print(f"[TASKS] {name}: 가중치 로드 실패 — 이 태스크만 비활성 ({path}): {errors[name]}")
            continue
        print(f"[TASKS] {name}: 로드 {loaded[name].model_version} ({path})")
        print(f"[GradCAM] {name}: CAM 레이어 {loaded[name].cam_layer_names}")
    return loaded, errors


TASK_MODELS, TASK_LOAD_ERRORS = _load_tasks()
_DEFAULT_TASK_MODEL = TASK_MODELS[DEFAULT_TASK]

# 하위호환 별칭(기본 태스크) — 위 CLASSES / CLASS_NAMES_KO 와 같은 이유.
model = _DEFAULT_TASK_MODEL.model
transform = _DEFAULT_TASK_MODEL.transform
MODEL_VERSION = _DEFAULT_TASK_MODEL.model_version
CAM_LAYERS = _DEFAULT_TASK_MODEL.cam_layers
CAM_LAYER_NAMES = _DEFAULT_TASK_MODEL.cam_layer_names
_model_lock = _DEFAULT_TASK_MODEL.lock


class PredictRequest(BaseModel):
    image_base64: str
    # 생략하면 기본 태스크 — task 를 모르는 구버전 백엔드가 그대로 돈다.
    task: str = DEFAULT_TASK


def _norm01(t: torch.Tensor, lo: float = _CAM_NORM_LO) -> torch.Tensor:
    """
    CAM 을 표시용 0~1 로 **펴낸다**. lo 분위를 0, 99 분위를 1 로 보낸다.

    처음에는 "min 을 빼지 않으면 '모델이 아무 데도 강하게 안 봤다'가 화면에 남는다"고
    보고 `t / p99` 만 썼다. **실측해 보니 그 이득은 없고 비용만 있었다.** p99 로
    나누는 순간 p99 는 무조건 1.0 이 되므로 전역적으로 약한 CAM 도 풀레인지로
    올라간다 — 절대 수준은 애초에 보존되지 않는다. 반면 CAM 원본은 상위 절반이
    최대값의 80~100% 에 몰려 있어서(실측 p50/p99 = 0.84), 뺄셈을 생략하면 쓸 수 있는
    대비가 0.16 밖에 안 남는다. 그 결과 융합 뒤 거의 전 픽셀이 표시 바닥에 가까스로
    걸쳐 **화면의 70~90% 가 alpha 0.03 으로 뿌옇게 덮였다** — 히트맵이 보이지도 않고
    범위도 넓은 최악의 조합이었다.

    위 문단은 conv_head Grad-CAM++ 를 쓰던 시절의 실측이다. 지금은 LayerCAM 이라
    p50 이 거의 0 이어서 이 뺄셈이 사실상 무동작이고(상수 주석 참고), 그 시절 짝으로
    넣었던 contrast/level 판정은 포화·무근거로 _cam_quality 에서 제거했다.
    그래도 이 형태를 유지한다 — 레이어나 방식을 다시 바꾸면 같은 함정이 돌아온다.

    아래를 99 분위로 두는 이유는 그대로다 — 극값 픽셀 하나가 스케일을 독점하면
    나머지 전부가 어두워진다. 격자 해상도(14x14~24x24)에서만 호출하므로 비용은 0 이다.
    """
    v = t.flatten().float()
    bottom = float(torch.quantile(v, lo))
    top = float(torch.quantile(v, 0.99))
    if top - bottom <= 1e-8:
        return torch.zeros_like(t)
    return ((t - bottom) / (top - bottom)).clamp(0.0, 1.0)


def _layercam(act: torch.Tensor, grad: torch.Tensor) -> torch.Tensor:
    """
    LayerCAM. 채널 가중치를 공간 평균하지 않고 픽셀별 양수 gradient 로 직접 가중하므로
    얕은 층에서 경계가 살아남는다. 얕은 층을 표준 Grad-CAM 으로 뽑으면 거의 노이즈다.
    """
    return F.relu((F.relu(grad) * act).sum(dim=1, keepdim=True))


def _cam_quality(cam: torch.Tensor) -> dict:
    """
    히트맵이 화면에서 어떻게 보이는지를 숫자로 남긴다.
    격자 해상도에서 계산하므로 비용은 사실상 0 이다.

    - focus_area : CAM 이 0.5 이상인 면적 비율. 화면에서 노랑~빨강으로 칠해지는 넓이다.
    - peak_ratio : 상위 10% 격자가 담은 CAM 에너지 비율. 집중도.

    **`level: focused|diffuse` 와 `contrast` 는 뺐다.** 둘 다 "이 히트맵을 믿어도
    되는가"를 단정하는 값이었는데, 실측이 뒷받침하지 않았다(출처마다 충실도와의 관계가
    반대였다). 품질을 가리지 못하는 값으로 "모델이 병변을 특정하지 못했다"고 화면에 쓰면
    근거 없이 신뢰를 조작하게 된다. 집중도 자체는 서술값으로 남기되 판정은 하지 않는다.
    """
    v = cam.flatten()
    total = float(v.sum())
    if total <= 1e-8:
        return {"focus_area": 0.0, "peak_ratio": 0.0}
    k = max(1, int(round(0.1 * v.numel())))
    return {
        "focus_area": round(float((v >= 0.5).float().mean()), 4),
        "peak_ratio": round(float(torch.topk(v, k).values.sum()) / total, 4),
    }


def _forward_with_act(tensor: torch.Tensor, tm: TaskModel) -> tuple[torch.Tensor, torch.Tensor]:
    """forward 1회. 반환: (logits, CAM 레이어의 activation). hook 은 예외가 나도 반드시 뗀다
    (안 떼면 그 태스크의 model 에 hook 이 남아 이후 모든 요청이 계속 터진다)."""
    store: dict[str, torch.Tensor] = {}
    _, layer = tm.cam_layers[0]
    handle = layer.register_forward_hook(lambda _m, _i, out: store.__setitem__("act", out))
    try:
        logits = tm.model(tensor)
    finally:
        handle.remove()
    return logits, store["act"]


def _layercam_for(logits: torch.Tensor, act: torch.Tensor, k: int) -> torch.Tensor:
    """클래스 k 의 LayerCAM (1,1,h,w).

    logits 가 아니라 log_softmax 를 미분한다. 원시 logit 의 gradient 는 "이 클래스 점수를
    올리는 방향"만 보지만, log_softmax 의 gradient 는 거기서 다른 클래스들의 기여를 뺀 값이라
    **그 클래스에만 고유한 근거**를 가리킨다(∂log p_k/∂z_j = δ_kj − p_j). 모든 클래스에 공통으로
    반응하는 영역(피부 전체, 조명, 배경)이 상쇄되어 빠진다.
    grad 는 tensor hook 대신 torch.autograd.grad 로 받는다 — 파라미터 .grad 를 건드리지
    않으므로 요청 간 gradient 누적도, zero_grad 호출도 필요 없다.
    """
    (grad,) = torch.autograd.grad(F.log_softmax(logits, dim=1)[0, k], (act,))
    return _layercam(act, grad).detach()


def _compute_gradcam(
    tensor: torch.Tensor,
    tm: TaskModel,
    target: int | None = None,
) -> tuple[torch.Tensor, torch.Tensor | None, dict | None]:
    """
    분류용 logits 와 표시용 CAM 을 **함께** 구한다.
    반환: (logits, cam, quality). cam/quality 는 실패 시 None(분석 결과에는 영향 없음).
    cam 은 격자 해상도 (1,1,h,w), 0~1 이다. 반드시 그 태스크의 락(tm.lock)을 잡은 채로 호출한다.
    target 을 주면 그 클래스를 설명한다(확대 재판정으로 1순위가 바뀐 경우). 없으면 1순위다.

    원본 1회와 좌우 반전 1회, 두 번 forward·backward 한다. 반전 쪽 CAM 을 되돌려 더하면
    한 번만 볼 때 생기는 칸 단위 잡음이 줄어 병변 적중이 오른다(_select_cam_layers 표 92.4% → 93.6%).
    **분류는 원본 1회의 logits 만 쓴다** — 반전 평균으로 분류까지 바꾸면 tests/baselines 의
    홀드아웃 점수가 더는 이 서버의 점수가 아니게 된다. 반전 쪽은 원본이 고른 클래스를 설명한다.
    """
    logits, act = _forward_with_act(tensor, tm)
    cam = quality = None
    try:
        pred = int(logits.argmax(dim=1).item()) if target is None else target
        cam = _layercam_for(logits, act, pred)
        flipped_logits, flipped_act = _forward_with_act(torch.flip(tensor, dims=[3]), tm)
        cam = cam + torch.flip(_layercam_for(flipped_logits, flipped_act, pred), dims=[3])
        cam = _norm01(cam)
        quality = _cam_quality(cam)
    except Exception as e:
        print(f"[GradCAM] CAM 계산 실패 (분석 결과에는 영향 없음): {e}")
        cam = quality = None
    return logits.detach(), cam, quality


# 표시용 맵을 다루는 작업 해상도(긴 변). 흐림·영역 고르기를 원본 해상도에서 하면 폰카 사진
# (4032x3024) 한 장에 수백 MB 를 쓴다. 이 크기에서 하고 마지막에만 늘린다.
_DISPLAY_WORK_SIDE = 256

# 저장하는 히트맵 JPEG 의 긴 변. 화면은 이보다 작게 그린다 — 원본 해상도로 저장하면
# 폰카 사진 한 장의 히트맵이 수 MB 가 되고, 원본 이미지와 비율만 같으면 토글 위치는 맞는다.
_HEATMAP_MAX_SIDE = 2048
# 확대 히트맵의 긴 변 범위. 작은 사진의 확대 영역은 100~250px 이다(SCIN 394x988 사진에서 139px).
# 그대로 저장하면 화면이 그것을 서너 배로 늘려 JPEG 덩어리가 그대로 보인다. 색을 입히기 **전에**
# 늘려야 히트맵 경계도 계단 없이 그려진다.
_CROP_MIN_SIDE = 448
_CROP_MAX_SIDE = 640


def _gaussian_blur(m: torch.Tensor, sigma: float) -> torch.Tensor:
    """(1,1,h,w) 에 분리형 가우시안. 가장자리는 복제해 바깥이 0 으로 빨려 들지 않게 한다."""
    radius = max(1, int(3 * sigma))
    x = torch.arange(-radius, radius + 1, dtype=m.dtype, device=m.device)
    kernel = torch.exp(-(x ** 2) / (2 * sigma ** 2))
    kernel /= kernel.sum()
    m = F.conv2d(F.pad(m, (radius, radius, 0, 0), mode="replicate"), kernel.view(1, 1, 1, -1))
    return F.conv2d(F.pad(m, (0, 0, radius, radius), mode="replicate"), kernel.view(1, 1, -1, 1))


def _to_p99(m: torch.Tensor) -> torch.Tensor:
    """99 분위를 1 로 보낸다. 0 은 0 으로 둔다 — 확대 영역을 병변이 꽉 채우면 최솟값도 높은데,
    최솟값을 0 으로 끌어내리면 병변 한가운데가 파랗게 칠해진다."""
    top = float(torch.quantile(m.flatten().float(), 0.99))
    return (m / top).clamp(0.0, 1.0) if top > 1e-8 else torch.zeros_like(m)


def _display_map(cam: torch.Tensor, width: int, height: int,
                 smooth: float = HEATMAP_SMOOTH) -> torch.Tensor:
    """
    격자 CAM → 표시용 맵 (1,1,h,w), 0~1. 이미지 비율의 작업 해상도로 늘리고 가우시안으로
    칸 경계를 지운 뒤 다시 0~1 로 편다. 두 태스크의 격자(14·24칸)가 같은 모양으로 보이는 것이
    이 흐림 덕분이다. 정규화를 원본 해상도가 아니라 여기서 하는 이유: 보간이 만든 값이 기준이
    되면 같은 CAM 인데 원본 해상도에 따라 색이 달라진다.
    """
    scale = _DISPLAY_WORK_SIDE / max(width, height)
    w, h = max(8, round(width * scale)), max(8, round(height * scale))
    m = F.interpolate(cam.float(), size=(h, w), mode="bilinear", align_corners=False)
    sigma = smooth * min(w, h)
    if sigma >= 0.5:
        m = _gaussian_blur(m, sigma)
    return _to_p99(m)


def _render_jet(image: Image.Image, disp: torch.Tensor) -> Image.Image:
    """
    표시용 맵을 이미지 크기로 늘려 jet 으로 겹친다. 빨강(1)·노랑·초록·하늘·파랑(0).
    불투명도는 ALPHA_MIN 에서 ALPHA_MAX 까지 선형이다.
    전체 해상도 배열이라 제자리 연산으로 임시 배열 수를 줄인다.
    """
    w, h = image.size
    t = F.interpolate(disp, size=(h, w), mode="bilinear", align_corners=False)
    t = t.squeeze().cpu().numpy().astype(np.float32)
    np.clip(t, 0.0, 1.0, out=t)
    a = t * (HEATMAP_ALPHA_MAX - HEATMAP_ALPHA_MIN)
    a += HEATMAP_ALPHA_MIN
    overlay = np.asarray(image, dtype=np.float32) / 255.0
    # jet 의 채널 c = clip(1.5 - |4t - 중심|, 0, 1). 중심: 빨강 3, 초록 2, 파랑 1.
    for ch, centre in enumerate((3.0, 2.0, 1.0)):
        color = 4.0 * t
        color -= centre
        np.abs(color, out=color)
        np.subtract(1.5, color, out=color)
        np.clip(color, 0.0, 1.0, out=color)
        color *= a
        overlay[..., ch] *= 1.0 - a
        overlay[..., ch] += color
    np.clip(overlay, 0.0, 1.0, out=overlay)
    return Image.fromarray((overlay * 255).astype(np.uint8))


def _jpeg_base64(image: Image.Image) -> str:
    buf = io.BytesIO()
    image.save(buf, format="JPEG", quality=90)
    return base64.b64encode(buf.getvalue()).decode("utf-8")


def _resized(image: Image.Image, min_side: int, max_side: int) -> Image.Image:
    """긴 변을 min_side~max_side 안으로 맞춘다. 이미 안에 있으면 그대로 돌려준다."""
    long_side = max(image.size)
    if min_side <= long_side <= max_side:
        return image
    scale = (max_side if long_side > max_side else min_side) / long_side
    return image.resize((max(1, round(image.width * scale)), max(1, round(image.height * scale))),
                        Image.LANCZOS)


def _render_overlay(image: Image.Image, disp: torch.Tensor, min_side: int, max_side: int,
                    what: str) -> str | None:
    """
    표시용 맵을 오버레이 JPEG(base64)로 렌더링한다. 긴 변을 min_side~max_side 로 맞춘 이미지 위에 그린다.
    모델을 전혀 건드리지 않으므로 **태스크 락(TaskModel.lock) 밖에서** 실행한다 —
    원본 해상도가 클수록(키오스크 폰카메라 사진) 이 구간이 길어지는데,
    락 안에 두면 그만큼 다른 요청이 통째로 대기하게 된다.
    """
    try:
        return _jpeg_base64(_render_jet(_resized(image, min_side, max_side), disp))
    except Exception as e:
        print(f"[GradCAM] {what} 렌더링 실패 (분석 결과에는 영향 없음): {e}")
        return None


def _propose_crops(disp: torch.Tensor, width: int, height: int, k: int) -> list[tuple[float, ...]]:
    """
    표시용 맵에서 확대해 볼 영역을 고른다. 반환: [(x0, y0, x1, y1, peak)] — 원본 픽셀 좌표의 정사각형.

    가장 뜨거운 점에서 시작해 그 점과 이어진 '봉우리의 절반 이상' 칸을 한 후보로 잡고,
    그 칸들을 지운 뒤 다음 봉우리를 찾는다. 1번 봉우리가 0.5 미만이면(맵이 비었으면) 고르지 않고,
    2번부터는 봉우리가 _CROP_MIN_PEAK 미만이면 멈춘다 — 그보다 약한 곳은 모델이 근거로 삼았다고
    말하기 어렵다(상수 주석의 실측). 상자는 후보 영역을 1.5 배로 감싼 정사각형이고,
    짧은 변의 22~60% 로 맞춘다. 너무 작으면 주변 맥락이 사라지고, 너무 크면 확대가 아니다.
    앞서 고른 상자와 겹치는 후보는 버린다 — IoU 30% 이상이거나, 작은 쪽의 60% 이상이 큰 쪽 안에
    들어가면 겹친 것으로 본다. 상자 안의 상자는 IoU 가 낮아도 같은 곳을 한 번 더 보여 줄 뿐이다.
    """
    h_d, w_d = disp.shape[-2:]
    scale = 64 / max(w_d, h_d)
    gw, gh = max(4, round(w_d * scale)), max(4, round(h_d * scale))
    grid = F.interpolate(disp, size=(gh, gw), mode="bilinear", align_corners=False)
    work = grid.squeeze().cpu().numpy().astype(np.float32).copy()
    short = min(width, height)
    boxes: list[tuple[float, ...]] = []
    for _ in range(k * 4):
        peak = float(work.max())
        if peak < (_CROP_MIN_PEAK if boxes else 0.5) or len(boxes) >= k:
            break
        y, x = np.unravel_index(int(np.argmax(work)), work.shape)
        region = _flood(work >= 0.5 * peak, y, x)
        ys, xs = np.nonzero(region)
        bx0, bx1 = xs.min() / gw * width, (xs.max() + 1) / gw * width
        by0, by1 = ys.min() / gh * height, (ys.max() + 1) / gh * height
        side = min(max(max(bx1 - bx0, by1 - by0) * 1.5, 0.22 * short), 0.60 * short)
        cx, cy = (bx0 + bx1) / 2, (by0 + by1) / 2
        x0 = min(max(cx - side / 2, 0.0), width - side)
        y0 = min(max(cy - side / 2, 0.0), height - side)
        box = (x0, y0, x0 + side, y0 + side)
        work[region] = 0.0
        if all(not _boxes_overlap(box, b) for b in boxes):
            boxes.append((*box, peak))
    return boxes


def _flood(mask: np.ndarray, y: int, x: int) -> np.ndarray:
    """mask 안에서 (y, x) 와 상하좌우로 이어진 칸. 격자가 64 칸 남짓이라 그냥 돈다."""
    region = np.zeros_like(mask, dtype=bool)
    stack = [(y, x)]
    h, w = mask.shape
    while stack:
        cy, cx = stack.pop()
        if cy < 0 or cx < 0 or cy >= h or cx >= w or region[cy, cx] or not mask[cy, cx]:
            continue
        region[cy, cx] = True
        stack.extend(((cy + 1, cx), (cy - 1, cx), (cy, cx + 1), (cy, cx - 1)))
    return region


def _boxes_overlap(a, b) -> bool:
    ix0, iy0, ix1, iy1 = max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])
    inter = max(0.0, ix1 - ix0) * max(0.0, iy1 - iy0)
    area_a, area_b = (a[2] - a[0]) * (a[3] - a[1]), (b[2] - b[0]) * (b[3] - b[1])
    union = area_a + area_b - inter
    return (union > 0 and inter / union >= 0.3) or inter >= 0.6 * min(area_a, area_b)


def _crop_regions(cam: torch.Tensor | None, image: Image.Image):
    """CAM → (표시용 맵, 확대 상자들, 확대 이미지들). 모델과 무관하므로 락 밖에서 부른다."""
    if cam is None:
        return None, [], []
    width, height = image.size
    disp = _display_map(cam, width, height)
    boxes = _propose_crops(disp, width, height, HEATMAP_CROPS) if HEATMAP_CROPS > 0 else []
    return disp, boxes, [image.crop(tuple(round(v) for v in box[:4])) for box in boxes]


def _crop_forward(crops: list[Image.Image], tm: TaskModel) -> list[tuple[torch.Tensor, torch.Tensor]]:
    """확대 영역을 **한 장씩** forward 한다. 반환: [(logits, CAM 레이어 activation)].

    묶어서(배치) 넣지 않는다. 배치 크기가 바뀌면 CPU 합성곱이 다른 구현을 골라 같은 사진의 로짓이
    소수 넷째 자리에서 달라지고(8.12820 → 8.12831), 그 뒤 한 장짜리 추론까지 처음 몇 번은
    미세하게 흔들려 히트맵 JPEG 가 달라졌다 — "같은 사진이면 같은 히트맵"은 동시 요청 교차오염
    검사(tests/test_concurrent_heatmap.py)의 전제다. 크롭 정책을 잰 평가도 한 장씩 넣었다.
    """
    return [_forward_with_act(tm.transform(c).unsqueeze(0).to(device), tm) for c in crops]


def _crop_layercams(passes: list[tuple[torch.Tensor, torch.Tensor]], k: int) -> list[torch.Tensor]:
    """확대 영역마다 클래스 k 의 LayerCAM (1,1,h,w), 0~1.
    좌우 반전 평균은 하지 않는다 — 확대 영역에서는 적중이 92.0% → 92.3% 로 거의 같은데 비용이 두 배다.
    """
    return [_norm01(_layercam_for(logits, act, k)) for logits, act in passes]


def _zoom_display(disp: torch.Tensor, box, width: int, height: int) -> torch.Tensor:
    """전체 표시용 맵에서 box(원본 픽셀 좌표) 영역을 잘라 키운다 (1,1,s,s), 0~1.
    작업 해상도의 맵을 칸 단위로 자르면 상자 가장자리가 최대 한 칸씩 어긋나서 roi_align 으로
    소수 좌표 그대로 표본을 뽑는다."""
    hd, wd = disp.shape[-2:]
    sx, sy = wd / width, hd / height
    roi = torch.tensor([[0.0, box[0] * sx, box[1] * sy, box[2] * sx, box[3] * sy]], dtype=disp.dtype)
    side = _DISPLAY_WORK_SIDE
    return _to_p99(roi_align(disp, roi, output_size=(side, side), sampling_ratio=2, aligned=True))


def _top_k(probs: torch.Tensor, tm: TaskModel) -> list[dict]:
    """상위 5개. 클래스가 5개 미만인 태스크가 들어오면 topk 가 터지므로 min 으로 막아 둔다
    (지금의 두 태스크는 8·6 클래스라 그대로 5다)."""
    top = torch.topk(probs, k=min(5, len(tm.classes)))
    return [
        {
            "rank": i + 1,
            "disease_code": tm.classes[idx.item()],
            "disease_name_ko": tm.names_ko[tm.classes[idx.item()]],
            "confidence": round(prob.item(), 4),
        }
        for i, (prob, idx) in enumerate(zip(top.values, top.indices))
    ]


def get_task_model(task: str) -> TaskModel:
    """
    요청의 task 문자열을 로드된 TaskModel 로 바꾼다. 못 바꾸면 HTTP 오류로 끝낸다.

    400 과 503 을 가른다 — 모르는 이름은 호출하는 쪽(백엔드)의 버그라 고쳐 보내야 하고,
    등록은 됐는데 가중치가 없는 것은 이 서버의 배포 상태라 파일을 올리면 풀린다.
    503 본문에 어떤 파일이 없는지 적는 이유: 그 메시지만 보고 무엇을 올려야 하는지
    알 수 있어야 한다(/health 의 tasks.<name> 에도 같은 정보가 있다).
    """
    if task not in TASKS:
        raise HTTPException(
            status_code=400,
            detail=f"알 수 없는 분석 태스크입니다: {task!r} (가능한 값: {', '.join(TASKS)})")
    tm = TASK_MODELS.get(task)
    if tm is None:
        raise HTTPException(
            status_code=503,
            detail=(f"분석 태스크 '{task}' 의 모델이 로드되지 않았습니다 "
                    f"(가중치 파일: {_weights_path_for(task)}). "
                    f"사유: {TASK_LOAD_ERRORS.get(task, '알 수 없음')}"))
    return tm


def run_inference(image_bytes: bytes, task: str = DEFAULT_TASK) -> dict:
    """
    EfficientNet-B0 추론. confidence_level / top1 / top5 / heatmap_base64 / crops 포함 결과 반환.

    task 는 TASKS 의 키다. 생략하면 기본 태스크라 기존 호출은 한 글자도 안 바꿔도 된다.

    확대 보기(crops): 전체 히트맵이 짚은 곳을 최대 HEATMAP_CROPS 곳 골라 확대한 히트맵을 함께 준다.
    reinfer_crops 가 켜진 태스크는 확대 영역을 모델에 다시 넣어 그 영역 자체의 CAM 으로 그린다 —
    전체 CAM 은 224 입력의 14칸 격자라 확대 영역 하나에 서너 칸밖에 안 걸리지만, 다시 넣으면
    그 영역에 14칸이 온전히 걸린다. ISIC 정답 마스크로 확대 영역 안의 병변 적중을 재면
    전체 CAM 을 잘라 키운 것 88.3%, 다시 넣은 것 92.0% 다(2·3번째 영역은 80% → 92%).
    설명하는 클래스는 화면에 나가는 1순위다. 확대 영역이 따로 고른 클래스로 그리면 적중이 83.5% 로
    떨어지고, 무엇보다 화면의 병명과 다른 것을 설명하게 된다.

    확대 재판정: 원본만으로는 판단이 어려울 때(1순위 신뢰도 < LOW_CONFIDENCE_THRESHOLD) 확대
    영역들의 판정을 원본과 반반 섞어 다시 판정한다. 확대 영역만으로 판정하지 않는 이유는 그쪽이
    훨씬 못하기 때문이다(HAM 홀드아웃 2,004장: 원본 81.8%, 1번 영역만 67.7%). 낮은 신뢰도일 때만
    섞으면 81.8% → 82.3%, 악성을 양성으로 놓친 수 28 → 27 이다. 그 구간(363장)만 보면 59.8% → 62.5%.
    크지 않고 통계적으로 확실하지도 않다(McNemar p=0.31) — 그래서 확신 낮음 등급은 원본 기준으로
    그대로 둔다(아래 confidence_level).
    """
    tm = get_task_model(task)
    # EXIF 회전을 먼저 적용한다. 폰카 사진은 센서 방향 그대로 저장되고 회전 정보는
    # EXIF 태그에만 들어 있다. 이걸 무시하면 두 가지가 동시에 깨진다:
    #  (1) 모델이 누운 이미지를 본다 — 학습 증강은 ±15도 회전뿐이라 90도는 분포 밖이다.
    #  (2) 히트맵 JPEG 에는 EXIF 가 없는데 브라우저는 원본 blob 에 EXIF 를 적용하므로,
    #      "히트맵 보기 ↔ 원본 이미지" 토글이 서로 다른 방향의 사진을 보여준다.
    # 모델 입력과 오버레이 베이스가 같은 이미지를 쓰게 되므로 둘 다 사라진다.
    image = ImageOps.exif_transpose(Image.open(io.BytesIO(image_bytes))).convert("RGB")
    tensor = tm.transform(image).unsqueeze(0).to(device)

    # ── 모델을 만지는 구간은 한 번에 하나씩만 (TaskModel 의 lock 설명 참고) ──
    # 분류와 Grad-CAM 이 같은 forward 를 공유하므로 호출 하나가 통째로 락 안에 있다.
    with tm.lock:
        logits, cam, heatmap_quality = _compute_gradcam(tensor, tm)
        probs = torch.softmax(logits, dim=1)[0]
    pred = int(probs.argmax())
    full_results = _top_k(probs, tm)

    # 확신 낮음 등급은 **원본 사진의 판정**으로 매긴다. 재판정으로 0.45 를 넘긴 HAM 101장의 정답률은
    # 66% 로, 처음부터 0.45 이상이던 1,641장(87%)보다 한참 낮다 — 섞은 확률이 올랐다고 믿을 만해진 게 아니다.
    confidence_level = (
        CONFIDENCE_NORMAL if full_results[0]["confidence"] >= LOW_CONFIDENCE_THRESHOLD
        else CONFIDENCE_LOW
    )

    # ── 확대 영역 고르기 (모델과 무관 → 락 밖) ──
    disp, boxes, crop_images = _crop_regions(cam, image)

    # ── 확대 영역 재추론·재판정 (모델을 만지므로 다시 락 안) ──
    # 확대 영역 하나하나의 판정은 응답에 넣지 않는다. 혼자서는 원본보다 훨씬 못 맞혀서
    # (HAM 홀드아웃: 확대 영역 전부 54.8%, 원본 81.8%) 따로 보여 주면 오해를 부른다.
    final_probs, crop_cams, rejudged = probs, None, False
    if crop_images and tm.reinfer_crops:
        try:
            with tm.lock:
                passes = _crop_forward(crop_images, tm)
                if confidence_level == CONFIDENCE_LOW:
                    crop_probs = torch.cat([torch.softmax(out.detach(), dim=1) for out, _ in passes])
                    final_probs = 0.5 * probs + 0.5 * crop_probs.mean(dim=0)
                    rejudged = True
                final = int(final_probs.argmax())
                if final == pred:
                    crop_cams = _crop_layercams(passes, final)
                else:
                    # 1순위가 바뀌었다. 히트맵은 화면의 1순위를 설명해야 하므로 전체 CAM 을 새 1순위로 다시
                    # 구하고 확대 영역도 그 히트맵에서 다시 고른다 — 옛 1순위의 근거 자리를 새 병명 옆에 두지 않는다.
                    _, final_cam, final_quality = _compute_gradcam(tensor, tm, target=final)
            if final != pred:
                final_disp, final_boxes, final_crops = _crop_regions(final_cam, image)
                final_cams = None
                if final_crops:
                    with tm.lock:
                        final_cams = _crop_layercams(_crop_forward(final_crops, tm), final)
                # 끝까지 성공했을 때만 바꿔 끼운다 — 중간에 실패하면 원본 판정과 원본 히트맵이 짝으로 남는다.
                heatmap_quality, disp, boxes, crop_images, crop_cams = (
                    final_quality, final_disp, final_boxes, final_crops, final_cams)
        except Exception as e:
            print(f"[GradCAM] 확대 영역 재추론 실패 — 원본 판정과 잘라 키운 히트맵으로 대신 (분석 결과에는 영향 없음): {e}")
            final_probs, crop_cams, rejudged = probs, None, False

    results = _top_k(final_probs, tm) if rejudged else full_results

    # ── 히트맵 렌더링 (모델과 무관한 후처리 → 락 밖에서 병렬로 돈다) ──
    width, height = image.size
    heatmap_base64 = (_render_overlay(image, disp, 0, _HEATMAP_MAX_SIDE, "히트맵")
                      if disp is not None else None)
    crops = []
    for i, (box, crop) in enumerate(zip(boxes, crop_images)):
        crop_disp = (_display_map(crop_cams[i], *crop.size, smooth=_CROP_SMOOTH)
                     if crop_cams is not None else _zoom_display(disp, box, width, height))
        crop_heatmap = _render_overlay(crop, crop_disp, _CROP_MIN_SIDE, _CROP_MAX_SIDE, f"확대 히트맵 {i + 1}")
        if crop_heatmap is None:
            continue
        x0, y0, x1, y1, peak = box
        crops.append({
            "rank": len(crops) + 1,
            # 원본 이미지 기준 0~1. 화면이 전체 이미지 위에 상자를 그릴 때 쓴다(EXIF 회전 적용 뒤 기준).
            "box_norm": [round(x0 / width, 4), round(y0 / height, 4),
                         round(x1 / width, 4), round(y1 / height, 4)],
            "peak": round(peak, 3),
            "heatmap_base64": crop_heatmap,
        })

    return {
        # is_valid / message 를 더 이상 보내지 않는다. 신뢰도로 결과를 막지 않기 때문이다.
        # 백엔드의 구버전 매핑은 is_valid 가 없으면 "유효"로 해석하므로(널 기본값),
        # 배포가 엇갈려 구버전 백엔드 + 신버전 여기가 되어도 차단이 되살아나지 않는다.
        "confidence_level": confidence_level,
        "low_confidence_threshold": LOW_CONFIDENCE_THRESHOLD,
        "top1": results[0],
        "top5": results,
        "heatmap_base64": heatmap_base64,
        # 새 필드. Spring 은 FAIL_ON_UNKNOWN_PROPERTIES=false 가 기본이라
        # 백엔드 DTO 를 고치지 않아도 구버전 백엔드가 그대로 돈다.
        "heatmap_quality": heatmap_quality,
        "model_version": tm.model_version,
        # 어느 태스크의 답인지. 백엔드가 analysis_result.analysis_type 에 남긴다(계약 §3-3).
        # 위와 같은 이유로 구버전 백엔드는 이 필드를 무시하므로 추가해도 안전하다.
        "task": tm.name,
        # 아래도 같은 이유로 구버전 백엔드에는 보이지 않는다.
        "heatmap_style": HEATMAP_STYLE,
        "crops": crops,
        # 확대 재판정을 했을 때만 채운다: 원본 사진만 보고 낸 1순위. top1 과 다를 수 있다.
        "full_image_top1": full_results[0] if rejudged else None,
    }


# /health 는 시크릿을 요구하지 않는다 — 도커 헬스체크와 로드밸런서가 부르는 곳이고,
# 모델 정보 외에는 아무것도 내주지 않는다.
@app.get("/health")
def health():
    return {
        "status": "ok",
        "device": str(device),
        "low_confidence_threshold": LOW_CONFIDENCE_THRESHOLD,
        "model_version": MODEL_VERSION,
        "classes": CLASSES,
        # 배포된 히트맵 표시 기준을 눈으로 확인할 수 있게 노출한다.
        # 현장에서 색이 이상해 보일 때 코드를 열지 않고 먼저 여기를 본다.
        "heatmap_style": HEATMAP_STYLE,
        "heatmap_alpha_min": HEATMAP_ALPHA_MIN,
        "heatmap_alpha_max": HEATMAP_ALPHA_MAX,
        "heatmap_smooth": HEATMAP_SMOOTH,
        "heatmap_crops": HEATMAP_CROPS,
        "cam_layers": CAM_LAYER_NAMES,
        # 태스크별 상태. 위 최상위 키들은 기본 태스크의 값이고 기존 호출자(도커 헬스체크,
        # 백엔드 로그)가 읽으므로 그대로 둔다. 새 태스크는 여기에만 나타난다.
        # loaded=false 인 태스크의 error 가 "왜 503 인지"를 밖에서 볼 수 있는 유일한 창구다.
        "tasks": {
            name: {
                "loaded": name in TASK_MODELS,
                "model_version": (TASK_MODELS[name].model_version
                                  if name in TASK_MODELS else None),
                "classes": spec["classes"],
                "weights": _weights_path_for(name),
                "input_size": spec["input_size"],
                "reinfer_crops": bool(spec.get("reinfer_crops", False)),
                "error": TASK_LOAD_ERRORS.get(name),
            }
            for name, spec in TASKS.items()
        },
    }


@app.post("/predict", dependencies=[Depends(verify_internal_secret)])
def predict(file: UploadFile = File(...), task: str = Form(DEFAULT_TASK)):
    """
    Swagger / curl 직접 테스트용 (multipart)

    직접 호출할 때는 `X-Internal-Secret` 헤더에 INTERNAL_API_SECRET 값을 넣어야 한다.
    폼 필드 `task` 는 선택이다(기본 skin_lesion): `-F file=@x.jpg -F task=facial`

    `async def` 가 아니라 `def` 인 것이 중요하다. run_inference 는 동기 함수라
    `async def` 안에서 호출하면 추론이 끝날 때까지 이벤트 루프 전체가 멈춰
    /health 를 포함한 모든 요청이 함께 대기하게 된다.
    `def` 로 두면 FastAPI 가 알아서 스레드풀에서 실행한다.
    """
    # content_type 은 클라이언트가 안 보내면 None 이다 (그대로 .startswith 하면 500)
    if not (file.content_type or "").startswith("image/"):
        raise HTTPException(status_code=400, detail="이미지 파일만 허용됩니다.")
    contents = file.file.read()
    return run_inference(contents, task)


@app.post("/predict-base64", dependencies=[Depends(verify_internal_secret)])
def predict_base64(request: PredictRequest):
    """Spring Boot 내부 호출용 (JSON base64). 본문의 task 생략 시 기본 태스크."""
    image_bytes = base64.b64decode(request.image_base64)
    return run_inference(image_bytes, request.task)
