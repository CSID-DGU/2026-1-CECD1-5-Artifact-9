#!/usr/bin/env python3
"""
AI허브 데이터셋 점검 스크립트 — 압축을 풀지 않고 구조·라벨 스키마·클래스 분포를 출력한다.

왜 필요한가
----------
AI허브 데이터셋은 페이지의 "메타데이터 구조표"와 실제 파일이 다른 경우가 잦고,
안심존(외부망 차단) 데이터는 아예 밖에서 볼 수가 없다. 그런데 학습 코드는 라벨 JSON 의
**정확한 키 이름**을 알아야 한다. 이 스크립트는 그 정보를 사람이 읽을 수 있는 한 장의
보고서로 뽑는다 — 표준 라이브러리만 쓰므로 어디서든(맥·안심존 리눅스 서버·Colab) 돈다.

무엇을 출력하나
--------------
  [1] 폴더/압축 파일별 파일 수 (확장자별)
  [2] 파일명 패턴 표본 (각 그룹에서 몇 개)
  [3] 이미지 해상도 표본 (PNG/JPEG 헤더만 읽는다 — Pillow 불필요)
  [4] 라벨 JSON 의 키 구조 (중첩 경로 · 타입 · 값 표본)
  [5] 라벨 JSON 안에서 값 종류가 적은 키들의 값 분포 (= 클래스/중증도/부위 후보)

사용법
------
    # 다운로드 폴더 통째로 (zip 을 풀지 않아도 된다)
    python3 inspect_dataset.py ~/Downloads/안면부_피부질환 --out report.txt

    # zip 몇 개만
    python3 inspect_dataset.py VL_여드름_정면.zip TL_여드름_정면.zip

    # 안심존 서버 (데이터 마운트 경로)
    python3 -I inspect_dataset.py /data/화상 --json-scan 500 --out ~/burn_report.txt

출력 파일(report.txt)을 그대로 공유하면 된다. 안심존처럼 파일 반출이 안 되는 곳에서는
화면에 찍힌 보고서를 스크린샷으로 남긴다 — 그래서 한 화면에 들어가도록 짧게 찍는다.
"""

from __future__ import annotations

import argparse
import io
import json
import os
import struct
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

IMAGE_EXT = {".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff", ".webp", ".dcm", ".svs", ".mrxs"}
LABEL_EXT = {".json", ".csv", ".txt", ".xml"}

# 경고는 화면에만 찍고 끝내지 않는다. 첫 실행에서 "경로 없음" 이 터미널에만 남고 보고서 파일은
# 빈 채로 공유되는 바람에 원인을 아무도 알 수 없었다. 전부 모아 보고서 머리에 싣는다.
WARNINGS: list[str] = []


def warn(msg: str) -> None:
    WARNINGS.append(msg)
    print(f"  ⚠️  {msg}")


def describe_path(raw: str) -> list[str]:
    """입력 경로가 무엇인지(있는지, 폴더인지, 안에 뭐가 있는지) 사람이 읽을 수 있게 적는다."""
    p = Path(raw).expanduser()
    lines = [f"  입력: {raw}  →  {p}"]
    if not p.exists():
        lines.append("     존재하지 않는다. 경로 오타이거나 <자리표시자> 를 그대로 넣었을 수 있다. "
                     "Finder 에서 폴더를 터미널 창으로 끌어다 놓으면 정확한 경로가 입력된다.")
        parent = p.parent
        if parent.exists():
            try:
                near = sorted(x.name for x in parent.iterdir())[:30]
                lines.append(f"     상위 폴더 {parent} 안의 항목: {near}")
            except PermissionError:
                lines.append(f"     상위 폴더 {parent} 를 읽을 권한이 없다.")
        return lines
    if p.is_file():
        lines.append(f"     파일 1개 ({p.stat().st_size / 1e6:,.1f} MB). zip 이 아니면 내용은 보지 않는다.")
        return lines
    try:
        entries = sorted(p.iterdir(), key=lambda x: x.name)
    except PermissionError:
        lines.append("     폴더를 읽을 권한이 없다. macOS 라면: 시스템 설정 → 개인정보 보호 및 보안 → 파일 및 폴더 → "
                     "터미널(또는 iTerm) → '다운로드 폴더' 허용. 또는 폴더를 ~/aihub 처럼 다른 곳으로 옮겨서 다시 실행.")
        return lines
    lines.append(f"     폴더. 바로 아래 항목 {len(entries)}개:")
    for x in entries[:40]:
        try:
            kind = "폴더" if x.is_dir() else f"{x.stat().st_size / 1e6:,.1f} MB"
        except OSError:
            kind = "?"
        lines.append(f"       - {x.name}  ({kind})")
    if len(entries) > 40:
        lines.append(f"       … 외 {len(entries) - 40}개")
    return lines


