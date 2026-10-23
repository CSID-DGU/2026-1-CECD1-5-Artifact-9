#!/usr/bin/env python3
"""
범용 분류 학습기 — manifest.csv 와 <task>/classes.py 만 있으면 어디서나 돈다
(제공받은 GPU 클라우드 · Colab · 안심존(외부망 차단) · 맥 CPU/MPS).

설계 원칙 (docs/aihub-datasets-plan.md §2-1)
------------------------------------------
- 순수 파이썬 스크립트다. 서버가 바뀌어도 코드가 바뀌지 않게 한다. 노트북은 이 스크립트를 부르는 래퍼만 둔다.
- 의존성은 torch · torchvision · timm · Pillow · numpy 뿐. 혼동행렬·재현율은 numpy 로 직접 센다 —
  안심존처럼 pip 이 안 되는 곳에서 scikit-learn 하나 때문에 못 도는 일을 없애기 위해서다.
- 외부망이 없어도 돈다. 기본 초기값(--init repo)은 저장소의 fastapi/model.pth(피부 사진으로 이미 학습된
  EfficientNet-B0)에서 **백본만** 물려받고 분류 헤드는 새로 만든다 — 기본 태스크 노트북 셀 9 와 같은 방식.
  git clone 만 되면 따라오므로 다운로드가 필요 없다. --init imagenet 은 timm 다운로드를 시도하고,
  실패하면 경고 뒤 repo 로 폴백한다(죽지 않는다).
- 태스크를 모른다. 클래스 목록은 training/aihub/<task>/classes.py 의 CLASSES 를 importlib 로 읽는다.
  그 리터럴은 계약 테스트가 fastapi/main.py 와 순서까지 대조하므로, 여기서 나온 가중치의 출력 인덱스는
  서빙과 같다.

분할 규율 (fastapi/notebooks/README.md · tests/README.md 의 홀드아웃 규칙)
---------------------------------------------------------------------
- manifest 의 split==train 으로 학습하고 split==val 로 검증한다(AI허브가 준 분할).
- --holdout-csv 가 있으면 그 image_id 는 **절대 학습에 넣지 않고** 그 목록을 검증 세트로 쓴다
  (기본 태스크 노트북 셀 6 의 규칙). 목록의 id 가 manifest 에 하나도 없으면 멈춘다 — 다른 태스크의 목록을
  잘못 줬을 때 "아무것도 안 빼고" 조용히 학습이 끝나는 사고를 막기 위해서다.
- val 이 하나도 없으면 train 에서 계층 분할 15% 를 떼되, 그 목록을 out-dir 에 반드시 저장한다.
  저장하지 않는 분할은 금지다 — 저장 안 한 분할 때문에 배포 모델이 무엇을 안 봤는지 아무도 말할 수
  없게 된 전말이 tests/baselines/README.md 에 있다.

산출물 (<out-dir>/)
------------------
  <task>.pth                   timm.create_model('efficientnet_b0', num_classes=len(CLASSES)) 의 state_dict 그대로.
                               fastapi/main.py 가 load_state_dict 로 바로 읽는다. 검증 macro recall 이 가장 좋은 epoch.
  <task>-train-report.json     인자 · 환경 · 장수 · epoch 곡선 · best epoch · 클래스별 recall/precision · 혼동행렬 ·
                               가중치 sha256 앞 12자리(= 서빙의 model_version). 매 epoch 끝에 덮어써 둔다 —
                               세션이 끊겨도 곡선은 남는다.
  <task>-val-predictions.csv   image_id, label, pred, confidence (best 가중치로 검증 세트를 다시 돌린 결과)
  <task>-val-split.csv         val 이 없어 train 에서 떼어 냈을 때만. 이 목록이 그 학습의 홀드아웃이다.

사용법
------
    # GPU 클라우드 (V100)
    python3 train.py --manifest /data/facial/manifest.csv --out-dir /data/facial/run1 --num-workers 8

    # 디버그 (CPU, 작은 이미지, 1 epoch, 60장)
    python3 train.py --manifest manifest.csv --out-dir /tmp/run --epochs 1 --image-size 64 --device cpu --limit 60

    # 홀드아웃 목록을 학습에서 제외하고 검증으로 쓴다
    python3 train.py --manifest manifest.csv --out-dir run2 --holdout-csv ../../tests/baselines/facial-holdout.csv
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import importlib.util
import json
import os
import platform
import random
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

import contextlib

import numpy as np
try:
    import timm
except ImportError:  # pragma: no cover — 외부망이 막힌 환경에서 가장 먼저 부딪히는 벽
    # torchvision 의 efficientnet_b0 로 대체하지 않는다. 두 구현은 state_dict 키 이름이 달라서,
    # 여기서 torchvision 으로 학습한 가중치는 fastapi/main.py(timm) 가 load_state_dict 로 읽지 못한다.
    # 키를 리매핑해 맞추는 길도 있지만, 리매핑이 한 층이라도 어긋나면 서버는 뜨고 확률만 조용히 틀린다 —
    # 이 저장소가 가장 피하려는 실패 방식이다. timm 은 순수 파이썬 wheel(수 MB) 이라 반입이 쉽다.
    raise SystemExit(
        "timm 이 없습니다. 서빙(fastapi/main.py)과 같은 구현으로 학습해야 가중치가 그대로 로드되므로 timm 이 필수입니다.\n"
        "  인터넷이 되면 : pip install timm==1.0.11\n"
        "  외부망 차단   : 다른 PC 에서 `pip download timm==1.0.11 --no-deps -d wheels/` 로 받은 wheel 을 반입한 뒤\n"
        "                  pip install --no-index --find-links wheels/ timm   (timm 의 의존성 torch·torchvision·pyyaml·huggingface_hub·safetensors 도 같은 방식)"
    )
import torch
import torch.nn as nn
import torchvision
from PIL import Image
from torch.utils.data import DataLoader, Dataset
from torchvision import transforms

HERE = Path(__file__).resolve().parent          # fastapi/training/aihub
FASTAPI_DIR = HERE.parents[1]                   # fastapi/
REPO_WEIGHTS = FASTAPI_DIR / "model.pth"        # 기본 태스크(skin_lesion) 가중치 — 백본 초기값으로 쓴다

# 서빙(fastapi/main.py TaskModel.transform)과 같은 정규화 상수. 검증 전처리는 서빙과 한 글자도 달라선 안 된다.
IMAGENET_MEAN = [0.485, 0.456, 0.406]
IMAGENET_STD = [0.229, 0.224, 0.225]


# ─────────────────────────────────────────────────────────────────────────────
# 태스크 · 환경
# ─────────────────────────────────────────────────────────────────────────────
def load_task_classes(task: str) -> tuple[list[str], dict[str, str]]:
    """training/aihub/<task>/classes.py 의 CLASSES / NAMES_KO. 패키지가 아니어도 되게 파일 경로로 읽는다."""
    path = HERE / task / "classes.py"
    if not path.is_file():
        raise SystemExit(f"태스크 '{task}' 의 클래스 파일이 없다: {path}\n"
                         f"  training/aihub/<task>/classes.py 에 TASK / CLASSES 리터럴을 선언해야 한다(계약 §3-5)")
    spec = importlib.util.spec_from_file_location(f"aihub_{task}_classes", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)                 # type: ignore[union-attr]
    classes = list(mod.CLASSES)
    if getattr(mod, "TASK", task) != task:
        raise SystemExit(f"{path} 의 TASK={mod.TASK!r} 가 --task {task!r} 와 다르다")
    if len(set(classes)) != len(classes) or not classes:
        raise SystemExit(f"{path} 의 CLASSES 가 비었거나 중복이 있다: {classes}")
    return classes, dict(getattr(mod, "NAMES_KO", {}))


def pick_device(name: str) -> torch.device:
    if name != "auto":
        return torch.device(name)
    if torch.cuda.is_available():
        return torch.device("cuda")
    mps = getattr(torch.backends, "mps", None)
    if mps is not None and mps.is_available():
        return torch.device("mps")
    return torch.device("cpu")


def env_summary(device: torch.device) -> dict:
    gpu = torch.cuda.get_device_name(0) if device.type == "cuda" else ""
    return {
        "python": platform.python_version(),
        "torch": torch.__version__,
        "torchvision": torchvision.__version__,
        "timm": timm.__version__,
        "device": str(device),
        "gpu": gpu,
        "cpu_count": os.cpu_count(),
        "platform": platform.platform(),
    }


def seed_everything(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)


# ─────────────────────────────────────────────────────────────────────────────
# manifest · 분할
# ─────────────────────────────────────────────────────────────────────────────
def read_manifest(path: Path, classes: list[str]) -> list[dict]:
    with path.open(encoding="utf-8-sig", newline="") as fh:
        reader = csv.DictReader(fh)
        need = {"image_id", "path", "label", "split"}
        if reader.fieldnames is None or not need <= set(reader.fieldnames):
            raise SystemExit(f"manifest 열이 모자란다. 필요: {sorted(need)} / 있음: {reader.fieldnames}")
        rows = list(reader)
    if not rows:
        raise SystemExit(f"manifest 가 비어 있다: {path}")
    unknown = Counter(r["label"] for r in rows if r["label"] not in classes)
    if unknown:
        raise SystemExit(f"classes.py 에 없는 라벨: {dict(unknown)} — manifest 와 태스크가 맞는지 확인할 것")
    ids = Counter(r["image_id"] for r in rows)
    dups = [i for i, n in ids.items() if n > 1]
    if dups:
        raise SystemExit(f"image_id 중복 {len(dups)}건 (예: {dups[:3]}) — 홀드아웃 제외를 id 로 하므로 겹치면 안 된다")
    # 경로가 다른 기계에서 만든 manifest 인 경우를 바로 잡는다 (첫 배치에서 터지면 그때까지 기다린 시간이 아깝다).
    missing = [r["path"] for r in rows if not os.path.isfile(r["path"])]
    if missing:
        raise SystemExit(f"manifest 의 이미지 {len(missing):,}개가 없다 (예: {missing[0]}).\n"
                         f"  manifest 는 학습할 기계에서 build_manifest.py 로 다시 만든다(경로가 절대 경로다)")
    for r in rows:
        r.setdefault("view", "")
        r.setdefault("src", "")
    return rows


def read_holdout_ids(path: Path) -> set[str]:
    with path.open(encoding="utf-8-sig", newline="") as fh:
        reader = csv.DictReader(fh)
        if reader.fieldnames is None or "image_id" not in reader.fieldnames:
            raise SystemExit(f"홀드아웃 CSV 에 image_id 열이 없다: {path} (열: {reader.fieldnames})")
        return {r["image_id"] for r in reader}


def stratified_take(rows: list[dict], n: int, rng: random.Random) -> list[dict]:
    """클래스마다 고르게 n 장을 뽑는다 (--limit 디버그용, 15% 분할용). 섞은 뒤 클래스별 순번으로 정렬해 앞에서 자른다."""
    shuffled = rows[:]
    rng.shuffle(shuffled)
    rank: Counter = Counter()
    keyed = []
    for r in shuffled:
        keyed.append((rank[r["label"]], r))
        rank[r["label"]] += 1
    keyed.sort(key=lambda t: t[0])
    return [r for _, r in keyed[:n]]


def make_splits(rows: list[dict], holdout_csv: Path | None, out_dir: Path, task: str,
                seed: int, val_frac: float = 0.15) -> tuple[list[dict], list[dict], dict]:
    """(train_rows, val_rows, 설명 dict). 독스트링 대신 모듈 머리말 '분할 규율' 참고."""
    info: dict = {"holdout_csv": str(holdout_csv) if holdout_csv else None}
    if holdout_csv is not None:
        holdout = read_holdout_ids(holdout_csv)
        hit = [r for r in rows if r["image_id"] in holdout]
        if not hit:
            raise SystemExit(f"홀드아웃 목록({holdout_csv})의 image_id 가 manifest 에 하나도 없다 — "
                             f"다른 태스크의 목록이거나 image_id 규칙이 다르다. 이대로 학습하면 아무것도 제외되지 않는다")
        missing = len(holdout) - len(hit)
        if missing:
            print(f"  ⚠️  홀드아웃 목록 {len(holdout):,}장 중 {missing:,}장이 manifest 에 없다 — "
                  f"데이터 구성이 목록과 다르다. 평가(evaluate.py)는 목록 전체로 하므로 장수가 안 맞게 된다")
        train_rows = [r for r in rows if r["image_id"] not in holdout and r["split"] == "train"]
        dropped = sum(1 for r in rows if r["image_id"] not in holdout and r["split"] != "train")
        if dropped:
            print(f"  홀드아웃에도 train 에도 없는 행 {dropped:,}장은 쓰지 않는다 (split 이 val 인데 목록에 없음)")
        info.update(val_source="holdout_csv", holdout_count=len(holdout), holdout_found=len(hit),
                    holdout_missing=missing)
        return train_rows, hit, info

    train_rows = [r for r in rows if r["split"] == "train"]
    val_rows = [r for r in rows if r["split"] == "val"]
    if val_rows:
        info.update(val_source="manifest_split_val")
        return train_rows, val_rows, info

    # val 이 없다 — 떼되 반드시 파일로 남긴다.
    if not train_rows:
        raise SystemExit("manifest 에 split 이 train 인 행이 없다")
    n_val = max(len(set(r["label"] for r in train_rows)), int(round(len(train_rows) * val_frac)))
    val_rows = stratified_take(train_rows, n_val, random.Random(seed))
    val_ids = {r["image_id"] for r in val_rows}
    train_rows = [r for r in train_rows if r["image_id"] not in val_ids]
    split_path = out_dir / f"{task}-val-split.csv"
    with split_path.open("w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=["image_id", "label", "view", "src"], extrasaction="ignore")
        w.writeheader()
        w.writerows(sorted(val_rows, key=lambda r: (r["label"], r["view"], r["image_id"])))
    print(f"  ⚠️  manifest 에 val 이 없어 train 에서 {len(val_rows):,}장({val_frac:.0%}, seed {seed})을 뗐다 → {split_path}")
    print(f"     이 목록이 이 학습의 홀드아웃이다. 가중치를 쓰려면 이 파일을 tests/baselines/{task}-holdout.csv 로 함께 커밋할 것")
    info.update(val_source="stratified_from_train", val_split_csv=str(split_path), val_frac=val_frac)
    return train_rows, val_rows, info


# ─────────────────────────────────────────────────────────────────────────────
# 데이터
# ─────────────────────────────────────────────────────────────────────────────
def build_transforms(image_size: int):
    # 학습 증강은 기본 태스크 노트북 셀 8 과 같은 계열이다. 직전 실험이 3 epoch 째부터 검증 손실이 올라가(과적합)
    # 크롭·색을 흔들어 같은 사진을 매번 조금 다르게 보여 준다. 얼굴 사진도 상하 뒤집기가 병변의 의미를 바꾸지 않고
    # (합성 데이터라 촬영 방향이 고정돼 있어 오히려 다양성이 부족하다) 그대로 둔다.
    train_tf = transforms.Compose([
        transforms.RandomResizedCrop(image_size, scale=(0.7, 1.0), ratio=(0.85, 1.18)),
        transforms.RandomHorizontalFlip(),
        transforms.RandomVerticalFlip(),
        transforms.RandomRotation(15),
        transforms.ColorJitter(brightness=0.2, contrast=0.2, saturation=0.2, hue=0.03),
        transforms.ToTensor(),
        transforms.Normalize(IMAGENET_MEAN, IMAGENET_STD),
    ])
    # 검증 전처리 = 서빙 전처리(fastapi/main.py TaskModel.transform). 여기서 재는 점수가 서비스 점수여야 한다.
    val_tf = transforms.Compose([
        transforms.Resize((image_size, image_size)),
        transforms.ToTensor(),
        transforms.Normalize(IMAGENET_MEAN, IMAGENET_STD),
    ])
    return train_tf, val_tf


class ManifestDataset(Dataset):
    def __init__(self, rows: list[dict], class_to_idx: dict[str, int], tf):
        self.rows = rows
        self.class_to_idx = class_to_idx
        self.tf = tf

    def __len__(self) -> int:
        return len(self.rows)

    def __getitem__(self, i: int):
        r = self.rows[i]
        with Image.open(r["path"]) as im:
            x = self.tf(im.convert("RGB"))
        return x, self.class_to_idx[r["label"]]


def _worker_init(worker_id: int) -> None:
    # 워커마다 다른 시드 — 같은 증강이 워커 수만큼 복제되는 것을 막는다.
    s = torch.initial_seed() % 2**32
    random.seed(s + worker_id)
    np.random.seed((s + worker_id) % 2**32)


def class_weights(train_rows: list[dict], classes: list[str], w_cap: float) -> tuple[torch.Tensor, dict]:
    # 역빈도 가중치에 상한을 둔다(노트북 셀 7). 직전 실험에서 표본 100여 장인 클래스의 가중치가 10.71 까지 올라
    # 손실이 그 몇 장에 끌려다니며 그 클래스의 재현율이 0.963 → 0.667 로 오히려 무너졌다. 희소 클래스를 살리되
    # 학습이 그쪽으로 휘둘리지는 않게 한다. 안면부 데이터는 클래스당 장수가 같아 보통 전부 1.0 이 나온다.
    counts = np.array([sum(1 for r in train_rows if r["label"] == c) for c in classes], dtype=np.float64)
    w = counts.sum() / (len(classes) * np.maximum(counts, 1))
    w = np.minimum(w, w_cap)
    return torch.tensor(w, dtype=torch.float32), {c: round(float(x), 3) for c, x in zip(classes, w)}


# ─────────────────────────────────────────────────────────────────────────────
# 모델
# ─────────────────────────────────────────────────────────────────────────────
def inherit_backbone(model: nn.Module, path: Path) -> tuple[int, list[str]]:
    """
    path 의 state_dict 에서 이름과 모양이 같은 텐서만 가져온다(노트북 셀 9). 분류 헤드(classifier.*)는 클래스 수가
    달라 모양이 안 맞으므로 자동으로 빠지고 새로 초기화된 채 남는다 — 피부 사진에서 특징을 뽑는 능력은 그대로
    가져오고 병명을 고르는 마지막 층만 다시 배운다.
    """
    old = torch.load(path, map_location="cpu", weights_only=True)
    if isinstance(old, dict) and "state_dict" in old and isinstance(old["state_dict"], dict):
        old = old["state_dict"]
    cur = model.state_dict()
    usable = {k: v for k, v in old.items() if k in cur and cur[k].shape == v.shape}
    skipped = [k for k in old if k not in usable]
    if not usable:
        raise SystemExit(f"{path} 에서 물려받을 수 있는 텐서가 하나도 없다 — efficientnet_b0 의 state_dict 가 아니다")
    model.load_state_dict(usable, strict=False)
    return len(usable), skipped


def build_model(num_classes: int, init: str, init_weights: Path | None) -> tuple[nn.Module, dict]:
    model = timm.create_model("efficientnet_b0", pretrained=False, num_classes=num_classes)
    info: dict = {"requested": init if init_weights is None else "file"}

    if init_weights is not None:
        n, skipped = inherit_backbone(model, init_weights)
        info.update(source=str(init_weights), inherited_tensors=n, reinitialized=skipped)
        print(f"  초기값: {init_weights} 에서 {n}개 텐서 물려받음 (새로 초기화: {skipped})")
        return model, info

    if init == "imagenet":
        try:
            model = timm.create_model("efficientnet_b0", pretrained=True, num_classes=num_classes)
            info.update(source="timm:efficientnet_b0 (imagenet)")
            print("  초기값: ImageNet 사전학습(timm 다운로드)")
            return model, info
        except Exception as exc:  # 네트워크·프록시·허브 오류 종류가 많아 넓게 받는다. 어쨌든 학습은 계속돼야 한다.
            print(f"  ⚠️  ImageNet 가중치 다운로드 실패({type(exc).__name__}: {str(exc)[:120]}) — "
                  f"저장소 가중치(--init repo)로 폴백")
            info["imagenet_error"] = f"{type(exc).__name__}: {str(exc)[:200]}"
            init = "repo"

    if init == "repo":
        if not REPO_WEIGHTS.is_file():
            raise SystemExit(f"저장소 가중치가 없다: {REPO_WEIGHTS} — git clone 이 온전한지 확인하거나 --init none / "
                             f"--init-weights <경로> 를 쓸 것")
        n, skipped = inherit_backbone(model, REPO_WEIGHTS)
        info.update(source=str(REPO_WEIGHTS), inherited_tensors=n, reinitialized=skipped)
        print(f"  초기값: {REPO_WEIGHTS.name}(기본 태스크 백본)에서 {n}개 텐서 물려받음 (새로 초기화: {skipped})")
        return model, info

    info.update(source="random")
    print("  초기값: 무작위 (--init none)")
    return model, info


# ─────────────────────────────────────────────────────────────────────────────
# 지표 — numpy 만으로
# ─────────────────────────────────────────────────────────────────────────────
def confusion(labels: np.ndarray, preds: np.ndarray, n: int) -> np.ndarray:
    cm = np.zeros((n, n), dtype=np.int64)       # 행 = 실제, 열 = 예측
    np.add.at(cm, (labels, preds), 1)
    return cm


def per_class_metrics(cm: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    support = cm.sum(1)
    predicted = cm.sum(0)
    diag = np.diag(cm)
    recall = np.divide(diag, support, out=np.zeros(len(cm)), where=support > 0)
    precision = np.divide(diag, predicted, out=np.zeros(len(cm)), where=predicted > 0)
    return recall, precision, support


def macro_recall(cm: np.ndarray) -> float:
    # 검증 세트에 없는 클래스(--limit 디버그, 일부 클래스만 받은 데이터)는 평균에서 뺀다 — 0 으로 넣으면
    # 아무리 잘해도 상한이 깎여 best 선택이 왜곡된다.
    recall, _, support = per_class_metrics(cm)
    present = support > 0
    return float(recall[present].mean()) if present.any() else 0.0


# ─────────────────────────────────────────────────────────────────────────────
# 학습 루프
# ─────────────────────────────────────────────────────────────────────────────
def run_epoch(model, loader, criterion, optimizer, device, scaler, use_amp, training: bool):
    model.train(training)
    total_loss, n = 0.0, 0
    preds, labels, confs = [], [], []
    for x, y in loader:
        x = x.to(device, non_blocking=True)
        y = y.to(device, non_blocking=True)
        # autocast 는 cuda 에서만 만든다 — CPU 전용 빌드에서 device_type="cuda" 를 주면 enabled=False 여도 경고가 난다.
        amp_ctx = torch.autocast(device_type="cuda", dtype=torch.float16) if use_amp else contextlib.nullcontext()
        with torch.set_grad_enabled(training):
            with amp_ctx:
                out = model(x)
                loss = criterion(out, y)
            if training:
                optimizer.zero_grad(set_to_none=True)
                scaler.scale(loss).backward()
                scaler.step(optimizer)
                scaler.update()
        total_loss += loss.item() * len(y)
        n += len(y)
        prob = out.detach().float().softmax(1)
        conf, pred = prob.max(1)
        preds.append(pred.cpu().numpy())
        labels.append(y.cpu().numpy())
        confs.append(conf.cpu().numpy())
    cat = (lambda a: np.concatenate(a) if a else np.zeros(0, dtype=np.int64))
    return total_loss / max(n, 1), cat(preds), cat(labels), cat(confs)


def sha256_12(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()[:12]


def write_json(path: Path, obj: dict) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


# ─────────────────────────────────────────────────────────────────────────────
def parse_args() -> argparse.Namespace:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--manifest", type=Path, required=True, help="build_manifest.py 가 만든 manifest.csv")
    ap.add_argument("--task", default="facial", help="training/aihub/<task>/classes.py 를 읽는다 (기본 facial)")
    ap.add_argument("--out-dir", type=Path, required=True)
    ap.add_argument("--epochs", type=int, default=15)
    ap.add_argument("--batch-size", type=int, default=32)
    ap.add_argument("--image-size", type=int, default=224, help="서빙 input_size 와 같아야 한다 (기본 224)")
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--weight-decay", type=float, default=1e-4)
    ap.add_argument("--label-smoothing", type=float, default=0.0,
                    help="노트북은 의사 투표 라벨이라 0.05 를 썼다. 합성 데이터는 라벨이 결정적이라 기본 0")
    ap.add_argument("--init", choices=["repo", "imagenet", "none"], default="repo",
                    help="repo: fastapi/model.pth 백본 물려받기(기본, 오프라인 가능) / imagenet: timm 다운로드(실패 시 repo) / none: 무작위")
    ap.add_argument("--init-weights", type=Path, help="이 파일에서 백본을 물려받는다 (--init 보다 우선)")
    ap.add_argument("--device", default="auto", help="auto|cuda|mps|cpu")
    ap.add_argument("--num-workers", type=int, default=4)
    ap.add_argument("--holdout-csv", type=Path, help="이 목록의 image_id 는 학습에서 제외하고 검증으로 쓴다")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--w-cap", type=float, default=5.0, help="클래스 가중치 상한 (노트북 W_CAP)")
    ap.add_argument("--patience", type=int, default=5, help="검증 macro recall 이 이만큼 epoch 동안 안 오르면 조기 종료")
    ap.add_argument("--limit", type=int, help="디버그용: 학습/검증 각각 이 장수만 (클래스별 고르게)")
    ap.add_argument("--amp", choices=["auto", "on", "off"], default="auto",
                    help="혼합정밀(fp16). auto 는 cuda 에서만 켠다. V100 에서 2배 가까이 빠르다")
    return ap.parse_args()


def main() -> int:
    args = parse_args()
    t_start = time.time()
    seed_everything(args.seed)
    device = pick_device(args.device)
    env = env_summary(device)
    print(f"torch {env['torch']} | device {env['device']}{' (' + env['gpu'] + ')' if env['gpu'] else ''} | "
          f"timm {env['timm']} | cpu {env['cpu_count']}")

    classes, names_ko = load_task_classes(args.task)
    class_to_idx = {c: i for i, c in enumerate(classes)}
    out_dir = args.out_dir.expanduser().resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    rows = read_manifest(args.manifest.expanduser().resolve(), classes)
    train_rows, val_rows, split_info = make_splits(
        rows, args.holdout_csv.expanduser().resolve() if args.holdout_csv else None,
        out_dir, args.task, args.seed)
    if args.limit:
        rng = random.Random(args.seed)
        train_rows = stratified_take(train_rows, args.limit, rng)
        val_rows = stratified_take(val_rows, args.limit, rng)
        split_info["limit"] = args.limit
    if not train_rows or not val_rows:
        raise SystemExit(f"학습 {len(train_rows)}장 / 검증 {len(val_rows)}장 — 어느 한쪽이 비어 있다")
    leak = {r["image_id"] for r in train_rows} & {r["image_id"] for r in val_rows}
    if leak:
        raise SystemExit(f"데이터 누수 — 같은 image_id 가 학습과 검증에 동시에 있다: {len(leak)}장")

    train_per_class = {c: sum(1 for r in train_rows if r["label"] == c) for c in classes}
    val_per_class = {c: sum(1 for r in val_rows if r["label"] == c) for c in classes}
    print(f"task {args.task} | 클래스 {len(classes)} {classes}")
    print(f"학습 {len(train_rows):,}장 {train_per_class}")
    print(f"검증 {len(val_rows):,}장 {val_per_class}  (출처: {split_info['val_source']})")

    train_tf, val_tf = build_transforms(args.image_size)
    g = torch.Generator()
    g.manual_seed(args.seed)
    loader_kw = dict(num_workers=args.num_workers, pin_memory=device.type == "cuda",
                     persistent_workers=args.num_workers > 0, worker_init_fn=_worker_init)
    train_loader = DataLoader(ManifestDataset(train_rows, class_to_idx, train_tf),
                              batch_size=args.batch_size, shuffle=True, drop_last=False, generator=g, **loader_kw)
    val_loader = DataLoader(ManifestDataset(val_rows, class_to_idx, val_tf),
                            batch_size=args.batch_size, shuffle=False, **loader_kw)

    weights, weights_by_class = class_weights(train_rows, classes, args.w_cap)
    print(f"클래스 가중치(상한 {args.w_cap}): {weights_by_class}")
    criterion = nn.CrossEntropyLoss(weight=weights.to(device), label_smoothing=args.label_smoothing)

    model, init_info = build_model(len(classes), args.init, args.init_weights)
    model.to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=args.weight_decay)
    # 코사인으로 서서히 낮춘다. 노트북의 직전 실험은 lr 고정이라 epoch 마다 결과가 크게 출렁였고 그 꼭대기가
    # '최고 모델'로 뽑히는 사고가 났다(셀 10 주석).
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=args.epochs)
    # 혼합정밀은 cuda 에서만. mps/cpu 의 fp16 autocast 는 torch 버전마다 지원이 들쭉날쭉해 켜지 않는다.
    use_amp = device.type == "cuda" and args.amp != "off"
    if args.amp == "on" and not use_amp:
        print(f"  --amp on 은 cuda 에서만 의미가 있다 (device {device}) — 끄고 진행")
    if hasattr(torch.amp, "GradScaler"):                       # torch >= 2.3
        scaler = torch.amp.GradScaler("cuda", enabled=use_amp)
    else:                                                      # 옛 torch (클라우드 이미지가 오래됐을 때)
        scaler = torch.cuda.amp.GradScaler(enabled=use_amp)
    if device.type == "cuda":
        torch.backends.cudnn.benchmark = True

    weights_path = out_dir / f"{args.task}.pth"
    report_path = out_dir / f"{args.task}-train-report.json"
    pred_path = out_dir / f"{args.task}-val-predictions.csv"
    report: dict = {
        "task": args.task,
        "classes": classes,
        "names_ko": names_ko,
        "args": {k: (str(v) if isinstance(v, Path) else v) for k, v in vars(args).items()},
        "env": env,
        "init": init_info,
        "amp": use_amp,
        "data": {
            "manifest": str(args.manifest.expanduser().resolve()),
            "train_count": len(train_rows), "val_count": len(val_rows),
            "train_per_class": train_per_class, "val_per_class": val_per_class,
            "class_weights": weights_by_class, **split_info,
        },
        "history": [],
        "best_epoch": None, "best_macro_recall": None, "epochs_run": 0, "stopped_early": False,
        "weights": {"path": str(weights_path)},
    }

    best_score, best_epoch, since_best = -1.0, 0, 0
    interrupted = False
    try:
        for epoch in range(1, args.epochs + 1):
            t0 = time.time()
            train_loss, _, _, _ = run_epoch(model, train_loader, criterion, optimizer, device, scaler, use_amp, True)
            val_loss, preds, labels, _ = run_epoch(model, val_loader, criterion, None, device, scaler, use_amp, False)
            lr_now = optimizer.param_groups[0]["lr"]
            scheduler.step()

            cm = confusion(labels, preds, len(classes))
            recall, _, support = per_class_metrics(cm)
            score = macro_recall(cm)
            mark = ""
            if score > best_score:
                best_score, best_epoch, since_best = score, epoch, 0
                torch.save({k: v.detach().cpu() for k, v in model.state_dict().items()}, weights_path)
                mark = "  <- 저장"
            else:
                since_best += 1

            report["history"].append({
                "epoch": epoch, "train_loss": round(train_loss, 5), "val_loss": round(val_loss, 5),
                "macro_recall": round(score, 5), "lr": lr_now, "seconds": round(time.time() - t0, 1),
                "per_class_recall": {c: round(float(r), 4) for c, r in zip(classes, recall)},
            })
            report.update(best_epoch=best_epoch, best_macro_recall=round(best_score, 5), epochs_run=epoch)
            write_json(report_path, report)

            rec_str = " ".join(f"{c[:4]} {r:.2f}" if s else f"{c[:4]} -" for c, r, s in zip(classes, recall, support))
            print(f"Epoch {epoch:02d}/{args.epochs} | train {train_loss:.4f} | val {val_loss:.4f} | "
                  f"macroR {score:.3f} | {rec_str} | lr {lr_now:.1e} | {time.time() - t0:.0f}s{mark}")

            if since_best >= args.patience:
                print(f"조기 종료: {args.patience} epoch 동안 검증 macro recall 이 오르지 않음 (best epoch {best_epoch})")
                report["stopped_early"] = True
                break
    except KeyboardInterrupt:
        interrupted = True
        print("\n중단됨 — 지금까지의 best 가중치와 리포트는 남아 있다")

    report["interrupted"] = interrupted
    if best_epoch == 0:
        write_json(report_path, report)
        print("저장된 가중치가 없다(한 epoch 도 끝나지 않음)")
        return 1

    # ── best 가중치로 검증 세트를 다시 돌려 최종 지표·예측을 남긴다 ──
    model.load_state_dict(torch.load(weights_path, map_location=device, weights_only=True))
    _, preds, labels, confs = run_epoch(model, val_loader, criterion, None, device, scaler, use_amp, False)
    cm = confusion(labels, preds, len(classes))
    recall, precision, support = per_class_metrics(cm)
    with pred_path.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["image_id", "label", "pred", "confidence"])
        for r, p, c in zip(val_rows, preds, confs):
            w.writerow([r["image_id"], r["label"], classes[int(p)], f"{float(c):.4f}"])

    digest = sha256_12(weights_path)
    report["weights"].update(sha256_12=digest, model_version=f"efficientnet_b0-{digest}",
                             bytes=weights_path.stat().st_size)
    report["best"] = {
        "epoch": best_epoch,
        "macro_recall": round(macro_recall(cm), 5),
        "accuracy": round(float(np.trace(cm) / max(cm.sum(), 1)), 5),
        "per_class": {c: {"recall": round(float(r), 4), "precision": round(float(p), 4), "support": int(s)}
                      for c, r, p, s in zip(classes, recall, precision, support)},
        "confusion": cm.tolist(),
        "confusion_note": "행=실제, 열=예측, 순서=classes",
    }
    report["val_predictions"] = str(pred_path)
    report["elapsed_seconds"] = round(time.time() - t_start, 1)
    write_json(report_path, report)

    print()
    print(f"완료. best epoch {best_epoch} | macro recall {report['best']['macro_recall']:.3f} | "
          f"accuracy {report['best']['accuracy']:.3f} | {report['elapsed_seconds']:.0f}s")
    print("  클래스별 recall/precision: " + ", ".join(
        f"{c} {v['recall']:.2f}/{v['precision']:.2f}" for c, v in report["best"]["per_class"].items()))
    print(f"  가중치: {weights_path}  (model_version efficientnet_b0-{digest})")
    print(f"  리포트: {report_path}")
    print(f"  예측:   {pred_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
