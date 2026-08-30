"""
홀드아웃 이미지로 모델 성능을 측정하는 평가 하네스.

왜 필요한가
----------
이 저장소에는 "이 모델이 얼마나 맞히는가"를 답할 수단이 없었다. 그래서
  - 모델을 갈아끼웠을 때 나아졌는지 나빠졌는지 판단할 근거가 없고,
  - LOW_CONFIDENCE_THRESHOLD 가 왜 그 값인지 아무도 설명할 수 없으며,
  - torch 버전을 올린 뒤 "확률이 미세하게 달라졌다"를 눈으로만 확인했다.
이 스크립트는 그 셋을 한 번에 해결한다.

전처리를 여기서 다시 쓰지 않고 main.py 를 임포트해 그대로 쓰는 것이 핵심이다.
Resize/Normalize 값을 복사해 두면 언젠가 한쪽만 바뀌고, 그때부터 이 평가는
**실제로 서빙되는 모델이 아닌 다른 것**을 측정하게 된다. 그런 평가는 없느니만 못하다.
(Grad-CAM 은 건너뛴다. 히트맵은 확률에 영향을 주지 않고, 평가에 필요한 건 확률뿐이다.)

사용법
------
    # 1) HAM10000 형식 — 이미지 폴더 + 메타데이터 CSV
    docker compose run --rm -v /path/to/holdout:/data:ro fastapi \
        python tests/evaluate.py --image-dir /data/images --csv /data/holdout.csv

    # 2) 클래스별 하위 폴더 구조 (/data/mel/*.jpg, /data/nv/*.jpg ...)
    docker compose run --rm -v /path/to/holdout:/data:ro fastapi \
        python tests/evaluate.py --image-dir /data

    # 3) 결과를 저장해 두고, 모델 교체 후 비교
    ... python tests/evaluate.py --image-dir /data --json /data/before.json
    (model.pth 교체 후)
    ... python tests/evaluate.py --image-dir /data --compare /data/before.json

    # 4) 병변이 아닌 사진 (tests/make_ood.py 로 만든다) — 전부 경고가 붙어야 정답
    ... python tests/evaluate.py --ood-dir /ood

    # 5) 둘을 함께 주면 임계값의 양쪽 비용을 한 표에 놓고 본다
    ... python tests/evaluate.py --image-dir /data --csv /data/holdout.csv --ood-dir /ood

JSON 키 이름에 대하여 — reject_rate 는 이제 "경고율"이다
--------------------------------------------------------
2026-08-30 에 서빙 임계값은 **차단선에서 경고선으로 바뀌었다**(fastapi/main.py 의
LOW_CONFIDENCE_THRESHOLD 주석 참고). 임계값 아래여도 결과는 그대로 화면에 나가고,
"확신 낮음" 표시만 붙는다. 따라서 아래 표의 reject_rate 는 **답을 못 받는 비율이
아니라 경고가 붙는 비율**로 읽어야 한다.

키 이름을 바꾸지 않은 것은 의도적이다 — tests/baselines/ 에 쌓인 기존 JSON 과
--compare 로 계속 비교할 수 있어야 하기 때문이다. 이름은 남기고 뜻만 옮겼다.

임계값을 양쪽에서 봐야 하는 이유
--------------------------------
--image-dir 로만 재면 이 하네스가 내놓는 '경고율'은 전부 **오경고율**이다. 전부 진짜
병변 사진이니, 경고는 하나같이 군더더기다. 그 숫자만 보면 임계값은 낮을수록 좋다는 결론밖에
안 나온다 — 실제로 0.45 를 0.35 로 내린 근거도 그 절반짜리 표였다.

임계값이 실제로 하는 일은 **병변 사진이 아닌 것을 걸러내는 것**이다. 그 효과는 병변
데이터로는 영영 측정되지 않는다. --ood-dir 이 나머지 절반이다. 거기서는 경고가 정답이라,
같은 임계값이 반대 방향의 점수를 받는다. 두 표를 나란히 놓아야 임계값을 고를 수 있다.

홀드아웃 데이터를 저장소에 커밋하지 않는 이유는 tests/README.md 참고.
"""

import argparse
import csv
import json
import os
import sys
import unicodedata
from collections import Counter
from pathlib import Path

