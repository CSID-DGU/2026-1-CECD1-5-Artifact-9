#!/usr/bin/env python3
"""
안면부 피부질환(AI허브 71863) 다운로드 폴더 → manifest.csv

왜 manifest 를 먼저 만드나
------------------------
AI허브 데이터는 zip 수십 개(TS_/TL_/VS_/VL_ × 질환 × 뷰)로 오고, 라벨 JSON 의 키 이름은 받아 보기
전까지 모른다(docs/aihub-datasets-plan.md §1-1). 학습 코드가 폴더 구조와 JSON 스키마를 직접 알게
하면 데이터가 조금만 달라도 train.py 를 고쳐야 한다. 그래서 "어떤 파일이 어떤 클래스·뷰·분할인가"를
한 장의 CSV 로 먼저 고정하고, train.py / make_holdout.py / evaluate.py 는 그 CSV 만 본다.
클래스는 zip/폴더 이름의 토큰(`_여드름_`, `_정상_` …)으로 확정한다 — JSON 키가 뭐든 학습은 가능하다.

출력 열
------
  image_id   파일명 stem. 전체에서 겹치면 그 파일만 `<label>_<view>_<stem>` 으로 바꾼다(아래 경고 참고).
  path       풀린 이미지 파일의 절대 경로 (학습은 풀린 파일에서 해야 빠르다 — zip 안에서 매번 읽지 않는다)
  label      클래스 코드 (classes.py CLASSES 중 하나)
  view       front(정면 1024²) / side(측면 512²)
  split      train(TS/TL) / val(VS/VL) — AI허브가 준 분할 그대로
  src        그 파일이 들어 있던 zip/폴더 이름 (예: VS_여드름_정면) — 원본을 되짚을 때 쓴다
  severity   --severity-key 를 줬을 때만 채운다. 모르면 빈칸
  label_json 같은 stem 의 라벨 JSON 경로 (TL_/VL_ 에서 짝지음). 없으면 빈칸

사용법
------
    # zip 그대로 둔 다운로드 폴더 → /data/facial 에 풀면서 manifest 생성
    python3 build_manifest.py ~/Downloads/안면부 --extract-to /data/facial --out /data/facial/manifest.csv

    # 이미 풀린 폴더 (zip 과 섞여 있어도 된다)
    python3 build_manifest.py /data/facial --out /data/facial/manifest.csv

    # inspect_dataset.py 보고서로 중증도 키 경로를 확인한 뒤
    python3 build_manifest.py /data/facial --out manifest.csv --severity-key "meta.severity"

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
import zipfile
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))            # classes.py (이 태스크의 클래스·토큰 표)
sys.path.insert(0, str(HERE.parent))     # inspect_dataset.py (zip 한글 파일명 복원)

from classes import CLASSES, FOLDER_TOKEN_TO_CODE, PREFIX_TO_SPLIT, VIEW_TOKEN_TO_CODE  # noqa: E402
from inspect_dataset import _fix_zip_name  # noqa: E402

IMAGE_EXT = {".png", ".jpg", ".jpeg", ".bmp", ".webp"}
MANIFEST_COLUMNS = ["image_id", "path", "label", "view", "split", "src", "severity", "label_json"]

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
    """zip 항목 이름을 dest 아래로만 떨어지게 한다(../ 나 절대경로는 버린다)."""
    rel = Path(name.replace("\\", "/"))
    if rel.is_absolute() or any(p == ".." for p in rel.parts):
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
# 라벨 JSON 에서 중증도 뽑기 — 키 경로는 설정값이다
# ─────────────────────────────────────────────────────────────────────────────
_MISSING = object()


def dig(obj, key_path: str):
    """'a.b.0.c' 처럼 점으로 이은 경로를 따라간다. 숫자는 리스트 인덱스. 없으면 _MISSING."""
    cur = obj
    for part in key_path.split("."):
        if isinstance(cur, dict):
            if part not in cur:
                return _MISSING
            cur = cur[part]
        elif isinstance(cur, list):
            try:
                cur = cur[int(part)]
            except (ValueError, IndexError):
                return _MISSING
        else:
            return _MISSING
    return cur


def load_json(path: Path):
    raw = path.read_bytes()
    for enc in ("utf-8-sig", "cp949"):
        try:
            return json.loads(raw.decode(enc))
        except (UnicodeDecodeError, json.JSONDecodeError):
            continue
    raise ValueError("utf-8/cp949 어느 쪽으로도 읽히지 않음")


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
    ap.add_argument("--severity-key", default=None,
                    help="라벨 JSON 안의 중증도 키 경로 (예: 'meta.severity', 'annotations.0.grade'). "
                         "inspect_dataset.py 보고서 [4]/[5] 에서 확인한 뒤 준다. 없으면 severity 는 빈칸")
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
        rows.append({
            "image_id": image_id,
            "path": str(p),
            "label": g.label,
            "view": g.view,
            "split": g.split,
            "src": g.src,
            "severity": "",
            "label_json": str(lj) if lj else "",
        })

    # 중증도 — 키 경로가 주어졌을 때만. 못 찾아도 죽지 않는다(한 줄 경고 + 건수).
    sev_values: Counter = Counter()
    if args.severity_key:
        miss = err = 0
        for r in rows:
            if not r["label_json"]:
                continue
            try:
                obj = load_json(Path(r["label_json"]))
            except (OSError, ValueError):
                err += 1
                continue
            v = dig(obj, args.severity_key)
            if v is _MISSING or v is None:
                miss += 1
                continue
            r["severity"] = v if isinstance(v, str) else json.dumps(v, ensure_ascii=False)
            sev_values[r["severity"]] += 1
        if miss or err:
            warn(f"--severity-key '{args.severity_key}' 로 중증도를 못 채운 JSON {miss:,}개, 읽기 실패 {err:,}개 "
                 f"(채움 {sum(sev_values.values()):,}개). 키 경로는 inspect_dataset.py 보고서 [4] 의 경로 표기 그대로다")

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
    if sev_values:
        print("  severity 값 분포: " + ", ".join(f"{v}={n:,}" for v, n in sev_values.most_common(10)))
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
    return 0


if __name__ == "__main__":
    sys.exit(main())