# ─────────────────────────────────────────────────────────────────────────────
# 파일 열거 — 폴더와 zip 을 같은 모양으로 다룬다
# ─────────────────────────────────────────────────────────────────────────────
class Entry:
    """폴더 안의 파일이든 zip 안의 항목이든 (그룹, 이름, 크기, 읽기 함수) 로 통일한다."""

    __slots__ = ("group", "name", "size", "_reader")

    def __init__(self, group: str, name: str, size: int, reader):
        self.group = group
        self.name = name
        self.size = size
        self._reader = reader

    def read(self, limit: int | None = None) -> bytes:
        return self._reader(limit)

    @property
    def ext(self) -> str:
        return Path(self.name).suffix.lower()


def _fix_zip_name(info: zipfile.ZipInfo) -> str:
    """
    AI허브 zip 은 윈도우에서 만들어져 파일명이 CP949 인 경우가 많다.
    zipfile 은 UTF-8 플래그가 없으면 cp437 로 해석해 한글이 깨진다 — 되돌려 본다.
    """
    if info.flag_bits & 0x800:
        return info.filename
    try:
        return info.filename.encode("cp437").decode("cp949")
    except (UnicodeEncodeError, UnicodeDecodeError):
        return info.filename


def iter_zip(zip_path: Path, group_prefix: str = ""):
    try:
        zf = zipfile.ZipFile(zip_path)
    except (zipfile.BadZipFile, OSError) as exc:
        warn(f"zip 열기 실패(손상/미완료 다운로드/권한?): {zip_path} — {exc}")
        return
    group = f"{group_prefix}{zip_path.name}"
    for info in zf.infolist():
        if info.is_dir():
            continue
        name = _fix_zip_name(info)

        def reader(limit, _zf=zf, _info=info):
            with _zf.open(_info) as fh:
                return fh.read() if limit is None else fh.read(limit)

        yield Entry(group, name, info.file_size, reader)


def iter_dir(root: Path):
    # os.walk 는 권한 오류를 기본적으로 삼킨다 — 그러면 "파일이 하나도 없다" 로만 보인다. 반드시 받아 적는다.
    for dirpath, dirnames, filenames in os.walk(root, onerror=lambda e: warn(f"읽기 실패: {e.filename} — {e.strerror}")):
        dirnames.sort()
        rel_dir = Path(dirpath).relative_to(root)
        for fn in sorted(filenames):
            p = Path(dirpath) / fn
            if p.suffix.lower() == ".zip":
                # zip 은 풀지 않고 안을 들여다본다. 그룹명에 상대 경로를 붙여 어디 있던 zip 인지 남긴다.
                yield from iter_zip(p, group_prefix=f"{rel_dir}/" if str(rel_dir) != "." else "")
                continue
            group = str(rel_dir) if str(rel_dir) != "." else root.name

            def reader(limit, _p=p):
                with open(_p, "rb") as fh:
                    return fh.read() if limit is None else fh.read(limit)

            yield Entry(group, str(rel_dir / fn), p.stat().st_size, reader)


def iter_inputs(paths: list[str]):
    for raw in paths:
        p = Path(raw).expanduser()
        if not p.exists():
            warn(f"경로 없음: {p}")
            continue
        if p.is_dir():
            yield from iter_dir(p)
        elif p.suffix.lower() == ".zip":
            yield from iter_zip(p)
        else:
            def reader(limit, _p=p):
                with open(_p, "rb") as fh:
                    return fh.read() if limit is None else fh.read(limit)
            yield Entry(p.parent.name, p.name, p.stat().st_size, reader)