# --- main.py 를 임포트하기 위한 준비 (임포트보다 먼저 와야 한다) ---------------
#
# main.py 는 model.pth 를 상대경로로 연다. 어느 디렉터리에서 실행하든 동작하도록
# fastapi/ 로 옮긴 뒤 임포트한다.
FASTAPI_DIR = Path(__file__).resolve().parent.parent
os.chdir(FASTAPI_DIR)
sys.path.insert(0, str(FASTAPI_DIR))

# main.py 는 INTERNAL_API_SECRET 이 없으면 기동을 거부한다. 그 가드는 "인증 없는 추론
# 엔드포인트가 네트워크에 열리는 것"을 막기 위한 것인데, 이 스크립트는 포트를 열지 않는다.
# 컨테이너 안에서 돌리면 이미 설정돼 있고, 로컬 venv 에서도 돌릴 수 있게 대체값을 둔다.
os.environ.setdefault("INTERNAL_API_SECRET", "evaluate.py-does-not-serve-anything")

import torch  # noqa: E402
from PIL import Image  # noqa: E402

import main as serving  # noqa: E402

CLASSES = serving.CLASSES

# 심각도 — 원본은 backend/src/main/resources/db/migration/V1__baseline_schema.sql 의
# disease 시드다. 여기에 복사본이 생기는 것이 마음에 들지는 않지만, 이 값이 있어야
# "악성을 양성으로 놓친 건수"라는 유일하게 임상적으로 의미 있는 지표를 낼 수 있다.
# 키가 CLASSES 와 어긋나면 tests/test_model_contract.py 가 잡는다.
SEVERITY = {
    "akiec": "MEDIUM",
    "bcc": "HIGH",
    "bkl": "LOW",
    "df": "LOW",
    "mel": "HIGH",
    "nv": "LOW",
    "vasc": "LOW",
    "inflammatory": "LOW",
}
DANGEROUS = {code for code, s in SEVERITY.items() if s in ("HIGH", "MEDIUM")}

IMAGE_SUFFIXES = (".jpg", ".jpeg", ".png", ".bmp", ".webp")


# =============================================
# 데이터 수집
# =============================================
def _resolve(image_dirs: list[Path], image_id: str) -> Path | None:
    """
    확장자가 붙어 있든 아니든 찾아낸다 (HAM10000 metadata 는 확장자가 없다).

    폴더를 여러 개 받는 이유: HAM10000 배포본은 이미지가
    HAM10000_images_part_1 / part_2 두 폴더로 쪼개져 있는데 metadata.csv 는 하나다.
    """
    for image_dir in image_dirs:
        direct = image_dir / image_id
        if direct.is_file():
            return direct
        for suffix in IMAGE_SUFFIXES:
            candidate = image_dir / f"{image_id}{suffix}"
            if candidate.is_file():
                return candidate
    return None


def load_from_csv(csv_path: Path, image_dirs: list[Path], id_col: str, label_col: str):
    """CSV 한 줄 = 이미지 하나. 컬럼명 기본값은 HAM10000 기준(image_id, dx)."""
    samples, missing, unknown = [], [], Counter()
    with open(csv_path, newline="", encoding="utf-8-sig") as f:
        reader = csv.DictReader(f)
        if reader.fieldnames is None or id_col not in reader.fieldnames:
            raise SystemExit(
                f"CSV 에 '{id_col}' 컬럼이 없습니다. 있는 컬럼: {reader.fieldnames}\n"
                f"--id-col / --label-col 로 지정하세요."
            )
        for row in reader:
            label = (row.get(label_col) or "").strip()
            if label not in CLASSES:
                unknown[label] += 1
                continue
            path = _resolve(image_dirs, str(row[id_col]).strip())
            if path is None:
                missing.append(row[id_col])
                continue
            samples.append((path, label))
    return samples, missing, unknown


def load_from_dirs(image_dirs: list[Path]):
    """하위 폴더 이름을 라벨로 본다 (torchvision ImageFolder 와 같은 관례)."""
    samples, unknown = [], Counter()
    for image_dir in image_dirs:
        for child in sorted(image_dir.iterdir()):
            if not child.is_dir():
                continue
            if child.name not in CLASSES:
                unknown[child.name] += 1
                continue
            for path in sorted(child.iterdir()):
                if path.suffix.lower() in IMAGE_SUFFIXES:
                    samples.append((path, child.name))
    return samples, [], unknown


