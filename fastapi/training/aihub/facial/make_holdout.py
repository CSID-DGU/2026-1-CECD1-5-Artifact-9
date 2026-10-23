#!/usr/bin/env python3
"""
manifest.csv 의 검증 분할(VS/VL)을 그대로 공식 홀드아웃 목록으로 적는다
→ fastapi/tests/baselines/facial-holdout.csv (열: image_id, label, view, src)

왜 AI허브의 분할을 그대로 쓰나
----------------------------
기본 태스크(skin_lesion)는 학습 노트북이 매번 새로 분할하고 결과를 저장하지 않은 탓에, 배포된
모델이 "어떤 사진을 안 봤는지"를 사후에 복원해야 했고 그 복원이 맞는지는 끝내 증명하지 못했다
(fastapi/tests/baselines/README.md). 그 뒤로 규칙은 "홀드아웃 목록은 저장소에 고정하고, 학습은
그 목록을 빼고, 평가는 그 목록으로만 한다"로 굳었다(fastapi/tests/README.md).

안면부 데이터는 AI허브가 이미 Training(TS/TL) / Validation(VS/VL) 으로 나눠서 준다. 그 분할을
그대로 홀드아웃으로 삼으면
  - 누구든 같은 데이터를 받아 같은 목록을 다시 만들 수 있고(재현 가능),
  - 시드·비율·알고리즘을 사람이 고를 여지가 없어 "유리한 분할을 골랐다"는 의심이 성립하지 않으며,
  - 데이터 제공자의 분할 의도(같은 인물·합성 시드의 누수 방지 등 우리가 모르는 기준)를 그대로 물려받는다.
학습 안에서 따로 검증 세트를 떼지 않고 이 목록을 검증 겸 홀드아웃으로 쓴다 — train.py 참고.

이 스크립트를 다시 돌릴 일은 데이터셋을 새로 받았을 때뿐이다. 목록이 바뀌면 baselines/ 의
facial-holdout-*.json 기준 성적은 다른 시험지로 잰 값이 되어 --compare 대상이 아니다. 그래서
파일이 이미 있으면 --force 없이는 덮어쓰지 않는다. 실제 데이터가 들어오기 전에는 이 파일을 만들어
커밋하지 않는다(tests/test_model_contract.py 는 없으면 건너뛴다).

사용법
------
    python3 make_holdout.py --manifest /data/facial/manifest.csv
    python3 make_holdout.py --manifest /data/facial/manifest.csv --out /tmp/facial-holdout.csv   # 시험 삼아
"""

from __future__ import annotations

import argparse
import csv
import sys
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from classes import CLASSES, TASK  # noqa: E402

# 저장소 안의 공식 위치. 계약(§3-5)과 test_model_contract.py [4] 가 이 이름을 찾는다.
DEFAULT_OUT = HERE.parents[2] / "tests" / "baselines" / f"{TASK}-holdout.csv"
HOLDOUT_COLUMNS = ["image_id", "label", "view", "src"]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--manifest", type=Path, required=True, help="build_manifest.py 가 만든 manifest.csv")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT,
                    help=f"출력 경로 (기본 {DEFAULT_OUT}). 시험할 때는 /tmp 아래로 돌릴 것")
    ap.add_argument("--force", action="store_true", help="이미 있는 목록을 덮어쓴다 (기준 성적과의 비교가 깨진다)")
    args = ap.parse_args()

    out = args.out.expanduser().resolve()
    if out.exists() and not args.force:
        print(f"❌ 이미 있다: {out}")
        print("   덮어쓰면 baselines/ 의 facial-holdout-*.json 과 비교가 깨진다. 데이터셋을 새로 받아 목록 자체를")
        print("   다시 정하는 것이 맞으면 --force 를 붙인다.")
        return 2

    with args.manifest.expanduser().open(encoding="utf-8-sig", newline="") as fh:
        reader = csv.DictReader(fh)
        need = {"image_id", "label", "view", "split", "src"}
        if reader.fieldnames is None or not need <= set(reader.fieldnames):
            raise SystemExit(f"manifest 열이 모자란다. 필요: {sorted(need)} / 있음: {reader.fieldnames}")
        rows = list(reader)

    val = [r for r in rows if r["split"] == "val"]
    if not val:
        raise SystemExit("manifest 에 split=val 행이 없다 — VS_/VL_ 폴더(zip)가 빠진 채 manifest 를 만들었는지 확인할 것. "
                         "AI허브 검증 분할이 없으면 공식 홀드아웃을 정할 수 없다")

    unknown = sorted({r["label"] for r in val} - set(CLASSES))
    if unknown:
        raise SystemExit(f"classes.py 에 없는 라벨이 있다: {unknown}")
    ids = Counter(r["image_id"] for r in val)
    dups = sorted(i for i, n in ids.items() if n > 1)
    if dups:
        raise SystemExit(f"image_id 중복 {len(dups)}건 (예: {dups[:5]}) — build_manifest.py 가 stem 충돌을 처리했는지 확인할 것")

    # 순서를 고정해 같은 manifest 면 바이트 단위로 같은 파일이 나오게 한다 (git diff 로 바뀐 줄만 보인다).
    val.sort(key=lambda r: (r["label"], r["view"], r["image_id"]))
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=HOLDOUT_COLUMNS, extrasaction="ignore")
        w.writeheader()
        w.writerows(val)

    train_n = sum(1 for r in rows if r["split"] == "train")
    print(f"홀드아웃 {len(val):,}장 → {out}   (학습 분할 {train_n:,}장)")
    by = Counter((r["label"], r["view"]) for r in val)
    views = sorted({v for _, v in by})
    print("  label      " + "".join((v or "-").rjust(8) for v in views) + "   total")
    for label in CLASSES:
        n = [by.get((label, v), 0) for v in views]
        print("  " + label.ljust(11) + "".join(f"{x:8,}" for x in n) + f"{sum(n):8,}")
    if out == DEFAULT_OUT:
        print("  이 파일은 가중치(facial.pth)·기준 성적 JSON 과 함께 커밋한다. 계약 테스트가 열·중복·라벨을 검사한다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
