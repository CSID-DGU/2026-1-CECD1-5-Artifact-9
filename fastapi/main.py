from fastapi import Depends, FastAPI, File, Header, UploadFile, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import hashlib
import hmac
import torch
import timm
from torchvision import transforms
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

CLASSES = ["akiec", "bcc", "bkl", "df", "mel", "nv", "vasc", "inflammatory"]
CLASS_NAMES_KO = {
    "akiec": "광선각화증/상피내암",
    "bcc":   "기저세포암",
    "bkl":   "양성 각화증성 병변",
    "df":    "피부섬유종",
    "mel":   "악성 흑색종",
    "nv":    "멜라닌세포모반",
    "vasc":  "혈관성 병변",
    "inflammatory": "염증성 피부질환",
}

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

model = timm.create_model("efficientnet_b0", pretrained=False, num_classes=8)
model.load_state_dict(torch.load("model.pth", map_location=device, weights_only=True))
model.to(device)
model.eval()

# 가중치 파일 자체의 해시로 버전을 만든다 — 사람이 적는 문자열 상수는 파일을 바꿔도
# 저절로 안 바뀌므로 언젠가 거짓말을 하게 된다. 해시는 model.pth 가 바뀌는 즉시,
# 그리고 그럴 때만 바뀐다.
with open("model.pth", "rb") as f:
    _model_hash = hashlib.sha256(f.read()).hexdigest()
MODEL_VERSION = f"efficientnet_b0-{_model_hash[:12]}"

# 모델은 서버 전체가 하나를 공유한다. Grad-CAM 은 그 model 객체에 forward hook 을
# 등록해 중간 activation 을 꺼내는 방식이라, A 요청이 hook 을 붙인 상태에서
# B 요청이 forward 를 돌리면 **A 의 hook 이 B 의 activation 으로 발화**한다.
# (B 의 forward 는 no_grad 라 "cannot register a hook on a tensor that doesn't
#  require gradient" 로 터지거나, 조용히 서로의 히트맵을 뒤바꾼다)
#
# 저장 위치를 요청별로 분리하는 것(contextvars 등)으로는 해결되지 않는다.
# hook 등록과 backward 자체가 공유 객체에 걸리므로, 모델을 건드리는 구간을
# 통째로 직렬화하는 것이 유일한 해법이다.
_model_lock = threading.Lock()

transform = transforms.Compose([
    transforms.Resize((224, 224)),
    transforms.ToTensor(),
    transforms.Normalize([0.485, 0.456, 0.406],
                         [0.229, 0.224, 0.225]),
])


class PredictRequest(BaseModel):
    image_base64: str


# =============================================
# 히트맵 표시 기준 — 환경변수로 뺀 이유
# =============================================
# 현장(부스/진료실)에서 색이 너무 옅다/짙다는 조정이 코드 수정 없이
# docker compose 재기동만으로 끝나야 한다. LOW_CONFIDENCE_THRESHOLD 와 같은 방식이다.
#
# DISPLAY_FLOOR: 이 값 미만의 CAM 은 **아예 칠하지 않는다**(원본 픽셀 그대로).
#   화면 범례가 "빨간색 = AI 가 진단 근거로 삼은 부위"라고 말하므로, 모델이 보지
#   않은 영역에 색이 묻으면 범례가 거짓말이 된다.
#   예전 구현은 0.5*원본 + 0.5*히트맵 **균일 알파**였고, jet 의 cam=0 이
#   (0,0,0.5) 중간 밝기 파랑이라 **안 본 영역이 전부 파랗게 덮였다**.
#   "병변이 짙은 파란색으로 보인다"는 증상의 직접적 원인이다.
# ALPHA_MAX: 가장 강한 지점의 최대 불투명도. 1.0 으로 올리면 병변 자체가 색에 가려
#   보이지 않는다 — 의사가 병변을 눈으로 확인해야 하므로 0.6 이 기본이다.
HEATMAP_DISPLAY_FLOOR = float(os.getenv("HEATMAP_DISPLAY_FLOOR", "0.35"))
HEATMAP_ALPHA_MAX = float(os.getenv("HEATMAP_ALPHA_MAX", "0.60"))