def load_ood(ood_dirs: list[Path]):
    """
    병변이 아닌 사진을 읽는다. 하위 폴더 이름이 갈래(category)가 된다.

    라벨을 받지 않는다 — 여기서는 어느 병명을 골랐는가가 아니라 **경고가 붙었는가**만 본다.
    갈래를 나누는 이유는, 섞어 놓으면 해석이 안 되기 때문이다. 새까만 사진에 경고를 붙이는
    것과 아무것도 없는 팔뚝에 경고를 붙이는 것은 난이도가 전혀 다른 일인데, 한 숫자로 합치면
    쉬운 쪽이 어려운 쪽을 가려 버린다.
    """
    samples = []
    for ood_dir in ood_dirs:
        children = sorted(c for c in ood_dir.iterdir() if c.is_dir())
        groups = [(c.name, c) for c in children] or [(ood_dir.name, ood_dir)]
        for category, folder in groups:
            for path in sorted(folder.rglob("*")):
                if path.suffix.lower() in IMAGE_SUFFIXES:
                    samples.append((path, category))
    return samples


# =============================================
# 추론
# =============================================
def predict_all(paths, batch_size: int):
    """확률 행렬 (N, 8) 을 만든다. 서빙과 같은 transform·같은 model 객체를 쓴다."""
    chunks = []
    total = len(paths)
    for start in range(0, total, batch_size):
        batch = paths[start:start + batch_size]
        tensors = torch.stack([
            serving.transform(Image.open(p).convert("RGB")) for p in batch
        ]).to(serving.device)
        with torch.no_grad():
            probs = torch.softmax(serving.model(tensors), dim=1)
        chunks.append(probs.cpu())
        done = min(start + batch_size, total)
        print(f"\r  추론 {done}/{total}", end="", flush=True)
    print()
    return torch.cat(chunks)


# =============================================
# 지표
# =============================================
def confusion(labels, preds):
    """matrix[정답][예측] = 건수."""
    index = {c: i for i, c in enumerate(CLASSES)}
    n = len(CLASSES)
    matrix = [[0] * n for _ in range(n)]
    for truth, pred in zip(labels, preds):
        matrix[index[truth]][index[pred]] += 1
    return matrix


def per_class(matrix, labels=None, top1_conf=None, threshold=None):
    """
    경고 통계를 함께 낸다.

    전체 경고율만 보면 "6장 중 1장에 경고가 붙는다"까지밖에 모른다. 그게 어느 병에 몰려 있는지가
    임상적으로 훨씬 중요하다 — 흑색종에서만 경고가 잦다면, 그 임계값은 정확도를 지키는 게
    아니라 **가장 위험한 병에서만 입을 다무는** 장치라는 뜻이다.
    """
    rejected = Counter()
    if labels is not None:
        for label, conf in zip(labels, top1_conf):
            if conf < threshold:
                rejected[label] += 1

    stats = {}
    for i, code in enumerate(CLASSES):
        tp = matrix[i][i]
        support = sum(matrix[i])                       # 정답이 이 클래스인 건수
        predicted = sum(matrix[r][i] for r in range(len(CLASSES)))  # 이 클래스로 예측한 건수
        recall = tp / support if support else None
        precision = tp / predicted if predicted else None
        if recall and precision:
            f1 = 2 * precision * recall / (precision + recall)
        else:
            f1 = 0.0 if support else None
        stats[code] = {
            "support": support,
            "predicted": predicted,
            "correct": tp,
            "precision": precision,
            "recall": recall,
            "f1": f1,
            "rejected": rejected[code],
            "reject_rate": rejected[code] / support if support else None,
        }
    return stats


def threshold_sweep(labels, preds, top1_conf):
    """
    임계값을 바꿔가며 무엇을 잃고 무엇을 얻는지 본다.

    임계값 미만은 화면에 "확신 낮음" 표시와 함께 그대로 나간다(차단하지 않는다).
    그래서 reject_rate 는 "경고가 붙는 비율", accuracy_of_passed 는 "경고 없이 나간
    것들의 정확도"로 읽는다. 임계값을 올리면 경고가 늘고, 경고 없이 나가는 쪽은
    깨끗해지지만 그만큼 좁아진 표본에서 잰 점수라는 점을 잊으면 안 된다.
    """
    rows = []
    for step in range(1, 20):
        t = step * 0.05
        passed = [i for i, c in enumerate(top1_conf) if c >= t]
        rejected = len(labels) - len(passed)
        correct = sum(1 for i in passed if preds[i] == labels[i])
        mel_total = sum(1 for l in labels if l == "mel")
        mel_hit = sum(1 for i in passed if labels[i] == "mel" and preds[i] == "mel")
        danger_total = sum(1 for l in labels if l in DANGEROUS)
        danger_hit = sum(
            1 for i in passed
            if labels[i] in DANGEROUS and preds[i] in DANGEROUS
        )
        rows.append({
            "threshold": round(t, 2),
            "reject_rate": rejected / len(labels),
            "accuracy_of_passed": correct / len(passed) if passed else None,
            "mel_recall": mel_hit / mel_total if mel_total else None,
            "danger_recall": danger_hit / danger_total if danger_total else None,
        })
    return rows


