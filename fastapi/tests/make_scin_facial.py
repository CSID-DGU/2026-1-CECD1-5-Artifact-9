"""
안면부 모델의 **실사진** 평가 세트 — SCIN 의 머리·목 사진 중 안면부 5개 질환 목록을 만들고 이미지를 받는다.

왜 필요한가
-----------
안면부 모델(facial)의 홀드아웃(baselines/facial-holdout.csv)은 AI허브 **합성** 이미지다. 그 점수(92.3%)는
합성 사진끼리의 점수라, 사람이 폰으로 찍은 얼굴에서 얼마나 맞히는지는 말해 주지 않는다. 실사진에서는
따로 재야 한다. 확대 재판정 실험 때 실험 스크립트로 잰 "SCIN 실사진 192장 24.5%"가 그 첫 숫자였지만
목록이 남지 않아 다시 낼 수 없었다. 이 스크립트가 목록을 고정한다 — holdout.csv 와 같은 이유다.

SCIN(Google Skin Condition Image Network)은 미국 성인이 자기 폰으로 찍어 올린 피부 사진에 피부과
전문의가 라벨을 붙인 공개 데이터다. https://github.com/google-research-datasets/scin

고르는 규칙
-----------
- 사례(case)의 촬영 부위에 머리·목(body_parts_head_or_neck)이 있다.
- 피부과 전문의 가중 라벨(weighted_skin_condition_label)에서 가장 큰 것이 아래 다섯 중 하나다.
      Acne → acne, Eczema → atopic, Psoriasis → psoriasis, Rosacea → rosacea,
      Seborrheic Dermatitis → seb_derm
- 정상 피부(normal)는 없다 — SCIN 은 증상이 있어서 올린 사진만 모은 데이터다. 그래서 이 세트에서는
  모델이 '정상 피부'라고 답하면 전부 오답이다. 정상 피부에서의 성능은 이 세트로 잴 수 없다.
- 같은 사진이 여러 사례에 걸려 있으면 한 줄만 남긴다. 사례마다 라벨이 다르면 그 사진은 뺀다.

열
--
image_id     SCIN 이미지 파일 이름(확장자 제외). evaluate.py 의 --id-col 기본값과 같다.
label        안면부 클래스 코드
case_id      SCIN 사례 ID. 한 사례에 사진이 1~3장이라 사진끼리 독립이 아니다.
label_weight 1순위 라벨의 가중치(0~1). 전문의끼리 얼마나 같은 의견이었는지다.
shot_type    CLOSE_UP / AT_AN_ANGLE / AT_DISTANCE. 부스 셀카에 가장 가까운 것은 AT_DISTANCE 다.
head_only    촬영 부위가 머리·목뿐이면 1
fitzpatrick  환자가 답한 피부 유형(FST1~6)

사용법
------
    # 목록을 다시 만든다 (평소에는 할 일이 없다 — 커밋된 목록이 곧 정의다)
    python3 tests/make_scin_facial.py --out tests/baselines/facial-scin.csv

    # 목록의 이미지를 받는다 (약 300MB, 표준 라이브러리만 쓴다)
    python3 tests/make_scin_facial.py --list tests/baselines/facial-scin.csv --download /data/scin-facial

    # 그다음 평가
    python3 tests/evaluate.py --task facial --image-dir /data/scin-facial \\
        --csv tests/baselines/facial-scin.csv --label-col label --group-col shot_type
"""

import argparse
import csv
import io
import json
import sys
import urllib.request
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

BUCKET = "https://storage.googleapis.com/dx-scin-public-data"

SCIN_TO_FACIAL = {
    "Acne": "acne",
    "Eczema": "atopic",
    "Psoriasis": "psoriasis",
    "Rosacea": "rosacea",
    "Seborrheic Dermatitis": "seb_derm",
}

FIELDS = ["image_id", "label", "case_id", "label_weight", "shot_type", "head_only", "fitzpatrick"]


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()