# 표시용 정규화의 하단 기준점. lo 분위를 0, 99 분위를 1 로 보낸다.
# conv_head Grad-CAM++ 를 쓸 때는 이 뺄셈이 결정적이었다 — 그 맵은 상위 절반이
# 최대값의 84% 에 몰려 있어 p99 로만 나누면 쓸 수 있는 대비가 0.16 밖에 안 남았다.
# 지금 쓰는 LayerCAM 은 희소해서 p50 이 거의 0 이고, 따라서 이 뺄셈은 사실상
# 무동작이다(실측 (p99-p50)/p99 의 p10 = 0.88). 그래도 남겨 둔다 — 올바른 일반형이고
# 격자 해상도에서만 돌아 비용이 0 이며, 나중에 다른 레이어/방식으로 바꿀 때 다시 필요하다.
_CAM_NORM_LO = 0.50


def _select_cam_layers() -> list[tuple[str, torch.nn.Module]]:
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

    그래서 해상도가 다른 **두 블록**을 쓴다(224 입력에서 14x14 + 28x28). conv_head 의
    7x7 을 버리므로 격자가 16 배 촘촘해진다 — 폰카 3024x4452 에서 한 칸이 430x630
    픽셀을 덮던 것이 108x159 로 줄어, "세밀하지 않다"는 증상에 직접 대응한다.

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
            model(torch.zeros(1, 3, 224, 224, device=device))
    finally:
        for h in handles:
            h.remove()

    layers: list[tuple[str, torch.nn.Module]] = []
    if sizes:
        final = sizes[max(sizes)]              # 마지막 블록 출력 = conv_head 입력 해상도
        for mult in (2, 4):                    # 224 입력에서 14x14, 28x28
            cand = sorted(i for i, s in sizes.items() if s == final * mult)
            if cand:
                layers.append((f"blocks.{cand[-1]}", model.blocks[cand[-1]]))
    if not layers:
        # 해상도가 올라가는 블록을 못 찾은 경우의 최후 수단. conv_head 로는 가지 않는다
        # (위 독스트링 참고 — 그 위치에서 LayerCAM 은 클래스를 보지 않는다).
        last = max(sizes) if sizes else len(model.blocks) - 1
        layers.append((f"blocks.{last}", model.blocks[last]))
    return layers


CAM_LAYERS = _select_cam_layers()
CAM_LAYER_NAMES = [name for name, _ in CAM_LAYERS]
print(f"[GradCAM] CAM 레이어: {CAM_LAYER_NAMES}")


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
    나머지 전부가 어두워진다. 격자 해상도(14x14~28x28)에서만 호출하므로 비용은 0 이다.
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


def _fuse_cams(maps: list[torch.Tensor]) -> torch.Tensor:
    """
    해상도가 다른 LayerCAM 들을 **가장 촘촘한 격자에서 산술평균**한다.
    반환값은 이미 0~1 로 정규화돼 있다 — 이후 렌더링/품질 계산은 다시 정규화하지 않는다.

    처음에는 곱(변조)으로 합쳤다. "둘 다 켜진 곳만 남으면 배경 오탐(침구·옷·주변 정상
    피부)이 줄어든다"는 생각이었다. 측정은 반대였다 — 곱은 한쪽이 0 인 곳을 통째로
    지우므로 **나쁜 쪽이 좋은 쪽을 깎는다.** 게다가 당시 곱셈의 주체였던 깊은 맵
    (conv_head Grad-CAM++)이 클래스를 보지 않는 맵이어서, 그 맵이 결과를 지배했다
    (융합 결과의 '엉뚱한 클래스' 상관 0.93 — 입력인 LayerCAM 의 0.26 보다 훨씬 나쁘다).

    deletion/insertion 충실도로 재서 골랐다(tests/evaluate_cam.py --faithfulness).
    3 출처 300 장 평균 score(= insertion AUC - deletion AUC, 높을수록 좋음):

        산술평균 (현재)                      +0.191
        곱 변조  blocks.4 x blocks.2         +0.151
        단일     blocks.4 LayerCAM           +0.145
        변경 전  conv_head Grad-CAM          +0.114
        기존 곱  conv_head++ x blocks.4      -0.043   <- 유일하게 음수

    음수는 "CAM 이 가리킨 곳을 지워도 예측이 안 무너진다"는 뜻이다. 산술평균은 세
    출처 전부에서, deletion·insertion **양방향 모두** 1 위였다.

    각 맵을 먼저 정규화하고 평균한 뒤 상단만 다시 맞춘다(lo=0). 두 맵의 peak 가 서로
    다른 픽셀에 있으면 평균의 최대값이 1 에 못 미쳐 alpha 예산을 다 쓰지 못하기 때문이다.
    """
    if len(maps) == 1:
        return _norm01(maps[0])

    target = max(maps, key=lambda m: m.shape[-1] * m.shape[-2])
    hw = (target.shape[-2], target.shape[-1])
    acc: torch.Tensor | None = None
    for m in maps:
        n = _norm01(m)
        if (n.shape[-2], n.shape[-1]) != hw:
            n = F.interpolate(n, size=hw, mode="bilinear", align_corners=False)
        acc = n if acc is None else acc + n
    return _norm01(acc / len(maps), lo=0.0)