def ood_per_category(categories, preds, top1_conf, threshold):
    """
    갈래별 채점. 여기서는 **경고가 정답**이라 경고율이 곧 정답률이다.

    통과한 것이 어떤 병명을 받았는지도 함께 센다. 벽 사진이 통과했다는 사실보다,
    그게 'mel 로 통과했다'는 사실이 더 나쁘기 때문이다 — 화면에는 흑색종 의심이 뜬다.
    """
    out = {}
    for category in sorted(set(categories)):
        idx = [i for i, c in enumerate(categories) if c == category]
        passed = [i for i in idx if top1_conf[i] >= threshold]
        passed_as = Counter(preds[i] for i in passed)
        out[category] = {
            "total": len(idx),
            "passed": len(passed),
            "rejected": len(idx) - len(passed),
            "reject_rate": (len(idx) - len(passed)) / len(idx),
            "passed_as": passed_as.most_common(),
            "passed_as_dangerous": sum(v for k, v in passed_as.items() if k in DANGEROUS),
        }
    return out


def ood_sweep(categories, top1_conf):
    """임계값별 경고율. 병변 쪽 threshold_sweep 과 같은 눈금을 써야 나란히 놓을 수 있다."""
    rows = []
    for step in range(1, 20):
        t = step * 0.05
        row = {"threshold": round(t, 2),
               "reject_rate": sum(1 for c in top1_conf if c < t) / len(top1_conf)}
        for cat in sorted(set(categories)):
            conf = [top1_conf[i] for i, c in enumerate(categories) if c == cat]
            row[cat] = sum(1 for c in conf if c < t) / len(conf)
        rows.append(row)
    return rows


# =============================================
# 출력
# =============================================
# 한글은 터미널에서 두 칸을 차지하는데 파이썬 문자열은 한 글자로 센다. f-string 의
# {:>10} 을 그대로 쓰면 한글이 섞인 표가 전부 어긋난다 — 폭을 직접 계산한다.
def _w(text: str) -> int:
    return sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1 for ch in text)


def lj(text: str, width: int) -> str:
    return text + " " * max(0, width - _w(text))


def rj(text: str, width: int) -> str:
    return " " * max(0, width - _w(text)) + text


def pct(x, width: int) -> str:
    """비율(0~1)을 백분율 문자열로. 정의되지 않으면 대시."""
    return rj("—" if x is None else f"{x * 100:.1f}%", width)


