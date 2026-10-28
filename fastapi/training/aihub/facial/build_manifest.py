#!/usr/bin/env python3
"""
안면부 피부질환(AI허브 71863) 다운로드 폴더 → manifest.csv

왜 manifest 를 먼저 만드나
------------------------
AI허브 데이터는 zip 48개(TS_/TL_/VS_/VL_ × 질환 6 × 뷰 2)로 온다. 학습 코드가 폴더 구조와 JSON 스키마를
직접 알게 하면 데이터가 조금만 달라도 train.py 를 고쳐야 한다. 그래서 "어떤 파일이 어떤 클래스·뷰·분할인가"를
한 장의 CSV 로 먼저 고정하고, train.py / make_holdout.py / evaluate.py 는 그 CSV 만 본다.

실제 구조 (2026-10-23 inspect_dataset.py 보고서로 확인 — docs/aihub-datasets-plan.md §7-1)
------------------------------------------------------------------------------------
- 1.데이터/Training/01.원천데이터/TS_<질환>_<뷰>.zip (png 800) · 02.라벨링데이터/TL_…zip (json 800)
  1.데이터/Validation/01.원천데이터/VS_…zip (png 100) · 02.라벨링데이터/VL_…zip (json 100). 총 10,800장.
- zip 안 항목은 '/H0_205860_P1_L2.png' 처럼 **루트에 평평하게, 앞에 '/' 가 붙어** 있다. 하위 폴더 없음.
- 라벨 JSON 은 파일당 annotations 1개. 클래스는 annotations[0].diagnosis_info.diagnosis_name
  (여드름|건선|아토피|정상|주사|지루 — 폴더 토큰과 같은 값). 중증도 필드는 없다.
- JSON 의 photograph.file_path 는 '여드름/정면/H0_….png' 로 적혀 있어 실제 zip 경로와 다르다 →
  이미지와 JSON 은 **파일명 stem(identifier)** 으로 짝짓는다.
- bbox 는 사실상 이미지 전체(정면 1024², 측면 500² 오프셋 0~12) → 크롭하지 않는다.

클래스는 zip/폴더 이름의 토큰(`_여드름_`, `_정상_` …)으로 정하고, JSON 의 diagnosis_name 으로 교차검증한다.
둘이 다른 파일이 하나라도 있으면 종료코드 1 로 멈춘다 — 라벨을 믿을 수 없는 상태로 학습을 시작하지 않기 위해서다.

출력 열
------
  image_id            파일명 stem(= JSON identifier). 전체에서 겹치면 그 파일만 `<label>_<view>_<stem>` 으로 바꾼다
  path                풀린 이미지 파일의 절대 경로 (학습은 풀린 파일에서 해야 빠르다)
  label               클래스 코드 (classes.py CLASSES 중 하나)
  view                front(정면 1024²) / side(측면 512²)
  split               train(TS/TL) / val(VS/VL) — AI허브가 준 분할 그대로
  src                 그 파일이 들어 있던 zip/폴더 이름 (예: VS_여드름_정면)
  label_json          같은 stem 의 라벨 JSON 경로. 없으면 빈칸
  identifier          JSON 의 annotations[0].identifier (파일명 stem 과 같아야 한다)
  gender, age_range   generated_parameters (여/남/N/A, '19-29' 등)
  distribution, onset, symptom, bodypart   diagnosis_info (부분 침범/넓은 침범, 수 년 전, none/itching, 얼굴/머리/N/A)
  lesion_count        bbox.lesions 개수 (여드름에만 있음, 나머지 0)
  inflammatory_lesions  그중 inflammatory=true 개수
  prefix, p_code, l_code  identifier '<접두2자>_<숫자>_P<n>_L<n>' 의 조각. 의미는 미문서라 보존만 한다

사용법
------
    # zip 그대로 둔 다운로드 폴더 → /data/facial 에 풀면서 manifest 생성 (경로에 공백이 있으니 따옴표)
    python3 build_manifest.py "~/Downloads/14.안면부 피부질환 이미지 합성데이터/3.개방데이터/1.데이터" \
        --extract-to /data/facial --out /data/facial/manifest.csv

    # 이미 풀린 폴더 (zip 과 섞여 있어도 된다)
    python3 build_manifest.py /data/facial --out /data/facial/manifest.csv

zip 안의 한글 파일명은 CP949 로 들어 있는 경우가 많아 inspect_dataset.py 의 복원 함수를 그대로 쓴다.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import shutil
import sys
import time
import unicodedata
import zipfile
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))            # classes.py (이 태스크의 클래스·토큰 표)
sys.path.insert(0, str(HERE.parent))     # inspect_dataset.py (zip 한글 파일명 복원)

from classes import CLASSES, DIAGNOSIS_NAME_TO_CODE, FOLDER_TOKEN_TO_CODE, PREFIX_TO_SPLIT, VIEW_TOKEN_TO_CODE  # noqa: E402
from inspect_dataset import _fix_zip_name  # noqa: E402

IMAGE_EXT = {".png", ".jpg", ".jpeg", ".bmp", ".webp"}
MANIFEST_COLUMNS = ["image_id", "path", "label", "view", "split", "src", "label_json",
                    "identifier", "gender", "age_range", "distribution", "onset", "symptom", "bodypart",
                    "lesion_count", "inflammatory_lesions", "prefix", "p_code", "l_code"]

# identifier 'H0_205860_P1_L2' → (H0, 205860, 1, 2). 안 맞으면 전부 빈칸(죽지 않는다).
_IDENTIFIER_RE = re.compile(r"^([A-Za-z0-9]{1,3})_(\d+)_P(\d+)_L(\d+)$")

# 경고는 모아서 끝에 한 번 더 찍는다 — 수천 줄 진행 로그 사이에 묻히면 아무도 못 본다.
WARNINGS: list[str] = []


def warn(msg: str) -> None:
    WARNINGS.append(msg)
    print(f"  ⚠️  {msg}")


# ─────────────────────────────────────────────────────────────────────────────
# 이름 → (분할, 원천/라벨, 클래스, 뷰)
# ─────────────────────────────────────────────────────────────────────────────
@dataclass(frozen=True)
class Group:
    split: str      # train / val
    kind: str       # source(이미지) / label(JSON·마스크)
    label: str      # 클래스 코드
    view: str       # front / side / "" (뷰 토큰이 없을 때)
    src: str        # 매칭된 zip/폴더 이름 (확장자 제외)


_PREFIX_RE = re.compile(r"^(TS|TL|VS|VL)(?=[_\-\s.]|$)", re.IGNORECASE)


def parse_group(name: str) -> Group | None:
    """
    'VL_여드름_정면.zip' / 'TS_정상_측면' 같은 이름을 Group 으로. 규칙에 안 맞으면 None.

    토큰은 부분 문자열로 찾는다 — AI허브가 'TS_01.여드름_정면' 처럼 번호를 끼워 넣어도 잡히게.
    """
    # macOS 는 한글 파일명을 자모 분리형(NFD)으로 넘기는 경우가 많다. 맥에서 Google Drive 로 올린 zip 이
    # 그 이름 그대로 저장되면 '여드름'(NFC) 토큰이 글자 단위로 일치하지 않아 이미지를 하나도 못 찾는다
    # (2026-10-28 Colab 에서 실제로 겪었다). 비교 전에 NFC 로 맞춘다. src 열도 NFC 로 남아 make_holdout 과 일치한다.
    name = unicodedata.normalize("NFC", name)
    stem = name[:-4] if name.lower().endswith(".zip") else name
    m = _PREFIX_RE.match(stem)
    if not m:
        return None
    prefix = m.group(1).upper()
    label = next((code for tok, code in FOLDER_TOKEN_TO_CODE.items() if tok in stem), None)
    if label is None:
        return None
    view = next((code for tok, code in VIEW_TOKEN_TO_CODE.items() if tok in stem), "")
    return Group(split=PREFIX_TO_SPLIT[prefix],
                 kind="source" if prefix[1] == "S" else "label",
                 label=label, view=view, src=stem)


def group_for(parts: tuple[str, ...]) -> Group | None:
    """경로 구성요소를 깊은 쪽부터 보며 처음 규칙에 맞는 것을 쓴다(파일명 → 상위 폴더 순)."""
    for comp in reversed(parts):
        g = parse_group(comp)
        if g is not None:
            return g
    return None


# ─────────────────────────────────────────────────────────────────────────────
# zip 풀기
# ─────────────────────────────────────────────────────────────────────────────
def _safe_member_path(dest: Path, name: str) -> Path | None:
    """
    zip 항목 이름을 dest 아래로만 떨어지게 한다.

    이 데이터셋의 항목 이름은 '/H0_205860_P1_L2.png' 처럼 **앞에 '/' 가 붙어 있다**(§7-1). 그대로 Path 로 만들면
    절대경로가 되어 "이상한 경로" 로 전부 버려지고, 10GB 를 풀어도 파일이 0개 나온다. 선행 구분자는 떼고,
    '..' 로 위로 올라가는 항목만 거른다.
    """
    cleaned = name.replace("\\", "/").lstrip("/")
    if not cleaned:
        return None
    rel = Path(cleaned)
    if any(p == ".." for p in rel.parts):
        return None
    return dest / rel


def extract_zip(zip_path: Path, extract_root: Path, force: bool) -> Path | None:
    """
    zip 하나를 extract_root/<zip stem>/ 에 푼다. 이미 풀려 있으면(폴더가 비어 있지 않으면) 건너뛴다 —
    10GB 를 두 번 풀지 않기 위해서다. 강제로 다시 풀려면 --re-extract.

    ZipFile.extractall 을 쓰지 않는 이유: 그 함수는 깨진(cp437) 이름 그대로 파일을 만든다.
    항목마다 이름을 복원해 직접 쓴다.
    """
    dest = extract_root / zip_path.stem
    if dest.exists() and any(dest.iterdir()) and not force:
        print(f"  건너뜀(이미 풀림): {zip_path.name} → {dest}")
        return dest
    try:
        zf = zipfile.ZipFile(zip_path)
    except (zipfile.BadZipFile, OSError) as exc:
        warn(f"zip 열기 실패(손상/미완료 다운로드?): {zip_path} — {exc}")
        return None
    dest.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    n = 0
    with zf:
        for info in zf.infolist():
            if info.is_dir():
                continue
            target = _safe_member_path(dest, _fix_zip_name(info))
            if target is None:
                warn(f"zip 항목 경로가 이상해 건너뜀: {zip_path.name}:{info.filename}")
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with zf.open(info) as src, open(target, "wb") as out:
                shutil.copyfileobj(src, out, 1024 * 1024)
            n += 1
    print(f"  풀기: {zip_path.name} → {dest}  ({n:,} 파일, {time.time() - t0:.0f}s)")
    return dest


# ─────────────────────────────────────────────────────────────────────────────
# 파일 수집
# ─────────────────────────────────────────────────────────────────────────────
def walk_files(root: Path):
    """(경로 구성요소 튜플, 절대 경로) — 구성요소에는 root 자신의 이름도 넣는다(사용자가 TS_… 폴더를 바로 줄 수 있다)."""
    if root.is_file():
        yield (root.parent.name, root.name), root
        return
    for dirpath, dirnames, filenames in os.walk(root, onerror=lambda e: warn(f"읽기 실패: {e.filename} — {e.strerror}")):
        dirnames.sort()
        rel = Path(dirpath).relative_to(root)
        base = (root.name,) + tuple(p for p in rel.parts if p != ".")
        for fn in sorted(filenames):
            yield base + (fn,), Path(dirpath) / fn


def collect(inputs: list[Path], extract_to: Path | None, re_extract: bool):
    """
    입력 폴더/zip 을 전부 훑어 (이미지, 라벨 JSON, 분류 못 한 파일) 로 나눈다.
    zip 을 만나면 풀고, 풀린 폴더를 다시 훑는다(안에 zip 이 또 있어도 된다).
    """
    images: list[tuple[Group, Path]] = []
    label_jsons: dict[tuple[str, str, str], Path] = {}     # (label, view, stem) → json
    jsons_by_stem: dict[str, list[Path]] = defaultdict(list)
    unmatched: Counter = Counter()                          # 규칙에 안 맞는 파일: 상위 폴더별 수
    mask_count = 0
    zips_pending: list[Path] = []
    queue = list(inputs)
    seen_roots: set[Path] = set()

    while queue:
        root = queue.pop(0).resolve()
        if root in seen_roots:
            continue
        seen_roots.add(root)
        if not root.exists():
            warn(f"경로 없음: {root}")
            continue
        for parts, path in walk_files(root):
            ext = path.suffix.lower()
            if ext == ".zip":
                if extract_to is None:
                    zips_pending.append(path)
                    continue
                dest = extract_zip(path, extract_to, re_extract)
                if dest is not None:
                    queue.append(dest)
                continue
            g = group_for(parts)
            if g is None:
                if ext in IMAGE_EXT or ext == ".json":
                    unmatched[str(path.parent)] += 1
                continue
            if g.kind == "source":
                if ext in IMAGE_EXT:
                    images.append((g, path))
            else:
                if ext == ".json":
                    label_jsons.setdefault((g.label, g.view, path.stem), path)
                    jsons_by_stem[path.stem].append(path)
                elif ext in IMAGE_EXT:
                    mask_count += 1     # 세그멘테이션 마스크 — 분류 학습에는 쓰지 않는다
    return images, label_jsons, jsons_by_stem, unmatched, mask_count, zips_pending


# ─────────────────────────────────────────────────────────────────────────────
# 라벨 JSON 읽기 — 확정 스키마(§7-1). 키가 없으면 빈칸으로 두고 죽지 않는다.
# ─────────────────────────────────────────────────────────────────────────────
def load_json(path: Path):
    raw = path.read_bytes()
    for enc in ("utf-8-sig", "cp949"):
        try:
            return json.loads(raw.decode(enc))
        except (UnicodeDecodeError, json.JSONDecodeError):
            continue
    raise ValueError("utf-8/cp949 어느 쪽으로도 읽히지 않음")


def _first_annotation(obj) -> dict:
    """annotations 리스트의 첫 항목. 구조가 다르면 빈 dict — 호출부가 빈칸으로 채운다."""
    if isinstance(obj, dict):
        ann = obj.get("annotations")
        if isinstance(ann, list) and ann and isinstance(ann[0], dict):
            return ann[0]
        return obj if "diagnosis_info" in obj else {}
    return {}


def parse_label_json(path: Path) -> dict:
    """
    한 파일에서 manifest 메타 열을 뽑는다. 돌려주는 dict 의 키는 MANIFEST_COLUMNS 의 부분집합 + 'json_label'.
    json_label 은 diagnosis_name 을 코드로 바꾼 값(모르는 값이면 원문 그대로) — 폴더 토큰과 대조하는 데 쓴다.
    """
    ann = _first_annotation(load_json(path))
    di = ann.get("diagnosis_info") if isinstance(ann.get("diagnosis_info"), dict) else {}
    gp = ann.get("generated_parameters") if isinstance(ann.get("generated_parameters"), dict) else {}
    bbox = ann.get("bbox") if isinstance(ann.get("bbox"), dict) else {}
    lesions = bbox.get("lesions") if isinstance(bbox.get("lesions"), list) else []

    name = di.get("diagnosis_name")
    json_label = DIAGNOSIS_NAME_TO_CODE.get(name.strip(), name.strip()) if isinstance(name, str) else ""

    identifier = ann.get("identifier") if isinstance(ann.get("identifier"), str) else ""
    m = _IDENTIFIER_RE.match(identifier)
    prefix, p_code, l_code = (m.group(1), m.group(3), m.group(4)) if m else ("", "", "")

    def text(v):
        return v.strip() if isinstance(v, str) else ("" if v is None else str(v))

    return {
        "json_label": json_label,
        "identifier": identifier,
        "gender": text(gp.get("gender")),
        "age_range": text(gp.get("age_range")),
        "distribution": text(di.get("distribution")),
        "onset": text(di.get("onset")),
        "symptom": text(di.get("symptom")),
        "bodypart": text(di.get("bodypart")),
        "lesion_count": str(len(lesions)),
        "inflammatory_lesions": str(sum(1 for l in lesions if isinstance(l, dict) and l.get("inflammatory") is True)),
        "prefix": prefix,
        "p_code": p_code,
        "l_code": l_code,
    }


# ─────────────────────────────────────────────────────────────────────────────
def print_table(rows: list[dict]) -> None:
    """label × split × view 장수 표. 끝에 클래스 불균형 경고."""
    cells: dict[tuple[str, str, str], int] = Counter()
    views = sorted({r["view"] for r in rows}) or [""]
    for r in rows:
        cells[(r["label"], r["split"], r["view"])] += 1
    cols = [(s, v) for s in ("train", "val") for v in views]
    head = "  label      " + "".join(f"{s}/{v or '-'}".rjust(13) for s, v in cols) + "       total"
    print(head)
    totals: dict[str, int] = {}
    for label in CLASSES:
        n = [cells.get((label, s, v), 0) for s, v in cols]
        totals[label] = sum(n)
        print("  " + label.ljust(11) + "".join(f"{x:13,}" for x in n) + f"{sum(n):12,}")
    print("  " + "total".ljust(11)
          + "".join(f"{sum(cells.get((l, s, v), 0) for l in CLASSES):13,}" for s, v in cols)
          + f"{len(rows):12,}")
    missing = [l for l, n in totals.items() if n == 0]
    if missing:
        warn(f"장수가 0 인 클래스: {missing} — 폴더 토큰({list(FOLDER_TOKEN_TO_CODE)})이 이름에 없거나 경로가 빠졌다")
    present = [n for n in totals.values() if n > 0]
    if len(present) >= 2 and max(present) >= 10 * min(present):
        warn(f"클래스별 장수가 10배 이상 차이난다 (최다 {max(present):,} / 최소 {min(present):,}) — "
             f"train.py 의 가중치 상한(--w-cap)이 걸릴 수 있다. 데이터가 덜 풀렸는지 먼저 의심할 것")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("inputs", nargs="+", type=Path, help="다운로드 폴더 / 풀린 폴더 / zip (여러 개, 섞여도 됨)")
    ap.add_argument("--out", type=Path, help="manifest.csv 경로 (기본: --extract-to 아래, 없으면 ./manifest.csv)")
    ap.add_argument("--extract-to", type=Path,
                    help="zip 을 이 폴더에 푼다(zip 이름별 하위 폴더). zip 이 있는데 이 옵션이 없으면 멈춘다")
    ap.add_argument("--re-extract", action="store_true", help="이미 풀린 폴더가 있어도 다시 푼다")
    args = ap.parse_args()

    extract_to = args.extract_to.expanduser().resolve() if args.extract_to else None
    if extract_to is not None:
        extract_to.mkdir(parents=True, exist_ok=True)
    out = args.out or ((extract_to / "manifest.csv") if extract_to else Path("manifest.csv"))
    out = out.expanduser().resolve()

    t0 = time.time()
    images, label_jsons, jsons_by_stem, unmatched, mask_count, zips_pending = collect(
        [p.expanduser() for p in args.inputs], extract_to, args.re_extract)

    if zips_pending:
        print(f"\n❌ zip 파일 {len(zips_pending)}개가 있는데 --extract-to 가 없다. 예: {zips_pending[0].name}")
        print("   학습은 풀린 파일에서 해야 하므로 풀 위치를 정해 다시 실행한다:")
        print(f"   python3 {Path(sys.argv[0]).name} {' '.join(str(p) for p in args.inputs)} --extract-to <풀 폴더>")
        return 2

    if not images:
        print("\n❌ 이미지를 하나도 찾지 못했다. 폴더/zip 이름이 'TS_여드름_정면' 꼴인지, 경로가 맞는지 확인할 것.")
        if unmatched:
            print("   규칙에 안 맞은 폴더(이미지/JSON 수):")
            for d, n in unmatched.most_common(10):
                print(f"     {d}: {n}")
        return 1

    # image_id: 파일명 stem. 겹치면 그 파일들만 라벨·뷰를 앞에 붙인다.
    # 전부에 붙이지 않는 이유: evaluate.py 가 image_id 로 파일을 찾는다(--image-dir 안에서 stem 매칭).
    # 겹치는 것이 하나라도 있으면 그 파일들은 그 방식으로 못 찾으므로 경고로 알린다.
    stem_count = Counter(p.stem for _, p in images)
    collided = {s for s, n in stem_count.items() if n > 1}

    rows: list[dict] = []
    json_hit = json_fallback = 0
    for g, p in images:
        key = (g.label, g.view, p.stem)
        lj = label_jsons.get(key)
        if lj is not None:
            json_hit += 1
        elif len(jsons_by_stem.get(p.stem, [])) == 1:
            lj = jsons_by_stem[p.stem][0]
            json_fallback += 1
        image_id = f"{g.label}_{g.view or 'noview'}_{p.stem}" if p.stem in collided else p.stem
        row = {c: "" for c in MANIFEST_COLUMNS}
        row.update({
            "image_id": image_id,
            "path": str(p),
            "label": g.label,
            "view": g.view,
            "split": g.split,
            "src": g.src,
            "label_json": str(lj) if lj else "",
            "lesion_count": "0",
            "inflammatory_lesions": "0",
        })
        rows.append(row)

    # ── 라벨 JSON 파싱: 메타 열 + diagnosis_name 교차검증 ──
    # 폴더 토큰이 말하는 클래스와 JSON 이 말하는 클래스가 다르면 어느 쪽도 믿을 수 없다. 그 상태로 학습하면
    # 서버는 멀쩡히 뜨고 병명만 틀린다. 하나라도 있으면 종료코드 1 — 사람이 봐야 한다.
    mismatches: list[tuple[str, str, str]] = []     # (image_id, 폴더 라벨, JSON 라벨)
    no_name = read_err = id_mismatch = 0
    meta_dist: dict[str, Counter] = defaultdict(Counter)
    for r in rows:
        if not r["label_json"]:
            continue
        try:
            meta = parse_label_json(Path(r["label_json"]))
        except (OSError, ValueError) as exc:
            read_err += 1
            if read_err <= 3:
                warn(f"라벨 JSON 읽기 실패: {r['label_json']} — {exc}")
            continue
        json_label = meta.pop("json_label")
        if not json_label:
            no_name += 1
        elif json_label != r["label"]:
            mismatches.append((r["image_id"], r["label"], json_label))
        if meta["identifier"] and meta["identifier"] != Path(r["path"]).stem:
            id_mismatch += 1
        for k, v in meta.items():
            r[k] = v
        for k in ("gender", "age_range", "distribution", "onset", "symptom", "bodypart"):
            meta_dist[k][r[k] or "(빈칸)"] += 1

    rows.sort(key=lambda r: (r["split"], r["label"], r["view"], r["image_id"]))
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=MANIFEST_COLUMNS)
        w.writeheader()
        w.writerows(rows)

    # ── 요약 ──
    print()
    print(f"manifest: {out}  ({len(rows):,}장, {time.time() - t0:.0f}s)")
    print_table(rows)
    no_json = sum(1 for r in rows if not r["label_json"])
    print(f"  라벨 JSON 짝: 같은 클래스·뷰·stem {json_hit:,} / stem 만으로 {json_fallback:,} / 없음 {no_json:,}"
          + (f"   (마스크 PNG {mask_count:,}개는 기록하지 않음)" if mask_count else ""))
    if mismatches:
        print(f"  ❌ 폴더 라벨 ≠ JSON diagnosis_name: {len(mismatches):,}건 (예: {mismatches[:3]})")
    else:
        checked = sum(1 for r in rows if r["label_json"]) - no_name - read_err
        print(f"  ✅ JSON diagnosis_name 교차검증 통과: {checked:,}장 (diagnosis_name 없음 {no_name:,}, 읽기 실패 {read_err:,})")
    if id_mismatch:
        warn(f"JSON identifier 가 파일명 stem 과 다른 파일 {id_mismatch:,}개 — 짝짓기는 stem 으로 했으니 학습엔 영향 없지만 원본을 확인할 것")
    for k in ("gender", "age_range", "distribution", "onset", "symptom", "bodypart"):
        if meta_dist[k]:
            top = ", ".join(f"{v}={n:,}" for v, n in meta_dist[k].most_common(6))
            more = f" … 외 {len(meta_dist[k]) - 6}종" if len(meta_dist[k]) > 6 else ""
            print(f"  {k:13s}: {top}{more}")
    if collided:
        warn(f"파일명 stem 이 겹치는 이미지 {sum(stem_count[s] for s in collided):,}장 — image_id 에 '<label>_<view>_' 를 "
             f"붙였다. 이 파일들은 evaluate.py --image-dir 방식으로는 못 찾으니 평가 때 manifest 의 path 를 써야 한다")
    if unmatched:
        warn(f"규칙(TS_/TL_/VS_/VL_ + 질환 토큰)에 안 맞아 뺀 이미지/JSON {sum(unmatched.values()):,}개 — "
             f"예: {unmatched.most_common(1)[0][0]}")
    if WARNINGS:
        print(f"\n경고 {len(WARNINGS)}건:")
        for m in WARNINGS:
            print(f"  - {m}")
    if mismatches:
        print("\n❌ 폴더 토큰과 JSON diagnosis_name 이 다른 파일이 있어 manifest 를 믿을 수 없다. "
              "zip 을 다른 폴더에 잘못 풀었거나 데이터셋 구조가 바뀐 것이다. 위 예시 파일을 열어 확인할 것.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
