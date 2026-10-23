# 손으로 타이핑할 수 있는 초소형 데이터 점검 스크립트 (표준 라이브러리만, 30줄).
# 안심존처럼 파일 반입도 클립보드도 막힌 곳에서 Jupyter 셀에 그대로 쳐 넣고 실행한다.
# 전체 보고서는 inspect_dataset.py — 이 파일은 그 축약판으로, 폴더별 파일 수와 JSON 한 개의 키 구조만 찍는다.
# 사용: python3 inspect_mini.py /data/화상   (Jupyter 에서는 ROOT 만 바꿔 셀로 실행)
import json, os, sys, collections
ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
cnt, sample = collections.Counter(), {}
for d, _, fs in os.walk(ROOT):
    for f in fs:
        ext = os.path.splitext(f)[1].lower()
        cnt[(os.path.relpath(d, ROOT), ext)] += 1
        if ext == ".json" and os.path.relpath(d, ROOT) not in sample:
            sample[os.path.relpath(d, ROOT)] = os.path.join(d, f)
print("[폴더별 파일 수]")
for (d, ext), n in sorted(cnt.items()):
    print(f"  {d}  {ext or '(없음)'}  {n}")
def keys(o, p="", out=None):
    out = [] if out is None else out
    if isinstance(o, dict):
        for k, v in o.items(): keys(v, f"{p}.{k}" if p else k, out)
    elif isinstance(o, list) and o:
        keys(o[0], p + "[]", out)
    else:
        out.append(f"{p} = {str(o)[:40]!r}")
    return out
print("[JSON 키 구조 — 폴더당 첫 파일]")
for d, f in sorted(sample.items()):
    print(f"  -- {d}: {os.path.basename(f)}")
    try:
        for line in keys(json.load(open(f, encoding="utf-8-sig"))): print("     " + line)
    except Exception as e:
        print("     읽기 실패:", e)