def print_report(result):
    print()
    print("=" * 74)
    print(f"  모델 {result['model_version']}   ·   홀드아웃 {result['sample_count']}건")
    print("=" * 74)
    print(f"\n전체 정확도 (임계값 무시)   {result['accuracy'] * 100:.2f}%")
    print(f"서빙 경고선                 {result['serving_threshold']}"
          f"  →  경고율 {result['serving']['reject_rate'] * 100:.2f}%")

    # ── 클래스별 ───────────────────────────────────────────────
    print("\n클래스별")
    print("  " + lj("코드", 14) + lj("심각도", 9) + rj("건수", 6)
          + rj("정밀도", 10) + rj("재현율", 10) + rj("F1", 8) + rj("경고율", 10))
    print("  " + "-" * 67)
    for code in CLASSES:
        st = result["per_class"][code]
        f1 = rj("—", 8) if st["f1"] is None else f"{st['f1']:>8.3f}"
        print("  " + lj(code, 14) + lj(SEVERITY[code], 9) + f"{st['support']:>6}"
              + pct(st["precision"], 10) + pct(st["recall"], 10) + f1
              + pct(st["reject_rate"], 10))

    # ── 혼동행렬 ───────────────────────────────────────────────
    print("\n혼동행렬  (행 = 실제 병명, 열 = 모델의 답)")
    print("  " + lj("", 14) + "".join(f"{c[:6]:>7}" for c in CLASSES))
    for i, code in enumerate(CLASSES):
        cells = "".join(
            f"{v:>6}*" if i == j and v else f"{v:>7}"
            for j, v in enumerate(result["confusion"][i])
        )
        print("  " + lj(code, 14) + cells)
    print("  (* = 맞힌 칸. 대각선 밖의 큰 수가 그 모델의 약점이다)")
    print("  ※ 위 표는 임계값을 무시한 값이다. 경고율 열만 서빙 경고선 기준이다.")

    # ── 위험 병변 ──────────────────────────────────────────────
    d = result["danger"]
    print("\n놓치면 안 되는 것")
    print(f"  악성·전암({'·'.join(sorted(DANGEROUS))})을 양성으로 판정: "
          f"{d['downgraded']}건 / {d['total']}건 ({d['downgrade_rate'] * 100:.1f}%)")
    if d["mel_confused_with"]:
        print("  mel 을 무엇으로 오인했나: "
              + ", ".join(f"{k} {v}건" for k, v in d["mel_confused_with"]))

    # ── 임계값 ────────────────────────────────────────────────
    print("\n임계값을 바꾸면")
    print("  " + rj("임계값", 8) + rj("경고율", 10) + rj("무경고 정확도", 16)
          + rj("mel 재현율", 14) + rj("위험군 재현율", 16))
    print("  " + "-" * 64)
    for row in result["threshold_sweep"]:
        mark = "  ←현재" if abs(row["threshold"] - result["serving_threshold"]) < 1e-9 else ""
        print("  " + f"{row['threshold']:>8.2f}" + pct(row["reject_rate"], 10)
              + pct(row["accuracy_of_passed"], 16) + pct(row["mel_recall"], 14)
              + pct(row["danger_recall"], 16) + mark)
    print("  경고율        = 화면에 '확신 낮음' 표시가 붙는 비율 (결과는 그대로 나간다)")
    print("  무경고 정확도 = 경고가 붙지 않은 것들 중 맞힌 비율")
    print("  mel 재현율    = 실제 흑색종 중 '경고 없이 mel 로 맞힌' 비율")
    print("  위험군 재현율 = 실제 악성·전암 중 '경고 없이 악성·전암 어딘가로 분류한' 비율")
    print("               (병명이 틀려도 양성으로 넘기지는 않았다는 뜻)")
    print()


def print_ood_report(ood):
    per = ood["per_category"]
    print()
    print("=" * 74)
    print(f"  OOD 경고 검사   ·   {ood['sample_count']}장   ·   경고선 {ood['serving_threshold']}")
    print("=" * 74)
    print("\n여기 있는 사진에는 병변이 없다 — 전부 경고가 붙는 것이 정답이다.")
    print(f"전체 경고율 (=정답률)       {ood['reject_rate'] * 100:.2f}%")

    print("\n갈래별")
    print("  " + lj("갈래", 14) + rj("장수", 6) + rj("경고(=정답)", 14)
          + rj("통과", 7) + rj("위험군", 8) + "   통과분이 받은 병명")
    print("  " + "-" * 72)
    for category in sorted(per):
        s = per[category]
        got = ", ".join(f"{k} {v}" for k, v in s["passed_as"][:4]) or "—"
        print("  " + lj(category, 14) + f"{s['total']:>6}" + pct(s["reject_rate"], 14)
              + f"{s['passed']:>7}" + f"{s['passed_as_dangerous']:>8}" + "   " + got)
    print("  위험군 = 통과분 중 악성·전암(akiec·bcc·mel)으로 분류된 장수.")
    print("           화면에 '흑색종 의심'이 뜬다는 뜻이라 단순 통과보다 나쁘다.")

    if ood["worst"]:
        w = ood["worst"]
        print("\n가장 자신 있게 틀린 것")
        for row in w:
            print(f"  {row['conf'] * 100:5.1f}%  {row['pred']:<6} {row['file']}")

    print("\n임계값을 바꾸면 (경고율 = 정답률)")
    cats = sorted(per)
    print("  " + rj("임계값", 8) + rj("전체", 9) + "".join(rj(c[:11], 13) for c in cats))
    print("  " + "-" * (17 + 13 * len(cats)))
    for row in ood["threshold_sweep"]:
        mark = "  ←현재" if abs(row["threshold"] - ood["serving_threshold"]) < 1e-9 else ""
        print("  " + f"{row['threshold']:>8.2f}" + pct(row["reject_rate"], 9)
              + "".join(pct(row[c], 13) for c in cats) + mark)
    print()


