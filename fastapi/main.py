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


def run_inference(image_bytes: bytes) -> dict:
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

    return {
        "is_valid": is_valid,
        "message": None if is_valid else INVALID_IMAGE_MESSAGE,
        "threshold": MIN_TOP1_CONFIDENCE,
        "top1": results[0],
        "top5": results,
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
