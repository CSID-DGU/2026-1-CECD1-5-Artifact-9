"""
확대 재판정이 **화면에 나가는 1순위**를 어떻게 바꾸는지, run_inference 를 그대로 불러 잰다.

evaluate.py 와 무엇이 다른가
---------------------------
evaluate.py 는 분류기 한 번의 softmax 를 잰다 — 재판정 **전**의 점수다. 피부 병변 태스크는 원본
1순위 신뢰도가 LOW_CONFIDENCE_THRESHOLD 미만이면 히트맵이 고른 확대 영역들의 판정을 원본과 반반
섞어 1순위를 다시 매긴다(main.run_inference). 그래서 화면에 나가는 1순위의 점수는 히트맵·확대 영역
코드까지 거쳐야 나오고, 그 경로를 다시 짜면 언젠가 서빙과 어긋난다 — 여기서는 run_inference 를
한 장에 한 번 부르고 응답만 읽는다. 원본 1순위는 응답의 full_image_top1(재판정했을 때만 채워진다)
또는 top1 이고, 서빙 1순위는 top1 이다.

baselines/README.md ③ 「확대 재판정」 표의 "원본만"·"0.45 미만일 때만 반반" 두 줄이 이 스크립트의
original·served 다.

사용법
------
    # 결과 JSON 을 남기려면 baselines 를 마운트한다 — 마운트 없이 --json 을 주면 컨테이너와 함께 사라진다.
    docker compose run --rm -v /path/to/holdout:/data:ro \\
        -v "$PWD/fastapi/tests/baselines:/app/tests/baselines" fastapi \\
        python tests/evaluate_rejudge.py --image-dir /data/ham /data/scin \\
        --csv tests/baselines/holdout.csv --label-col label --group-col src \\
        --json tests/baselines/rejudge-$(date +%F).json
    ... --limit 300            # 빨리 감 잡기 (히트맵까지 그리므로 evaluate.py 보다 몇 배 느리다)
    ... --threshold 0.6        # 경고선을 바꾸면 재판정 범위도 바뀐다 — 바꾸기 전에 화면 1순위가 어떻게 되는지 본다
"""

import argparse
import json
import math
import sys
import time
from collections import Counter
from pathlib import Path

import evaluate as ev  # 같은 폴더. main.py 임포트 준비(작업 폴더·시크릿)와 데이터 읽기를 그대로 쓴다.

serving = ev.serving


def mcnemar_p(b: int, c: int) -> float:
    """정확 이항 McNemar(양측). b·c 는 한쪽만 맞힌 건수다."""
    n = b + c
    if n == 0:
        return 1.0
    k = min(b, c)
    tail = sum(math.comb(n, i) for i in range(k + 1)) / 2 ** n
    return min(1.0, 2 * tail)


def score(labels, preds) -> dict:
    n = len(labels)
    mel = [p for p, l in zip(preds, labels) if l == "mel"]
    danger = [p for p, l in zip(preds, labels) if l in ev.DANGEROUS]
    return {
        "n": n,
        "accuracy": round(sum(p == l for p, l in zip(preds, labels)) / n, 4) if n else None,
        "mel_recall": round(sum(p == "mel" for p in mel) / len(mel), 4) if mel else None,
        # 악성·전암을 양성으로 놓친 건수 — evaluate.py 의 downgraded 와 같은 정의다.
        "malignant_to_benign": sum(1 for p in danger if p not in ev.DANGEROUS),
    }


def compare(labels, original, served, rejudged, served_conf, threshold) -> dict:
    b = sum(1 for o, s, l in zip(original, served, labels) if o == l and s != l)
    c = sum(1 for o, s, l in zip(original, served, labels) if o != l and s == l)
    band = [i for i, r in enumerate(rejudged) if r]
    # 확신 낮음 등급을 원본 기준으로 남기는 근거: 재판정으로 섞은 신뢰도가 경고선을 넘긴 것들이
    # 처음부터 경고선 위였던 것만큼 믿을 만한가.
    crossed = [i for i in band if served_conf[i] >= threshold]
    confident = [i for i, r in enumerate(rejudged) if not r]
    return {
        "original": score(labels, original),
        "served": score(labels, served),
        "rejudged": len(band),
        "changed": sum(1 for o, s in zip(original, served) if o != s),
        "band_original": score([labels[i] for i in band], [original[i] for i in band]),
        "band_served": score([labels[i] for i in band], [served[i] for i in band]),
        "mcnemar": {"original_only": b, "served_only": c, "p": round(mcnemar_p(b, c), 4)},
        "crossed_served": score([labels[i] for i in crossed], [served[i] for i in crossed]),
        "confident_original": score([labels[i] for i in confident], [original[i] for i in confident]),
    }