def read_csv(name: str) -> list[dict]:
    return list(csv.DictReader(io.StringIO(fetch(f"{BUCKET}/dataset/{name}").decode("utf-8"))))


def build_rows() -> list[dict]:
    cases = read_csv("scin_cases.csv")
    labels = {r["case_id"]: r for r in read_csv("scin_labels.csv")}
    by_image: dict[str, list[dict]] = defaultdict(list)
    for c in cases:
        if c.get("body_parts_head_or_neck") != "YES":
            continue
        raw = (labels.get(c["case_id"]) or {}).get("weighted_skin_condition_label") or ""
        try:
            weighted = json.loads(raw.replace("'", '"')) if raw else {}
        except json.JSONDecodeError:
            continue
        if not weighted:
            continue
        name, weight = max(weighted.items(), key=lambda kv: kv[1])
        if name not in SCIN_TO_FACIAL:
            continue
        head_only = not any(v == "YES" for k, v in c.items()
                            if k.startswith("body_parts_") and k != "body_parts_head_or_neck")
        for i in (1, 2, 3):
            path = c.get(f"image_{i}_path")
            if not path:
                continue
            by_image[Path(path).stem].append({
                "image_id": Path(path).stem,
                "label": SCIN_TO_FACIAL[name],
                "case_id": c["case_id"],
                "label_weight": f"{float(weight):.3f}",
                "shot_type": c.get(f"image_{i}_shot_type") or "",
                "head_only": "1" if head_only else "0",
                "fitzpatrick": c.get("fitzpatrick_skin_type") or "",
            })
    rows, dropped = [], 0
    for image_id, found in by_image.items():
        if len({r["label"] for r in found}) > 1:
            dropped += 1
            continue
        rows.append(found[0])
    rows.sort(key=lambda r: (r["case_id"], r["image_id"]))
    if dropped:
        print(f"사례마다 라벨이 달라 뺀 사진 {dropped}장")
    return rows


def download(list_path: Path, out_dir: Path) -> int:
    out_dir.mkdir(parents=True, exist_ok=True)
    with open(list_path, newline="", encoding="utf-8") as f:
        ids = [r["image_id"] for r in csv.DictReader(f)]
    todo = [i for i in ids if not (out_dir / f"{i}.png").is_file()]
    print(f"{len(ids)}장 중 {len(todo)}장을 받는다 → {out_dir}")

    def one(image_id: str) -> str | None:
        try:
            (out_dir / f"{image_id}.png").write_bytes(fetch(f"{BUCKET}/dataset/images/{image_id}.png"))
            return None
        except Exception as e:  # 한 장 실패가 나머지를 막지 않게
            return f"{image_id}: {e}"

    with ThreadPoolExecutor(max_workers=8) as pool:
        errors = [e for e in pool.map(one, todo) if e]
    for e in errors:
        print(f"  실패 {e}")
    return 1 if errors else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, help="목록을 새로 만들어 이 경로에 쓴다")
    ap.add_argument("--list", type=Path, help="이미지를 받을 목록 (--download 와 함께)")
    ap.add_argument("--download", type=Path, help="목록의 이미지를 이 폴더로 받는다")
    args = ap.parse_args()
    if not args.out and not args.download:
        ap.error("--out 또는 --download 중 하나는 필요하다")

    if args.out:
        rows = build_rows()
        with open(args.out, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=FIELDS)
            w.writeheader()
            w.writerows(rows)
        print(f"{len(rows)}장, 사례 {len({r['case_id'] for r in rows})}건 → {args.out}")
        print(f"  라벨 {dict(Counter(r['label'] for r in rows))}")
        print(f"  구도 {dict(Counter(r['shot_type'] for r in rows))}")
    if args.download:
        return download(args.list or args.out, args.download)
    return 0


if __name__ == "__main__":
    sys.exit(main())