def _cam_quality(cam: torch.Tensor) -> dict:
    """
    히트맵이 화면에서 어떻게 보이는지를 숫자로 남긴다.
    격자 해상도에서 계산하므로 비용은 사실상 0 이다.

    - focus_area : 실제로 색이 칠해지는 면적 비율. 화면에 보이는 것과 일치한다.
    - peak_ratio : 상위 10% 격자가 담은 CAM 에너지 비율. 집중도.

    **`level: focused|diffuse` 와 `contrast` 는 뺐다.** 둘 다 "이 히트맵을 믿어도
    되는가"를 단정하는 값이었는데, 실측이 뒷받침하지 않았다.

    - contrast = (p99-p50)/p99 는 LayerCAM 에서 p50 이 거의 0 이라 포화됐다.
      3 출처 240 장에서 p10 이 0.877 — 기존 기준 0.35 로는 전부 "focused" 가 된다.
    - 그래서 focus_area 로 갈라 봤지만, 충실도 score 와의 관계가 출처마다 **반대**였다.
      focus_area 사분위별 score: ham 은 넓을수록 오히려 올라가고(+0.246 -> +0.338),
      pad 는 내려가고(+0.171 -> +0.130), scin 은 혼재. peak_ratio 와 확신도도 같았다.

    품질을 가리지 못하는 값으로 "모델이 병변을 특정하지 못했다"고 화면에 쓰면 근거 없이
    신뢰를 조작하게 된다. 집중도 자체는 서술값으로 남기되 판정은 하지 않는다.
    히트맵을 믿을 수 있는지는 정답 마스크나 충실도 측정으로만 말할 수 있다.
    """
    v = cam.flatten()
    total = float(v.sum())
    if total <= 1e-8:
        return {"focus_area": 0.0, "peak_ratio": 0.0}
    k = max(1, int(round(0.1 * v.numel())))
    return {
        "focus_area": round(float((v >= HEATMAP_DISPLAY_FLOOR).float().mean()), 4),
        "peak_ratio": round(float(torch.topk(v, k).values.sum()) / total, 4),
    }