# ─────────────────────────────────────────────────────────────────────────────
# 이미지 해상도 — 헤더만 읽는다
# ─────────────────────────────────────────────────────────────────────────────
def image_size(head: bytes) -> tuple[int, int] | None:
    if head[:8] == b"\x89PNG\r\n\x1a\n" and len(head) >= 24:
        w, h = struct.unpack(">II", head[16:24])
        return w, h
    if head[:2] == b"\xff\xd8":
        i = 2
        n = len(head)
        while i + 9 < n:
            if head[i] != 0xFF:
                i += 1
                continue
            marker = head[i + 1]
            if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
                i += 2
                continue
            seg_len = struct.unpack(">H", head[i + 2:i + 4])[0]
            if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                h, w = struct.unpack(">HH", head[i + 5:i + 9])
                return w, h
            i += 2 + seg_len
    return None


# ─────────────────────────────────────────────────────────────────────────────
# JSON 스키마 — 키 경로별 타입과 값 표본
# ─────────────────────────────────────────────────────────────────────────────
def walk_json(obj, path: str, schema: dict, values: dict, depth: int = 0):
    """schema[path] = Counter(type), values[path] = Counter(값) — 리스트는 [] 로 접어 경로를 합친다."""
    kind = type(obj).__name__
    schema[path][kind] += 1
    if isinstance(obj, dict):
        for k, v in obj.items():
            walk_json(v, f"{path}.{k}" if path else k, schema, values, depth + 1)
    elif isinstance(obj, list):
        schema[path]["list_len=" + str(len(obj))] += 0  # 길이는 따로 센다
        values[path + "#len"][len(obj)] += 1
        for v in obj[:50]:
            walk_json(v, path + "[]", schema, values, depth + 1)
    else:
        if isinstance(obj, (str, int, float, bool)) or obj is None:
            v = obj if not isinstance(obj, str) else obj[:60]
            values[path][v] += 1


def short(v, n: int = 40) -> str:
    s = json.dumps(v, ensure_ascii=False) if not isinstance(v, str) else v
    return s if len(s) <= n else s[: n - 1] + "…"


