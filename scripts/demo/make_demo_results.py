"""데모 사진 40장(frontend/public/demo/presets)을 운영과 같은 모델로 다시 돌려 결과·히트맵을 만든다.

모델 가중치가 바뀌면 이걸 다시 돌려 데모 결과를 맞춘다. 사진·설명(번호·진단명)은 그대로 두고 결과만 새로 쓴다.

  cd fastapi && INTERNAL_API_SECRET=local python ../scripts/demo/make_demo_results.py

출력: frontend/public/demo/results/*.jpg, frontend/src/demo/data/presets.json
"""
import base64, io, json, os, sys, time
from pathlib import Path
from PIL import Image

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "fastapi"))
os.chdir(REPO / "fastapi")
import main  # noqa: E402  (fastapi/main.py — 운영과 같은 추론·확대 재판정)

PRESETS = REPO / "frontend/public/demo/presets"
RESULTS = REPO / "frontend/public/demo/results"
DATA = REPO / "frontend/src/demo/data/presets.json"
RESULTS.mkdir(parents=True, exist_ok=True)


def jpeg(b64: str, max_side: int) -> bytes:
    img = Image.open(io.BytesIO(base64.b64decode(b64))).convert("RGB")
    s = min(1.0, max_side / max(img.size))
    if s < 1:
        img = img.resize((round(img.width * s), round(img.height * s)), Image.LANCZOS)
    out = io.BytesIO()
    img.save(out, "JPEG", quality=80, optimize=True, progressive=True)
    return out.getvalue()


presets = json.loads(DATA.read_text())
for p in presets:
    raw = (PRESETS / f"{p['key']}.jpg").read_bytes()
    t0 = time.perf_counter()
    r = main.run_inference(raw, p["task"])
    ms = round((time.perf_counter() - t0) * 1000)
    (RESULTS / f"{p['key']}-heatmap.jpg").write_bytes(jpeg(r["heatmap_base64"], 640))
    crops = []
    for c in r.get("crops") or []:
        (RESULTS / f"{p['key']}-crop{c['rank']}.jpg").write_bytes(jpeg(c["heatmap_base64"], 320))
        crops.append({"rank": c["rank"], "imageUrl": f"/demo/results/{p['key']}-crop{c['rank']}.jpg",
                      "boxNorm": c["box_norm"], "peak": c["peak"], "source": c["source"]})
    fit = r.get("full_image_top1")
    p["result"] = {
        "confidenceLevel": "low" if r["confidence_level"] == "low" else "normal",
        "top5": [{"rank": t["rank"], "diseaseCode": t["disease_code"], "diseaseNameKo": t["disease_name_ko"],
                  "confidence": round(float(t["confidence"]), 4)} for t in r["top5"]],
        "heatmapUrl": f"/demo/results/{p['key']}-heatmap.jpg",
        "heatmapStyle": r.get("heatmap_style"),
        "modelVersion": r["model_version"],
        "inferenceTimeMs": ms,
        "crops": crops,
        "fullImageTop1": None if not fit else {"diseaseCode": fit["disease_code"], "diseaseNameKo": fit["disease_name_ko"],
                                                 "confidence": round(float(fit["confidence"]), 4)},
    }
    top = p["result"]["top5"][0]
    print(f"{p['key']:10s} 정답 {p['expectedCode']:12s} → {top['diseaseCode']:12s} {top['confidence']:.3f}")
DATA.write_text(json.dumps(presets, ensure_ascii=False, indent=1))
print(len(presets), "presets")
