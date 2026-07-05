from fastapi import FastAPI, File, UploadFile, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import torch
import timm
from torchvision import transforms
from PIL import Image
import io
import base64
import os
import numpy as np
import torch.nn.functional as F

app = FastAPI(title="Artifact Medical AI", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

CLASSES = ["akiec", "bcc", "bkl", "df", "mel", "nv", "vasc"]
CLASS_NAMES_KO = {
    "akiec": "광선각화증/상피내암",
    "bcc":   "기저세포암",
    "bkl":   "양성 각화증성 병변",
    "df":    "피부섬유종",
    "mel":   "악성 흑색종",
    "nv":    "멜라닌세포모반",
    "vasc":  "혈관성 병변",
}

MIN_TOP1_CONFIDENCE = float(os.getenv("MIN_TOP1_CONFIDENCE", "0.45"))
INVALID_IMAGE_MESSAGE = (
    "피부 병변 이미지로 판단하기 어렵습니다. "
    "의료 이미지 또는 피부 병변이 명확히 보이는 사진을 업로드해 주세요."
)

device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

model = timm.create_model("efficientnet_b0", pretrained=False, num_classes=7)
model.load_state_dict(torch.load("model.pth", map_location=device))
model.to(device)
model.eval()
transform = transforms.Compose([
    transforms.Resize((224, 224)),
    transforms.ToTensor(),
    transforms.Normalize([0.485, 0.456, 0.406],
                         [0.229, 0.224, 0.225]),
])


class PredictRequest(BaseModel):
    image_base64: str


def _generate_gradcam_b64(model, tensor: torch.Tensor, orig_image: Image.Image) -> str | None:
    """
    GradCAM — 순수 PyTorch + Pillow (cv2 / grad-cam 라이브러리 불필요).
    실패 시 None 반환(분석 결과에는 영향 없음).
    """
    try:
        activation_store = {}

        def fwd_hook(*args):
            act = args[2]  # (1, C, H, W)
            activation_store['act'] = act
            act.register_hook(lambda g: activation_store.__setitem__('grad', g))

        handle = model.conv_head.register_forward_hook(fwd_hook)

        output = model(tensor)
        pred = output.argmax(dim=1).item()
        model.zero_grad()
        output[0, pred].backward()
        handle.remove()

        act  = activation_store['act'].detach()   # (1, C, H, W)
        grad = activation_store['grad'].detach()  # (1, C, H, W)

        weights = grad.mean(dim=(2, 3), keepdim=True)
        cam = F.relu((weights * act).sum(dim=1, keepdim=True))
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
        print(f"[GradCAM] 히트맵 생성 실패 (분석 결과에는 영향 없음): {e}")
        return None


def run_inference(image_bytes: bytes) -> dict:
    """EfficientNet-B0 추론. is_valid / top1 / top5 / heatmap_base64 포함 결과 반환."""
    image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    tensor = transform(image).unsqueeze(0).to(device)

    with torch.no_grad():
        outputs = model(tensor)
        probs = torch.softmax(outputs, dim=1)[0]

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

    # ── GradCAM (순수 PyTorch + Pillow 구현, cv2/grad-cam 라이브러리 불필요) ──
    heatmap_base64 = _generate_gradcam_b64(model, tensor, image)


    return {
        "is_valid": is_valid,
        "message": None if is_valid else INVALID_IMAGE_MESSAGE,
        "threshold": MIN_TOP1_CONFIDENCE,
        "top1": results[0],
        "top5": results,
        "heatmap_base64": heatmap_base64,
    }


@app.get("/health")
def health():
    return {
        "status": "ok",
        "device": str(device),
        "min_top1_confidence": MIN_TOP1_CONFIDENCE,
    }


@app.post("/predict")
async def predict(file: UploadFile = File(...)):
    """Swagger / curl 직접 테스트용 (multipart)"""
    if not file.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="이미지 파일만 허용됩니다.")
    contents = await file.read()
    return run_inference(contents)


@app.post("/predict-base64")
def predict_base64(request: PredictRequest):
    """Spring Boot 내부 호출용 (JSON base64)"""
    image_bytes = base64.b64decode(request.image_base64)
    return run_inference(image_bytes)
