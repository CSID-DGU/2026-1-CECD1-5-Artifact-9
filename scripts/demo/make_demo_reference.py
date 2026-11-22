"""데모 KCD·약품 검색 데이터 — backend/src/main/resources/data 의 원본 엑셀에서 피부과 범위만 추린다.

  python scripts/demo/make_demo_reference.py      (openpyxl 필요)

출력: frontend/src/demo/data/kcd.json, drugs.json
"""
import json
from pathlib import Path
import openpyxl

REPO = Path(__file__).resolve().parents[2]
DATA = REPO / "backend/src/main/resources/data"
OUT = REPO / "frontend/src/demo/data"

# 피부 및 피하조직 질환(L) + 피부암·모반·혈관종 + 피부 감염 일부
KCD_PREFIX = ("L", "C43", "C44", "D03", "D04", "D18", "D22", "D23", "B00", "B02", "B07", "B08", "B35", "B36", "B37", "B85", "B86", "R21", "R23")
# 피부과에서 자주 쓰는 성분 — 성분마다 최대 CAP 개
KEYWORDS = ["아다팔렌", "트레티노인", "이소트레티노인", "클린다마이신", "과산화벤조일", "벤조일퍼옥사이드", "아젤라산", "독시사이클린", "미노사이클린",
            "메트로니다졸", "이버멕틴", "브리모니딘", "히드로코르티손", "베타메타손", "모메타손", "클로베타솔", "데속시메타손",
            "프레드니카르베이트", "메틸프레드니솔론", "트리암시놀론", "타크로리무스", "피메크로리무스", "케토코나졸", "테르비나핀", "이트라코나졸",
            "플루코나졸", "클로트리마졸", "칼시포트리올", "메토트렉세이트", "시클로스포린", "이미퀴모드", "플루오로우라실", "무피로신", "퓨시드산",
            "세티리진", "레보세티리진", "펙소페나딘", "로라타딘", "데스로라타딘", "에피나스틴", "올로파타딘", "아시클로버", "발라시클로버", "팜시클로버",
            "우레아", "덱스판테놀", "히알루론산"]
CAP = 24


def rows(name):
    wb = openpyxl.load_workbook(DATA / name, read_only=True)
    for i, row in enumerate(wb[wb.sheetnames[0]].iter_rows(values_only=True)):
        if i and row[0] and row[1]:
            yield str(row[0]).strip(), str(row[1]).strip(), (str(row[2]).strip() if row[2] else None)


seen, kcd = set(), []
for code, name, name_en in rows("kcd_disease.xlsx"):
    if code not in seen and code.startswith(KCD_PREFIX):
        seen.add(code)
        kcd.append({"id": len(kcd) + 1, "code": code, "nameKr": name, "nameEn": name_en})

per, seen, drugs = {k: 0 for k in KEYWORDS}, set(), []
for code, name, name_en in rows("drug_master.xlsx"):
    if code in seen:
        continue
    for k in KEYWORDS:
        if k in name and per[k] < CAP:
            per[k] += 1
            seen.add(code)
            drugs.append({"id": len(drugs) + 1, "code": code, "nameKr": name, "nameEn": name_en})
            break

(OUT / "kcd.json").write_text(json.dumps(kcd, ensure_ascii=False, separators=(",", ":")))
(OUT / "drugs.json").write_text(json.dumps(drugs, ensure_ascii=False, separators=(",", ":")))
print("kcd", len(kcd), "drugs", len(drugs))