def _compute_gradcam(
    tensor: torch.Tensor,
) -> tuple[torch.Tensor, torch.Tensor | None, dict | None]:
    """
    forward 1회 + backward 1회로 logits 와 CAM 을 **함께** 구한다.
    반환: (logits, cam, quality). cam/quality 는 실패 시 None(분석 결과에는 영향 없음).

    quality 를 여기서 함께 만드는 이유: focus_area 가 표시 임계값
    (HEATMAP_DISPLAY_FLOOR)과 **같은 척도**여야 해서 _fuse_cams 가 0~1 로 맞춰 둔
    격자 해상도 CAM 을 봐야 하는데, 그 값이 살아 있는 곳은 이 함수 안뿐이다.
    업샘플 뒤에 재면 보간이 만든 값까지 면적에 섞인다.
    반드시 _model_lock 을 잡은 채로 호출한다.

    예전에는 no_grad forward 1회(분류용) + CAM 용 forward 1회를 돌렸다. 같은 입력에
    같은 모델이므로 결과는 동일하고 비용만 두 배였다. CPU 전용 인스턴스에서는 이
    낭비가 곧 응답 지연이라, 한 번만 돌려 둘 다 쓴다.

    grad 는 tensor hook 대신 torch.autograd.grad 로 받는다 — 파라미터 .grad 를
    건드리지 않으므로 요청 간 gradient 누적도, zero_grad 호출도 필요 없다.
    """
    store: dict[str, torch.Tensor] = {}
    handles = [
        layer.register_forward_hook(
            lambda _m, _i, out, name=name: store.__setitem__(name, out)
        )
        for name, layer in CAM_LAYERS
    ]
    try:
        logits = model(tensor)
    finally:
        # 예외가 나도 hook 은 반드시 떼어낸다.
        # (안 떼면 전역 model 에 hook 이 남아 이후 모든 요청이 계속 터진다)
        for h in handles:
            h.remove()

    cam = quality = None
    try:
        pred = int(logits.argmax(dim=1).item())
        acts = [store[name] for name in CAM_LAYER_NAMES]
        grads = torch.autograd.grad(logits[0, pred], acts)
        cam = _fuse_cams([_layercam(a, g).detach() for a, g in zip(acts, grads)])
        quality = _cam_quality(cam)
    except Exception as e:
        print(f"[GradCAM] CAM 계산 실패 (분석 결과에는 영향 없음): {e}")

    return logits.detach(), cam, quality


def _render_gradcam_overlay(cam: torch.Tensor, orig_image: Image.Image) -> str | None:
    """
    CAM 을 원본 해상도 오버레이 JPEG(base64)로 렌더링한다.
    모델을 전혀 건드리지 않으므로 **_model_lock 밖에서** 실행한다 —
    원본 해상도가 클수록(키오스크 폰카메라 사진) 이 구간이 길어지는데,
    락 안에 두면 그만큼 다른 요청이 통째로 대기하게 된다.

    입력 cam 은 _fuse_cams 가 격자 해상도에서 이미 0~1 로 맞춰 둔 값이다.
    정규화를 여기(원본 해상도)에서 하지 않는 이유: 보간이 만들어낸 값이 기준이 되어
    같은 CAM 인데 원본 해상도에 따라 색이 달라진다.
    """
    try:
        orig_w, orig_h = orig_image.size
        cam = F.interpolate(cam, size=(orig_h, orig_w), mode="bilinear", align_corners=False)
        t = cam.squeeze().cpu().numpy().astype(np.float32)

        # 표시 바닥 아래는 알파 0 → 원본 픽셀이 그대로 남는다.
        # 이어서 smoothstep(t^2*(3-2t)) 으로 올려 경계가 계단처럼 끊기지 않게 한다.
        # 전체 해상도 배열이라 제자리 연산으로 임시 배열 수를 줄인다 —
        # 폰카 사진(3024x4452) 한 장에서 (H,W) float32 하나가 이미 54MB 다.
        t -= HEATMAP_DISPLAY_FLOOR
        t /= (1.0 - HEATMAP_DISPLAY_FLOOR)
        np.clip(t, 0.0, 1.0, out=t)
        s = 3.0 - 2.0 * t
        t *= t
        t *= s
        del s
        a = t * HEATMAP_ALPHA_MAX

        # 순차 컬러맵: 연노랑 → 주황 → 빨강. 파란색을 쓰지 않는다.
        # jet 을 버린 이유는 위 HEATMAP_DISPLAY_FLOOR 주석에 적어두었다.
        # 채널별 제자리 합성 — 전체 해상도 중간 배열을 채널마다 만들면 안 된다.
        overlay = np.asarray(orig_image, dtype=np.float32) / 255.0
        for ch, (lo, hi) in enumerate(((1.0, 1.0), (0.95, 0.10), (0.40, 0.0))):
            color = lo + (hi - lo) * t
            color *= a
            overlay[..., ch] *= 1.0 - a
            overlay[..., ch] += color
        np.clip(overlay, 0.0, 1.0, out=overlay)

        buf = io.BytesIO()
        Image.fromarray((overlay * 255).astype(np.uint8)).save(buf, format="JPEG", quality=90)
        return base64.b64encode(buf.getvalue()).decode("utf-8")

    except Exception as e:
        print(f"[GradCAM] 히트맵 렌더링 실패 (분석 결과에는 영향 없음): {e}")
        return None


