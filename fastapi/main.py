from fastapi import Depends, FastAPI, File, Header, UploadFile, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import hashlib
import hmac
import torch
import timm
from torchvision import transforms
from PIL import Image
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

MIN_TOP1_CONFIDENCE = float(os.getenv("MIN_TOP1_CONFIDENCE", "0.45"))
INVALID_IMAGE_MESSAGE = (
    "피부 병변 이미지로 판단하기 어렵습니다. "
    "의료 이미지 또는 피부 병변이 명확히 보이는 사진을 업로드해 주세요."
)

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


def _compute_gradcam(tensor: torch.Tensor) -> torch.Tensor | None:
    """
    GradCAM 중 **모델을 건드리는 구간**. 반드시 _model_lock 을 잡은 채로 호출한다.
    반환값은 (1, 1, h, w) CAM 텐서. 실패 시 None(분석 결과에는 영향 없음).
    """
    activation_store = {}

    def fwd_hook(*args):
        act = args[2]  # (1, C, H, W)
        activation_store['act'] = act
        act.register_hook(lambda g: activation_store.__setitem__('grad', g))

    handle = model.conv_head.register_forward_hook(fwd_hook)
    try:
        output = model(tensor)
        pred = output.argmax(dim=1).item()
        model.zero_grad()
        output[0, pred].backward()

        act  = activation_store['act'].detach()   # (1, C, H, W)
        grad = activation_store['grad'].detach()  # (1, C, H, W)
    except Exception as e:
        print(f"[GradCAM] CAM 계산 실패 (분석 결과에는 영향 없음): {e}")
        return None
    finally:
        # 예외가 나도 hook 은 반드시 떼어낸다.
        # (안 떼면 전역 model 에 hook 이 남아 이후 모든 요청이 계속 터진다)
        handle.remove()

    weights = grad.mean(dim=(2, 3), keepdim=True)
    return F.relu((weights * act).sum(dim=1, keepdim=True))


def _render_gradcam_overlay(cam: torch.Tensor, orig_image: Image.Image) -> str | None:
    """
    CAM 을 원본 해상도 오버레이 JPEG(base64)로 렌더링한다.
    모델을 전혀 건드리지 않으므로 **_model_lock 밖에서** 실행한다 —
    원본 해상도가 클수록(키오스크 폰카메라 사진) 이 구간이 길어지는데,
    락 안에 두면 그만큼 다른 요청이 통째로 대기하게 된다.
    """
    try:
        # CAM을 원본 이미지 크기로 업샘플 (224×224 아님 → 원본과 동일 해상도 출력)
        orig_w, orig_h = orig_image.size
        cam = F.interpolate(cam, size=(orig_h, orig_w), mode="bilinear", align_corners=False)
        cam = cam.squeeze().cpu().numpy()
        cam = (cam - cam.min()) / (cam.max() - cam.min() + 1e-8)

        # Jet 컬러맵 (원본 크기)
        r = np.clip(1.5 - np.abs(4 * cam - 3), 0, 1)
        g = np.clip(1.5 - np.abs(4 * cam - 2), 0, 1)
        b = np.clip(1.5 - np.abs(4 * cam - 1), 0, 1)
        heatmap = np.stack([r, g, b], axis=-1)

        # 원본 이미지 그대로 사용 (리사이즈 없음 → 동일 해상도 보장)
        orig = np.array(orig_image, dtype=np.float32) / 255.0
        overlay = np.clip(0.5 * orig + 0.5 * heatmap, 0, 1)

        buf = io.BytesIO()
        Image.fromarray((overlay * 255).astype(np.uint8)).save(buf, format="JPEG", quality=90)
        return base64.b64encode(buf.getvalue()).decode("utf-8")

    except Exception as e:
        print(f"[GradCAM] 히트맵 렌더링 실패 (분석 결과에는 영향 없음): {e}")
        return None


def run_inference(image_bytes: bytes) -> dict:
    """EfficientNet-B0 추론. is_valid / top1 / top5 / heatmap_base64 포함 결과 반환."""
    image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    tensor = transform(image).unsqueeze(0).to(device)

    # ── 모델을 만지는 구간은 한 번에 하나씩만 (위 _model_lock 주석 참고) ──
    # 추론과 Grad-CAM 을 **둘 다** 락 안에 넣어야 한다.
    # 한쪽만 감싸면 A 의 hook 이 걸린 상태에서 B 의 forward 가 돌아가는 상황이 그대로 남는다.
    with _model_lock:
        with torch.no_grad():
            outputs = model(tensor)
            probs = torch.softmax(outputs, dim=1)[0]

        cam = _compute_gradcam(tensor)

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
    is_valid = top1_confidence >= MIN_TOP1_CONFIDENCE

    # ── GradCAM 렌더링 (모델과 무관한 후처리 → 락 밖에서 병렬로 돈다) ──
    heatmap_base64 = _render_gradcam_overlay(cam, image) if cam is not None else None

    return {
        "is_valid": is_valid,
        "message": None if is_valid else INVALID_IMAGE_MESSAGE,
        "threshold": MIN_TOP1_CONFIDENCE,
        "top1": results[0],
        "top5": results,
        "heatmap_base64": heatmap_base64,
        "model_version": MODEL_VERSION,
    }


# /health 는 시크릿을 요구하지 않는다 — 도커 헬스체크와 로드밸런서가 부르는 곳이고,
# 모델 정보 외에는 아무것도 내주지 않는다.
@app.get("/health")
def health():
    return {
        "status": "ok",
        "device": str(device),
        "min_top1_confidence": MIN_TOP1_CONFIDENCE,
        "model_version": MODEL_VERSION,
        "classes": CLASSES,
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
