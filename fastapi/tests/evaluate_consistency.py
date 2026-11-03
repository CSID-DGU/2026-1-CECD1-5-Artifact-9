"""
같은 사람을 조금 다르게 찍어도 같은 답이 나오는가 — 전처리 정책별로 정확도와 일관성을 함께 잰다.

왜 필요한가
-----------
evaluate.py 는 "사진 한 장을 얼마나 맞히는가"를 잰다. 부스처럼 여러 사람이 자기 손으로 찍는 곳에서는
그것만으로 모자라다. 같은 사람이 두 번 찍었는데 1순위가 바뀌면, 둘 중 하나가 맞았더라도 결과를 믿을 수
없다. 이 스크립트는 그 흔들림을 숫자로 만든다.

촬영이 달라지는 경우를 흉내 낸 변형마다, 변형한 사진의 1순위가 원래 사진의 1순위와 같은지 센다.

  mirror      좌우 반전 — 전면 카메라의 미러링 설정에 따라 같은 셀카가 뒤집혀 저장된다
  tilt±8      손이 8° 기울었다 (회전으로 생긴 빈 모서리는 실제 촬영에 없으므로 가운데 85% 만 남긴다)
  dark/bright 조명 밝기 ±20%
  closer      10% 가까이서 찍었다
  offset      구도가 조금 어긋났다 (90% 창을 옆·위로 5% 옮김)
  portrait    같은 장면을 세로 3:4 로 찍었다
  landscape   같은 장면을 가로 4:3 으로 찍었다
  jpeg70      폰마다 다른 압축 (JPEG 품질 70 으로 다시 저장)

전처리 정책
-----------
  squash  지금 서빙 — 어떤 비율이든 정사각형 입력 크기로 늘이거나 줄인다(main.TaskModel.transform).
          휴대폰 세로 사진(3:4)은 가로로 1.33배 늘어난다. 안면부 학습 사진은 전부 정사각형이고 학습 증강의
          비율 폭은 0.85~1.18 이라, 그 밖의 늘어남은 모델이 본 적이 없다.
  center  가운데 정사각형을 잘라낸 뒤 같은 transform. 늘어남은 없고 가장자리가 잘린다.
  pad     짧은 쪽을 ImageNet 평균색으로 채워 정사각형으로 만든 뒤 같은 transform. 아무것도 자르지 않는다.

정책을 서빙에 넣을지는 이 표로 정한다. 정책 함수는 여기에만 있다 — 채택하면 main.py 로 옮기고, 이
스크립트는 그쪽을 불러 쓰도록 바꾼다(evaluate.py 가 전처리를 복사하지 않는 것과 같은 이유).

tta 열은 원본과 좌우 반전의 확률을 평균한 판정이다. main.py 의 _compute_gradcam 은 히트맵 잡음을 줄이려고
이미 매 요청 좌우 반전을 한 번 더 넣는다 — 분류에 쓰면 추가 계산이 들지 않는 후보라 함께 잰다.
(배치로 넣으므로 확률이 서빙의 한 장짜리 추론과 소수 넷째 자리에서 다를 수 있다. 통계에는 영향이 없다.)

사용법
------
    # 안면부 실사진(SCIN) — 목록과 이미지는 tests/make_scin_facial.py 가 만든다
    python tests/evaluate_consistency.py --task facial --image-dir /data/scin-facial \\
        --csv tests/baselines/facial-scin.csv --label-col label --group-col shot_type \\
        --json tests/baselines/facial-consistency-$(date +%F).json
    ... --policies squash center    # 일부 정책만
    ... --limit 50                  # 빨리 감 잡기

    # 같은 사람을 여러 번 찍은 사진 — 부스 리허설용. 라벨이 필요 없다. 하위 폴더 하나 = 한 사람.
    #   /data/selfies/kim/1.jpg 2.jpg 3.jpg ...   /data/selfies/lee/...
    python tests/evaluate_consistency.py --task facial --same-person-dir /data/selfies
    한 사람의 사진들에서 가장 많이 나온 1순위와 같은 답을 낸 비율(일치율)을 사람마다 내고 평균한다.
    합성 변형이 아니라 실제로 다시 찍은 사진이라, 부스에서 방문자가 두 번 찍었을 때의 흔들림에 가장 가깝다.
"""

import argparse
import io
import json
import math
import sys
import time
from collections import Counter
from pathlib import Path

import evaluate as ev  # 같은 폴더. main.py 임포트 준비(작업 폴더·시크릿)와 데이터 읽기를 그대로 쓴다.
import torch
from PIL import Image, ImageEnhance, ImageOps

serving = ev.serving

POLICIES = ("squash", "center", "pad")

# ImageNet 평균색. 정규화하면 0 근처가 되어 모델에게 '아무 내용 없음'에 가장 가깝다.
PAD_RGB = (124, 116, 104)