def run_inference(image_bytes: bytes) -> dict:
    """EfficientNet-B0 추론. confidence_level / top1 / top5 / heatmap_base64 포함 결과 반환."""
    # EXIF 회전을 먼저 적용한다. 폰카 사진은 센서 방향 그대로 저장되고 회전 정보는
    # EXIF 태그에만 들어 있다. 이걸 무시하면 두 가지가 동시에 깨진다:
    #  (1) 모델이 누운 이미지를 본다 — 학습 증강은 ±15도 회전뿐이라 90도는 분포 밖이다.
    #  (2) 히트맵 JPEG 에는 EXIF 가 없는데 브라우저는 원본 blob 에 EXIF 를 적용하므로,
    #      "히트맵 보기 ↔ 원본 이미지" 토글이 서로 다른 방향의 사진을 보여준다.
    # 모델 입력과 오버레이 베이스가 같은 이미지를 쓰게 되므로 둘 다 사라진다.
    image = ImageOps.exif_transpose(Image.open(io.BytesIO(image_bytes))).convert("RGB")
    tensor = transform(image).unsqueeze(0).to(device)

    # ── 모델을 만지는 구간은 한 번에 하나씩만 (위 _model_lock 주석 참고) ──
    # 분류와 Grad-CAM 이 같은 forward 를 공유하므로 호출 하나가 통째로 락 안에 있다.
    with _model_lock:
        logits, cam, heatmap_quality = _compute_gradcam(tensor)
        probs = torch.softmax(logits, dim=1)[0]

    top5 = torch.topk(probs, k=5)
    results = [
        {
            "rank": i + 1,
            "disease_code": CLASSES[idx.item()],
            "disease_name_ko": CLASS_NAMES_KO[CLASSES[idx.item()]],
            "confidence": round(prob.item(), 4),
        }
        for i, (prob, idx) in enumerate(zip(top5.values, top5.indices))
    ]
    top1_confidence = results[0]["confidence"]
    confidence_level = (
        CONFIDENCE_NORMAL if top1_confidence >= LOW_CONFIDENCE_THRESHOLD else CONFIDENCE_LOW
    )

    # ── GradCAM 렌더링 (모델과 무관한 후처리 → 락 밖에서 병렬로 돈다) ──
    heatmap_base64 = _render_gradcam_overlay(cam, image) if cam is not None else None

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
        "model_version": MODEL_VERSION,
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
        "heatmap_display_floor": HEATMAP_DISPLAY_FLOOR,
        "heatmap_alpha_max": HEATMAP_ALPHA_MAX,
        "cam_layers": CAM_LAYER_NAMES,
    }


@app.post("/predict", dependencies=[Depends(verify_internal_secret)])
def predict(file: UploadFile = File(...)):
    """
    Swagger / curl 직접 테스트용 (multipart)

    직접 호출할 때는 `X-Internal-Secret` 헤더에 INTERNAL_API_SECRET 값을 넣어야 한다.

    `async def` 가 아니라 `def` 인 것이 중요하다. run_inference 는 동기 함수라
    `async def` 안에서 호출하면 추론이 끝날 때까지 이벤트 루프 전체가 멈춰
    /health 를 포함한 모든 요청이 함께 대기하게 된다.
    `def` 로 두면 FastAPI 가 알아서 스레드풀에서 실행한다.
    """
    # content_type 은 클라이언트가 안 보내면 None 이다 (그대로 .startswith 하면 500)
    if not (file.content_type or "").startswith("image/"):
        raise HTTPException(status_code=400, detail="이미지 파일만 허용됩니다.")
    contents = file.file.read()
    return run_inference(contents)


@app.post("/predict-base64", dependencies=[Depends(verify_internal_secret)])
def predict_base64(request: PredictRequest):
    """Spring Boot 내부 호출용 (JSON base64)"""
    image_bytes = base64.b64decode(request.image_base64)
    return run_inference(image_bytes)