# ─────────────────────────────────────────────────────────────────────────────
def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="+", help="폴더 또는 zip 파일 (여러 개 가능)")
    ap.add_argument("--json-scan", type=int, default=300,
                    help="그룹당 값 분포를 셀 JSON 파일 수 (기본 300). 클수록 정확하지만 느리다.")
    ap.add_argument("--samples", type=int, default=3, help="그룹당 파일명/해상도 표본 수 (기본 3)")
    ap.add_argument("--max-distinct", type=int, default=30,
                    help="이 개수 이하의 서로 다른 값을 갖는 키만 '클래스 후보'로 분포를 찍는다 (기본 30)")
    ap.add_argument("--out", help="보고서를 이 파일에도 저장한다")
    args = ap.parse_args()

    out_lines: list[str] = []

    def emit(line: str = "") -> None:
        print(line)
        out_lines.append(line)

    counts: dict[str, Counter] = defaultdict(Counter)        # group -> ext -> n
    bytes_total: dict[str, int] = Counter()                  # group -> bytes
    name_samples: dict[str, list[str]] = defaultdict(list)
    img_sizes: dict[str, Counter] = defaultdict(Counter)     # group -> (w,h) -> n (표본만)
    json_schema: dict[str, dict] = defaultdict(lambda: defaultdict(Counter))
    json_values: dict[str, dict] = defaultdict(lambda: defaultdict(Counter))
    json_scanned: Counter = Counter()
    json_errors: Counter = Counter()
    json_first: dict[str, str] = {}
    nested_zips: Counter = Counter()

    for e in iter_inputs(args.paths):
        g = e.group
        counts[g][e.ext or "(없음)"] += 1
        bytes_total[g] += e.size
        if len(name_samples[g]) < args.samples:
            name_samples[g].append(e.name)
        if e.ext == ".zip":
            nested_zips[g] += 1
            continue
        if e.ext in IMAGE_EXT and sum(img_sizes[g].values()) < args.samples * 4:
            try:
                sz = image_size(e.read(65536))
                if sz:
                    img_sizes[g][sz] += 1
            except Exception:
                pass
        if e.ext == ".json" and json_scanned[g] < args.json_scan:
            try:
                raw = e.read()
                text = raw.decode("utf-8-sig") if raw[:3] == b"\xef\xbb\xbf" or b"\x00" not in raw[:4] else raw.decode("utf-16")
                obj = json.loads(text)
            except Exception as exc:
                json_errors[g] += 1
                if json_errors[g] <= 2:
                    warn(f"JSON 파싱 실패 [{g}] {e.name}: {type(exc).__name__}: {exc}")
                continue
            json_scanned[g] += 1
            if g not in json_first:
                json_first[g] = short(obj, 400)
            walk_json(obj, "", json_schema[g], json_values[g])

    groups = sorted(counts)
    total_files = sum(sum(c.values()) for c in counts.values())

    emit("=" * 78)
    emit(f"[0] 실행 정보 — 입력 {len(args.paths)}개, 찾은 파일 {total_files:,}개, 경고 {len(WARNINGS)}건")
    emit("=" * 78)
    for w in WARNINGS[:50]:
        emit(f"  ⚠️  {w}")
    if len(WARNINGS) > 50:
        emit(f"  … 경고 {len(WARNINGS) - 50}건 더 있음")
    if total_files == 0:
        emit("  ❌ 파일을 하나도 찾지 못했다. 아래 경로 진단을 보고 경로/권한을 고친 뒤 다시 실행할 것.")
        for raw in args.paths:
            for line in describe_path(raw):
                emit(line)
    emit()

    emit("=" * 78)
    emit("[1] 그룹별 파일 수  (그룹 = 폴더 또는 zip)")
    emit("=" * 78)
    for g in groups:
        ext_str = ", ".join(f"{ext} {n:,}" for ext, n in counts[g].most_common())
        mb = bytes_total[g] / 1e6
        emit(f"  {g}  [{mb:,.1f} MB]  {ext_str}")
        if nested_zips[g]:
            emit(f"      ↳ 안에 zip 이 {nested_zips[g]}개 더 있다 — 먼저 풀어서 다시 돌릴 것")

    emit()
    emit("=" * 78)
    emit("[2] 파일명 표본")
    emit("=" * 78)
    for g in groups:
        for n in name_samples[g]:
            emit(f"  {g}: {n}")

    emit()
    emit("=" * 78)
    emit("[3] 이미지 해상도 표본 (가로x세로: 장수)")
    emit("=" * 78)
    for g in groups:
        if img_sizes[g]:
            emit(f"  {g}: " + ", ".join(f"{w}x{h}: {n}" for (w, h), n in img_sizes[g].most_common(6)))

    emit()
    emit("=" * 78)
    emit("[4] 라벨 JSON 키 구조  (경로 : 타입 — 값 표본)")
    emit("=" * 78)
    for g in groups:
        if not json_scanned[g]:
            continue
        emit(f"  ── {g}  (JSON {json_scanned[g]}개 읽음, 실패 {json_errors[g]}개)")
        emit(f"     첫 파일: {json_first[g]}")
        for path, kinds in sorted(json_schema[g].items()):
            if not path:
                continue
            kind_str = "/".join(k for k in kinds if not k.startswith("list_len"))
            sample = ""
            if path in json_values[g]:
                top = [short(v) for v, _ in json_values[g][path].most_common(3)]
                sample = " — 예: " + " | ".join(top)
            emit(f"     {path} : {kind_str}{sample}")

    emit()
    emit("=" * 78)
    emit(f"[5] 클래스/중증도/부위 후보 — 서로 다른 값이 {args.max_distinct}개 이하인 키의 값 분포")
    emit("=" * 78)
    for g in groups:
        if not json_scanned[g]:
            continue
        emit(f"  ── {g}")
        for path, ctr in sorted(json_values[g].items()):
            if path.endswith("#len"):
                continue
            total = sum(ctr.values())
            if total < 2 or len(ctr) > args.max_distinct or len(ctr) == 1 and total == 1:
                continue
            # 이미지 파일명·좌표처럼 거의 다 다른 값은 후보가 아니다
            if len(ctr) > total * 0.5 and total > 10:
                continue
            dist = ", ".join(f"{short(v, 24)}={n}" for v, n in ctr.most_common(12))
            more = f" … 외 {len(ctr) - 12}종" if len(ctr) > 12 else ""
            emit(f"     {path}  ({len(ctr)}종 / {total}건): {dist}{more}")

    emit()
    emit("보고서 끝. 이 출력을 그대로 공유하면 라벨 키 이름을 확정할 수 있다.")

    if args.out:
        Path(args.out).expanduser().write_text("\n".join(out_lines) + "\n", encoding="utf-8")
        print(f"\n💾 저장: {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
