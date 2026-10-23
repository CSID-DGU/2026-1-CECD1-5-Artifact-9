"""
안면부 피부질환(AI허브 71863) 태스크의 클래스 목록 — 학습 파이프라인 쪽의 단일 출처.

fastapi/main.py TASKS['facial']['classes'] 와 **순서까지** 같아야 하며 tests/test_model_contract.py 가 대조한다.
그 테스트는 이 파일을 import 하지 않고 ast 로 읽는다 — 그래서 TASK 와 CLASSES 는 반드시
문자열/리스트 **리터럴**로 적는다(계산식·내포·다른 모듈 참조 금지). 리터럴이 아니면 테스트가
"선언하지 않음"으로 보고 실패한다.

순서가 곧 모델 출력 인덱스의 의미다. 바꾸면 서버는 멀쩡히 뜨고 병명만 조용히 뒤바뀐다.
계약(docs/aihub-datasets-plan.md §3-1)은 코드를 알파벳순으로 고정했고, 뒤에만 덧붙일 수 있다.
"""

TASK = "facial"

CLASSES = ["acne", "atopic", "normal", "psoriasis", "rosacea", "seb_derm"]

# 한글 병명 — 계약 §3-1 표. main.py names_ko · DB 시드(V10) · Java 맵과 글자 단위로 같아야 한다.
NAMES_KO = {
    "acne":      "여드름",
    "atopic":    "아토피피부염",
    "normal":    "정상 피부",
    "psoriasis": "건선",
    "rosacea":   "주사(장미증)",
    "seb_derm":  "지루피부염",
}

# AI허브 zip/폴더 이름의 질환 토큰 → 클래스 코드. 예: VL_여드름_정면.zip, TS_정상_측면/
# 라벨 JSON 의 annotations[0].diagnosis_info.diagnosis_name 값도 정확히 이 여섯 토큰이다
# (2026-10-23 inspect_dataset.py 보고서로 확인, docs/aihub-datasets-plan.md §7-1). build_manifest.py 는
# 폴더 토큰으로 클래스를 정하고 JSON 의 diagnosis_name 으로 교차검증한다 — 둘이 다르면 멈춘다.
FOLDER_TOKEN_TO_CODE = {
    "건선":   "psoriasis",
    "아토피": "atopic",
    "여드름": "acne",
    "정상":   "normal",
    "주사":   "rosacea",
    "지루":   "seb_derm",
}

# 뷰 토큰. 정면은 1024x1024, 측면은 512x512 PNG 다(§1-1). 학습은 둘을 섞어 쓰되 manifest 에 남겨
# 뷰별 성능을 따로 볼 수 있게 한다.
VIEW_TOKEN_TO_CODE = {
    "정면": "front",
    "측면": "side",
}

# AI허브 표준 접두어 → 분할. TS/TL 은 학습(원천/라벨), VS/VL 은 검증(원천/라벨).
# 검증 분할(VS/VL)이 그대로 공식 홀드아웃이 된다 — make_holdout.py 참고.
PREFIX_TO_SPLIT = {
    "TS": "train",
    "TL": "train",
    "VS": "val",
    "VL": "val",
}

# 라벨 JSON 의 diagnosis_name → 코드. 폴더 토큰과 같은 표다(값이 같다는 것을 실데이터로 확인했다).
# 따로 이름을 둔 이유: 나중에 JSON 쪽 표기만 바뀌어도(예: '지루피부염') 폴더 표는 건드리지 않고 여기만 늘리면 된다.
DIAGNOSIS_NAME_TO_CODE = dict(FOLDER_TOKEN_TO_CODE)