def to_square(img: Image.Image, policy: str) -> Image.Image:
    if policy == "squash":
        return img
    w, h = img.size
    if policy == "center":
        s = min(w, h)
        left, top = (w - s) // 2, (h - s) // 2
        return img.crop((left, top, left + s, top + s))
    if policy == "pad":
        s = max(w, h)
        canvas = Image.new("RGB", (s, s), PAD_RGB)
        canvas.paste(img, ((s - w) // 2, (s - h) // 2))
        return canvas
    raise ValueError(policy)


def crop_frac(img: Image.Image, frac: float, dx: float = 0.0, dy: float = 0.0) -> Image.Image:
    """가운데 기준 frac 크기의 창을 창 크기 대비 dx·dy 만큼 옮겨 자른다."""
    w, h = img.size
    cw, ch = round(w * frac), round(h * frac)
    left = min(max(0.0, (w - cw) / 2 + dx * cw), w - cw)
    top = min(max(0.0, (h - ch) / 2 + dy * ch), h - ch)
    return img.crop((round(left), round(top), round(left) + cw, round(top) + ch))


def crop_aspect(img: Image.Image, aspect: float) -> Image.Image:
    """가운데에서 가로/세로 비율이 aspect 인 가장 큰 창을 자른다 — 같은 장면을 다른 비율로 찍은 셈이다."""
    w, h = img.size
    if w / h > aspect:
        nw = round(h * aspect)
        left = (w - nw) // 2
        return img.crop((left, 0, left + nw, h))
    nh = round(w / aspect)
    top = (h - nh) // 2
    return img.crop((0, top, w, top + nh))


def tilt(img: Image.Image, degrees: float) -> Image.Image:
    return crop_frac(img.rotate(degrees, resample=Image.BILINEAR), 0.85)


def recompress(img: Image.Image, quality: int) -> Image.Image:
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=quality)
    buf.seek(0)
    return Image.open(buf).convert("RGB")


PERTURBATIONS = {
    "mirror": ImageOps.mirror,
    "tilt+8": lambda im: tilt(im, 8),
    "tilt-8": lambda im: tilt(im, -8),
    "dark": lambda im: ImageEnhance.Brightness(im).enhance(0.8),
    "bright": lambda im: ImageEnhance.Brightness(im).enhance(1.2),
    "closer": lambda im: crop_frac(im, 0.9),
    "offset": lambda im: crop_frac(im, 0.9, dx=0.05, dy=-0.05),
    "portrait": lambda im: crop_aspect(im, 3 / 4),
    "landscape": lambda im: crop_aspect(im, 4 / 3),
    "jpeg70": lambda im: recompress(im, 70),
}


def mcnemar_p(b: int, c: int) -> float:
    """정확 이항 McNemar(양측). b·c 는 한쪽만 맞힌 건수다."""
    n = b + c
    if n == 0:
        return 1.0
    k = min(b, c)
    return min(1.0, 2 * sum(math.comb(n, i) for i in range(k + 1)) / 2 ** n)


def probs_for(images: list[Image.Image]) -> torch.Tensor:
    """(이미지 수, 클래스 수) 확률. 서빙과 같은 transform·같은 model 객체를 쓴다."""
    batch = torch.stack([ev.TASK.transform(im) for im in images]).to(serving.device)
    with torch.no_grad():
        return torch.softmax(ev.TASK.model(batch), dim=1).cpu()


def summarize(labels, base_probs, variant_top1, threshold) -> dict:
    """base_probs: (N, C). variant_top1: {변형 이름: [N 개의 1순위 인덱스]}."""
    classes = ev.CLASSES
    n = len(labels)
    top1 = base_probs.argmax(dim=1).tolist()
    top3 = base_probs.topk(min(3, len(classes)), dim=1).indices.tolist()
    conf = base_probs.max(dim=1).values.tolist()
    gold = [classes.index(l) for l in labels]
    correct = [p == g for p, g in zip(top1, gold)]
    agree = {name: sum(v == b for v, b in zip(tops, top1)) / n for name, tops in variant_top1.items()}
    unstable = sum(1 for i in range(n) if any(tops[i] != top1[i] for tops in variant_top1.values()))
    recall = {}
    for c in sorted(set(labels)):
        idx = [i for i, l in enumerate(labels) if l == c]
        recall[c] = round(sum(correct[i] for i in idx) / len(idx), 4)
    predicted = Counter(classes[p] for p in top1)
    return {
        "n": n,
        "accuracy": round(sum(correct) / n, 4),
        "top3": round(sum(g in t for g, t in zip(gold, top3)) / n, 4),
        "recall": recall,
        "predicted": dict(predicted.most_common()),
        "mean_confidence": round(sum(conf) / n, 4),
        "warning_rate": round(sum(c < threshold for c in conf) / n, 4),
        "agreement": {k: round(v, 4) for k, v in agree.items()},
        "mean_agreement": round(sum(agree.values()) / len(agree), 4),
        "unstable_rate": round(unstable / n, 4),
        "_correct": correct,
    }


def print_table(title: str, rows: dict) -> None:
    names = list(PERTURBATIONS)
    print(f"\n[{title}]")
    print(f"{'':16s} {'정확도':>7s} {'top3':>6s} {'경고율':>6s} {'평균일치':>7s} {'흔들림':>6s}  "
          + " ".join(f"{n:>9s}" for n in names))
    for key, r in rows.items():
        print(f"{key:16s} {r['accuracy']:7.1%} {r['top3']:6.1%} {r['warning_rate']:6.1%} "
              f"{r['mean_agreement']:7.1%} {r['unstable_rate']:6.1%}  "
              + " ".join(f"{r['agreement'][n]:9.1%}" for n in names))
    for key, r in rows.items():
        print(f"  {key:14s} 재현율 {r['recall']}  1순위 분포 {r['predicted']}")


def same_person(args) -> int:
    """하위 폴더 하나 = 한 사람. 사람마다 가장 많이 나온 1순위와 같은 답을 낸 사진의 비율을 낸다."""
    folders = sorted(d for d in args.same_person_dir.iterdir() if d.is_dir())
    people = {d.name: sorted(p for p in d.iterdir() if p.suffix.lower() in ev.IMAGE_SUFFIXES) for d in folders}
    people = {k: v for k, v in people.items() if len(v) >= 2}
    if not people:
        raise SystemExit("사진이 2장 이상인 하위 폴더가 없습니다 (하위 폴더 하나 = 한 사람).")
    print(f"{len(people)}명, 사진 {sum(map(len, people.values()))}장, 태스크 {ev.TASK.name}, 모델 {ev.TASK.model_version}")
    result = {"task": ev.TASK.name, "model_version": ev.TASK.model_version, "people": len(people), "rows": {}}
    print(f"\n{'':16s} {'평균 일치율':>9s} {'전부 일치':>8s}  1순위 분포")
    for policy in args.policies:
        for mode in ("plain", "tta"):
            agreements, all_same, predicted, per_person = [], 0, Counter(), {}
            for name, paths in people.items():
                imgs = [to_square(ImageOps.exif_transpose(Image.open(p)).convert("RGB"), policy) for p in paths]
                probs = probs_for(imgs + [ImageOps.mirror(i) for i in imgs])
                k = len(imgs)
                p = probs[:k] if mode == "plain" else (probs[:k] + probs[k:]) / 2
                tops = [ev.CLASSES[i] for i in p.argmax(dim=1).tolist()]
                mode_code, count = Counter(tops).most_common(1)[0]
                agreements.append(count / k)
                all_same += count == k
                predicted.update(tops)
                per_person[name] = tops
            key = f"{policy}/{mode}"
            row = {"mean_agreement": round(sum(agreements) / len(agreements), 4),
                   "all_same_rate": round(all_same / len(people), 4),
                   "predicted": dict(predicted.most_common()), "per_person": per_person}
            result["rows"][key] = row
            print(f"{key:16s} {row['mean_agreement']:9.1%} {row['all_same_rate']:8.1%}  {row['predicted']}")
    if args.json:
        args.json.write_text(json.dumps(result, ensure_ascii=False, indent=1))
        print(f"저장: {args.json}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--task", default=serving.DEFAULT_TASK)
    ap.add_argument("--image-dir", type=Path, nargs="+")
    ap.add_argument("--same-person-dir", type=Path, help="하위 폴더 하나 = 한 사람. 라벨 없이 반복 촬영 일치율만 낸다")
    ap.add_argument("--csv", type=Path, help="이미지 ID·라벨 목록. 없으면 하위 폴더명이 라벨이다")
    ap.add_argument("--id-col", default="image_id")
    ap.add_argument("--label-col", default="dx")
    ap.add_argument("--group-col", help="이 CSV 열의 값별로도 따로 낸다 (예: facial-scin.csv 의 shot_type)")
    ap.add_argument("--policies", nargs="+", default=list(POLICIES), choices=POLICIES)
    ap.add_argument("--limit", type=int)
    ap.add_argument("--json", type=Path)
    args = ap.parse_args()

    if args.task not in serving.TASK_MODELS:
        raise SystemExit(f"태스크 '{args.task}' 의 모델이 로드되지 않았습니다: "
                         f"{serving.TASK_LOAD_ERRORS.get(args.task, '사유 불명')}")
    ev.TASK = serving.TASK_MODELS[args.task]
    ev.CLASSES = ev.TASK.classes
    if args.same_person_dir:
        return same_person(args)
    if not args.image_dir:
        ap.error("--image-dir 또는 --same-person-dir 중 하나가 필요하다")
    if args.csv:
        samples, missing, unknown = ev.load_from_csv(args.csv, args.image_dir, args.id_col, args.label_col)
    else:
        samples, missing, unknown = ev.load_from_dirs(args.image_dir)
    if missing:
        print(f"⚠ 파일을 못 찾은 항목 {len(missing)}건은 건너뜁니다")
    if unknown:
        print(f"⚠ CLASSES 에 없는 라벨은 건너뜁니다: {dict(unknown)}")
    samples = samples[: args.limit]
    if not samples:
        raise SystemExit("평가할 이미지가 하나도 없습니다.")
    groups = ev.read_groups(args.csv, args.id_col, args.group_col) if args.group_col else {}
    threshold = serving.LOW_CONFIDENCE_THRESHOLD

    print(f"{len(samples)}장, 태스크 {ev.TASK.name}, 모델 {ev.TASK.model_version}, "
          f"입력 {ev.TASK.input_size}, 경고선 {threshold}, 정책 {args.policies}")
    names = list(PERTURBATIONS)
    labels, keys = [], []
    # 정책 × (plain|tta) 마다: 원본 확률 목록, 변형별 1순위 목록
    base = {(p, m): [] for p in args.policies for m in ("plain", "tta")}
    tops = {(p, m): {n: [] for n in names} for p in args.policies for m in ("plain", "tta")}
    t0 = time.time()
    for count, (path, label) in enumerate(samples, 1):
        # 서빙과 같은 EXIF 회전 처리(main.run_inference)
        img = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
        variants = [img] + [PERTURBATIONS[n](img) for n in names]
        labels.append(label)
        keys.append(groups.get(path.name) or groups.get(path.stem, ""))
        for policy in args.policies:
            squared = [to_square(v, policy) for v in variants]
            probs = probs_for(squared + [ImageOps.mirror(s) for s in squared])
            k = len(squared)
            for mode, p in (("plain", probs[:k]), ("tta", (probs[:k] + probs[k:]) / 2)):
                base[(policy, mode)].append(p[0])
                for j, n in enumerate(names, 1):
                    tops[(policy, mode)][n].append(int(p[j].argmax()))
        if count % 25 == 0:
            print(f"  {count}/{len(samples)}  {time.time() - t0:.0f}s", flush=True)

    def block(idx: list[int]) -> dict:
        out = {}
        for (policy, mode), rows in base.items():
            probs = torch.stack([rows[i] for i in idx])
            variant = {n: [tops[(policy, mode)][n][i] for i in idx] for n in names}
            out[f"{policy}/{mode}"] = summarize([labels[i] for i in idx], probs, variant, threshold)
        return out

    def paired(rows: dict) -> dict:
        """지금 서빙(squash/plain) 대비 McNemar. 사진 단위라 한 사례의 여러 장이 독립이 아니다 — p 는 낙관적이다."""
        ref = rows["squash/plain"]["_correct"] if "squash/plain" in rows else None
        out = {}
        for key, r in rows.items():
            if ref is None or key == "squash/plain":
                continue
            b = sum(1 for x, y in zip(ref, r["_correct"]) if x and not y)
            c = sum(1 for x, y in zip(ref, r["_correct"]) if y and not x)
            out[key] = {"current_only": b, "this_only": c, "p": round(mcnemar_p(b, c), 4)}
        return out

    all_rows = block(list(range(len(labels))))
    print_table("전체", all_rows)
    comparisons = paired(all_rows)
    for key, m in comparisons.items():
        print(f"  vs squash/plain  {key:14s} 지금만 맞힘 {m['current_only']:3d} · 이쪽만 맞힘 {m['this_only']:3d} · p={m['p']}")
    result = {
        "task": ev.TASK.name,
        "model_version": ev.TASK.model_version,
        "input_size": ev.TASK.input_size,
        "threshold": threshold,
        "perturbations": names,
        "all": {k: {kk: vv for kk, vv in v.items() if not kk.startswith("_")} for k, v in all_rows.items()},
        "mcnemar_vs_current": comparisons,
    }
    if groups:
        result["by_group"] = {}
        for g in sorted(Counter(keys)):
            idx = [i for i, k in enumerate(keys) if k == g]
            rows = block(idx)
            print_table(f"{g or '(그룹 없음)'} — {len(idx)}장", rows)
            result["by_group"][g] = {k: {kk: vv for kk, vv in v.items() if not kk.startswith("_")}
                                     for k, v in rows.items()}
    print(f"\n{time.time() - t0:.0f}s")
    if args.json:
        args.json.write_text(json.dumps(result, ensure_ascii=False, indent=1))
        print(f"저장: {args.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