def print_block(title: str, r: dict) -> None:
    o, s = r["original"], r["served"]
    print(f"\n[{title}] {o['n']}장 — 재판정 {r['rejudged']}장, 1순위가 바뀐 것 {r['changed']}장")
    print(f"  정확도        원본 {o['accuracy']:.1%} → 서빙 {s['accuracy']:.1%}")
    if o["mel_recall"] is not None:
        print(f"  mel 재현율    원본 {o['mel_recall']:.1%} → 서빙 {s['mel_recall']:.1%}")
    print(f"  악성→양성     원본 {o['malignant_to_benign']} → 서빙 {s['malignant_to_benign']}")
    if r["rejudged"]:
        print(f"  재판정 구간   원본 {r['band_original']['accuracy']:.1%} → 서빙 {r['band_served']['accuracy']:.1%}")
    m = r["mcnemar"]
    print(f"  McNemar       원본만 맞힘 {m['original_only']} · 서빙만 맞힘 {m['served_only']} · p={m['p']}")
    x, f = r["crossed_served"], r["confident_original"]
    if x["n"]:
        print(f"  재판정으로 경고선을 넘긴 {x['n']}장의 정확도 {x['accuracy']:.1%} "
              f"(처음부터 경고선 위였던 {f['n']}장은 {f['accuracy']:.1%})")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--task", default=serving.DEFAULT_TASK)
    ap.add_argument("--image-dir", type=Path, nargs="+", required=True)
    ap.add_argument("--csv", type=Path, help="이미지 ID·라벨 목록. 없으면 하위 폴더명이 라벨이다")
    ap.add_argument("--id-col", default="image_id")
    ap.add_argument("--label-col", default="dx")
    ap.add_argument("--group-col", help="이 CSV 열의 값별로도 따로 낸다 (예: holdout.csv 의 src)")
    ap.add_argument("--limit", type=int)
    ap.add_argument("--threshold", type=float,
                    help="LOW_CONFIDENCE_THRESHOLD 를 이 값으로 바꿔 잰다(경고선이자 재판정 문턱). 생략하면 서버 값")
    ap.add_argument("--json", type=Path)
    args = ap.parse_args()

    if args.threshold is not None:
        serving.LOW_CONFIDENCE_THRESHOLD = args.threshold  # run_inference 가 부를 때마다 읽는 전역이다
    if args.task not in serving.TASK_MODELS:
        raise SystemExit(f"태스크 '{args.task}' 의 모델이 로드되지 않았습니다: "
                         f"{serving.TASK_LOAD_ERRORS.get(args.task, '사유 불명')}")
    ev.TASK = serving.TASK_MODELS[args.task]
    ev.CLASSES = ev.TASK.classes
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

    print(f"{len(samples)}장, 태스크 {ev.TASK.name}, 모델 {ev.TASK.model_version}, "
          f"경고선 {serving.LOW_CONFIDENCE_THRESHOLD}, 재추론 {'켜짐' if ev.TASK.reinfer_crops else '꺼짐(확대 보기만)'}")
    labels, original, served, rejudged, served_conf, keys = [], [], [], [], [], []
    t0 = time.time()
    for n, (path, label) in enumerate(samples, 1):
        r = serving.run_inference(path.read_bytes(), ev.TASK.name)
        labels.append(label)
        served.append(r["top1"]["disease_code"])
        served_conf.append(r["top1"]["confidence"])
        original.append((r["full_image_top1"] or r["top1"])["disease_code"])
        rejudged.append(r["full_image_top1"] is not None)
        keys.append(groups.get(path.name) or groups.get(path.stem, ""))  # HAM metadata 의 id 는 확장자가 없다
        if n % 100 == 0:
            print(f"  {n}/{len(samples)}  {time.time() - t0:.0f}s", flush=True)

    result = {
        "task": ev.TASK.name,
        "model_version": ev.TASK.model_version,
        "serving_threshold": serving.LOW_CONFIDENCE_THRESHOLD,
        "heatmap_style": serving.HEATMAP_STYLE,
        "all": compare(labels, original, served, rejudged, served_conf, serving.LOW_CONFIDENCE_THRESHOLD),
    }
    print_block("전체", result["all"])
    if groups:
        result["by_group"] = {}
        for g in sorted(Counter(keys)):
            idx = [i for i, k in enumerate(keys) if k == g]
            pick = lambda xs: [xs[i] for i in idx]  # noqa: E731
            result["by_group"][g] = compare(pick(labels), pick(original), pick(served), pick(rejudged),
                                            pick(served_conf), serving.LOW_CONFIDENCE_THRESHOLD)
            print_block(g or "(그룹 없음)", result["by_group"][g])
    print(f"\n{time.time() - t0:.0f}s")
    if args.json:
        args.json.write_text(json.dumps(result, ensure_ascii=False, indent=1))
        print(f"저장: {args.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