def print_tradeoff(lesion, ood):
    """
    같은 임계값을 양쪽에서 본다. 이 표 하나가 이 하네스에 OOD 를 붙인 이유다.

    왼쪽은 비용 — 진짜 병변인데 불필요하게 '확신 낮음'이 붙는 비율.
    오른쪽은 위험 — 병변이 아닌 사진이 아무 표시 없이 병명을 받는 비율.
    임계값을 올리면 왼쪽이 나빠지고 오른쪽이 좋아진다. 어느 쪽도 공짜가 아니다.
    차단하던 시절과 달리 왼쪽의 비용은 "답을 못 받는 것"이 아니라 "표시가 붙는 것"이라,
    같은 표를 읽어도 임계값을 더 높게 잡을 여지가 생겼다. 그 근거로 0.45 를 골랐다.
    """
    by_t = {row["threshold"]: row for row in ood["threshold_sweep"]}
    skin = "normal_skin" if "normal_skin" in ood["per_category"] else None

    print("=" * 74)
    print("  임계값의 양쪽 비용")
    print("=" * 74)
    print("  " + rj("임계값", 8) + rj("병변 오경고", 13) + rj("mel 재현율", 13)
          + rj("OOD 무경고", 11) + (rj("정상피부 무경고", 16) if skin else ""))
    print("  " + "-" * (45 + (16 if skin else 0)))
    for row in lesion["threshold_sweep"]:
        o = by_t.get(row["threshold"])
        if o is None:
            continue
        mark = "  ←현재" if abs(row["threshold"] - lesion["serving_threshold"]) < 1e-9 else ""
        line = ("  " + f"{row['threshold']:>8.2f}" + pct(row["reject_rate"], 13)
                + pct(row["mel_recall"], 13) + pct(1 - o["reject_rate"], 11))
        if skin:
            line += pct(1 - o[skin], 16)
        print(line + mark)
    print("  병변 오경고   = 진짜 병변인데 불필요하게 경고가 붙은 비율 (낮을수록 좋다)")
    print("  OOD 통과      = 병변이 아닌 사진에 병명을 붙여 내보낸 비율 (낮을수록 좋다)")
    print("  정상피부 통과 = 그중 아무것도 없는 피부를 병변이라고 답한 비율")
    print("                  키오스크에서 실제로 가장 자주 일어나는 오촬영이라 따로 뺐다")
    print()


def _scope(result) -> str:
    """이 결과가 무엇을 재고 있는지 한 줄로. 병변만/OOD만/둘 다 가능하다."""
    parts = []
    if "accuracy" in result:
        parts.append(f"병변 {result['sample_count']}건")
    if "ood" in result:
        parts.append(f"OOD {result['ood']['sample_count']}장")
    return ", ".join(parts) or "—"


def print_comparison(current, baseline):
    print("=" * 74)
    print("  이전과 비교")
    print(f"    이전  {baseline['model_version']}  ({_scope(baseline)})")
    print(f"    현재  {current['model_version']}  ({_scope(current)})")
    print("=" * 74)
    if baseline.get("sample_count") != current.get("sample_count"):
        print("  ⚠ 홀드아웃 건수가 다릅니다. 같은 데이터가 아니면 비교는 의미가 없습니다.")
    if baseline.get("classes") != current.get("classes"):
        print("  ⚠ 클래스 목록이 다릅니다. 비교표의 클래스별 값을 믿지 마세요.")

    def delta(now, before, label):
        if now is None or before is None:
            print("  " + lj(label, 34) + rj("비교 불가", 22))
            return
        diff = (now - before) * 100
        sign = "+" if diff >= 0 else ""
        print("  " + lj(label, 34)
              + f"{before * 100:7.2f}  →{now * 100:7.2f}   {sign}{diff:.2f}%p")

    print()
    if "accuracy" in current and "accuracy" in baseline:
        delta(current["accuracy"], baseline["accuracy"], "전체 정확도")
        delta(current["danger"]["downgrade_rate"], baseline["danger"]["downgrade_rate"],
              "악성→양성 오판율 (낮을수록 좋음)")
        print("\n  클래스별 재현율")
        for code in CLASSES:
            delta(current["per_class"].get(code, {}).get("recall"),
                  baseline["per_class"].get(code, {}).get("recall"),
                  f"  {code}")

    if "ood" in current and "ood" in baseline:
        now_cat, was_cat = current["ood"]["per_category"], baseline["ood"]["per_category"]
        if current["ood"]["sample_count"] != baseline["ood"]["sample_count"]:
            print("  ⚠ OOD 장수가 다릅니다. '전체' 줄은 구성이 달라서 생긴 차이일 수 있으니 "
                  "갈래별 줄을 보세요.")
        print("\n  OOD 경고율 (여기서는 높을수록 좋음)")
        delta(current["ood"]["reject_rate"], baseline["ood"]["reject_rate"], "  전체")
        # 한쪽에만 있는 갈래도 줄을 남긴다 — 조용히 사라지면 뭘 못 쟀는지 알 수 없다.
        for category in sorted(set(now_cat) | set(was_cat)):
            delta(now_cat.get(category, {}).get("reject_rate"),
                  was_cat.get(category, {}).get("reject_rate"),
                  f"  {category}")
    print()


# =============================================
def main() -> int:
    parser = argparse.ArgumentParser(
        description="홀드아웃 이미지로 서빙 모델의 성능을 측정한다.")
    parser.add_argument("--image-dir", type=Path, nargs="+",
                        help="병변 이미지 폴더. 여러 개 줄 수 있다(HAM10000 은 part_1/part_2 로 쪼개져 있다). "
                             "--csv 가 없으면 하위 폴더명을 라벨로 본다.")
    parser.add_argument("--ood-dir", type=Path, nargs="+",
                        help="병변이 아닌 사진 폴더(하위 폴더명 = 갈래). 여기 있는 것은 전부 "
                             "경고가 붙는 것이 정답이다. tests/make_ood.py 로 만든다.")
    parser.add_argument("--csv", type=Path,
                        help="이미지 ID·라벨 목록 (HAM10000 metadata 형식)")
    parser.add_argument("--id-col", default="image_id")
    parser.add_argument("--label-col", default="dx")
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--json", type=Path, help="결과를 이 경로에 JSON 으로 저장")
    parser.add_argument("--compare", type=Path, help="이전 --json 결과와 비교")
    parser.add_argument("--min-mel-recall", type=float,
                        help="mel 재현율이 이 값 미만이면 종료코드 1 (CI 게이트용)")
    parser.add_argument("--max-ood-pass-rate", type=float,
                        help="OOD 통과율이 이 값을 넘으면 종료코드 1 (CI 게이트용)")
    args = parser.parse_args()

    if not args.image_dir and not args.ood_dir:
        parser.error("--image-dir 또는 --ood-dir 중 적어도 하나는 필요합니다.")
    for d in (args.image_dir or []) + (args.ood_dir or []):
        if not d.is_dir():
            raise SystemExit(f"폴더가 없습니다: {d}")

    threshold = serving.LOW_CONFIDENCE_THRESHOLD
    result = {
        "model_version": serving.MODEL_VERSION,
        "serving_threshold": threshold,
    }

    # ── 병변 쪽 ────────────────────────────────────────────────
    if args.image_dir:
        if args.csv:
            samples, missing, unknown = load_from_csv(
                args.csv, args.image_dir, args.id_col, args.label_col)
        else:
            samples, missing, unknown = load_from_dirs(args.image_dir)

        if unknown:
            listed = ", ".join(f"{k or '(빈값)'} {v}건" for k, v in unknown.most_common())
            print(f"⚠ CLASSES 에 없는 라벨은 건너뜁니다: {listed}")
        if missing:
            print(f"⚠ 파일을 못 찾은 항목 {len(missing)}건 "
                  f"(예: {', '.join(map(str, missing[:3]))})")
        if not samples:
            raise SystemExit("평가할 이미지가 하나도 없습니다. --image-dir / --csv 를 확인하세요.")

        print(f"홀드아웃 {len(samples)}건, 모델 {serving.MODEL_VERSION}, device={serving.device}")
        labels = [l for _, l in samples]
        probs = predict_all([p for p, _ in samples], args.batch_size)
        preds = [CLASSES[i] for i in probs.argmax(dim=1).tolist()]
        top1_conf = probs.max(dim=1).values.tolist()

        matrix = confusion(labels, preds)
        stats = per_class(matrix, labels, top1_conf, threshold)
        correct = sum(1 for p, l in zip(preds, labels) if p == l)

        danger_total = sum(1 for l in labels if l in DANGEROUS)
        downgraded = sum(1 for p, l in zip(preds, labels)
                         if l in DANGEROUS and p not in DANGEROUS)
        mel_confused = Counter(
            p for p, l in zip(preds, labels) if l == "mel" and p != "mel")
        rejected_now = sum(1 for c in top1_conf if c < threshold)

        result.update({
            "sample_count": len(samples),
            "accuracy": correct / len(samples),
            "per_class": stats,
            "confusion": matrix,
            "classes": CLASSES,
            "serving": {
                "reject_rate": rejected_now / len(samples),
                "rejected": rejected_now,
            },
            "danger": {
                "total": danger_total,
                "downgraded": downgraded,
                "downgrade_rate": downgraded / danger_total if danger_total else 0.0,
                "mel_confused_with": mel_confused.most_common(),
            },
            "threshold_sweep": threshold_sweep(labels, preds, top1_conf),
        })
        print_report(result)

    # ── OOD 쪽 (경고가 정답) ──────────────────────────────────
    if args.ood_dir:
        ood_samples = load_ood(args.ood_dir)
        if not ood_samples:
            raise SystemExit(f"OOD 이미지가 하나도 없습니다: {args.ood_dir}")

        print(f"OOD {len(ood_samples)}장, 모델 {serving.MODEL_VERSION}, device={serving.device}")
        categories = [c for _, c in ood_samples]
        ood_probs = predict_all([p for p, _ in ood_samples], args.batch_size)
        ood_preds = [CLASSES[i] for i in ood_probs.argmax(dim=1).tolist()]
        ood_conf = ood_probs.max(dim=1).values.tolist()

        passed_idx = [i for i, c in enumerate(ood_conf) if c >= threshold]
        # 가장 확신에 차서 틀린 것들. 총계보다 이쪽이 문제를 훨씬 빨리 보여준다 —
        # 어떤 사진에서 무너지는지 파일 이름째로 나오니 바로 열어 볼 수 있다.
        worst = sorted(passed_idx, key=lambda i: -ood_conf[i])[:5]

        result["ood"] = {
            "sample_count": len(ood_samples),
            "serving_threshold": threshold,
            "rejected": len(ood_samples) - len(passed_idx),
            "reject_rate": (len(ood_samples) - len(passed_idx)) / len(ood_samples),
            "per_category": ood_per_category(categories, ood_preds, ood_conf, threshold),
            "threshold_sweep": ood_sweep(categories, ood_conf),
            "worst": [{"file": f"{categories[i]}/{ood_samples[i][0].name}",
                       "pred": ood_preds[i], "conf": ood_conf[i]} for i in worst],
        }
        print_ood_report(result["ood"])

    if args.image_dir and args.ood_dir:
        print_tradeoff(result, result["ood"])

    if args.json:
        args.json.write_text(json.dumps(result, ensure_ascii=False, indent=2),
                             encoding="utf-8")
        print(f"결과 저장: {args.json}")

    if args.compare:
        print_comparison(result, json.loads(args.compare.read_text(encoding="utf-8")))

    # ── CI 게이트 ─────────────────────────────────────────────
    failed = False
    if args.min_mel_recall is not None:
        if "per_class" not in result:
            raise SystemExit("--min-mel-recall 은 --image-dir 이 있어야 판정할 수 있습니다.")
        actual = result["per_class"]["mel"]["recall"]
        if actual is None:
            print("⚠ 홀드아웃에 mel 이 없어 --min-mel-recall 을 판정할 수 없습니다.")
            failed = True
        elif actual < args.min_mel_recall:
            print(f"❌ mel 재현율 {actual * 100:.2f}% < 기준 {args.min_mel_recall * 100:.2f}%")
            failed = True
        else:
            print(f"✅ mel 재현율 {actual * 100:.2f}% ≥ 기준 {args.min_mel_recall * 100:.2f}%")

    if args.max_ood_pass_rate is not None:
        if "ood" not in result:
            raise SystemExit("--max-ood-pass-rate 는 --ood-dir 이 있어야 판정할 수 있습니다.")
        actual = 1 - result["ood"]["reject_rate"]
        if actual > args.max_ood_pass_rate:
            print(f"❌ OOD 통과율 {actual * 100:.2f}% > 기준 {args.max_ood_pass_rate * 100:.2f}%")
            failed = True
        else:
            print(f"✅ OOD 통과율 {actual * 100:.2f}% ≤ 기준 {args.max_ood_pass_rate * 100:.2f}%")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
