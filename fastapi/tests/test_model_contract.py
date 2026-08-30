"""
이미지 한 장 없이 확인할 수 있는 것들 — 클래스 목록의 정합성.

왜 필요한가
----------
이 프로젝트에서 병명 코드 목록은 최소 다섯 군데에 흩어져 있다.

    fastapi/main.py                CLASSES, CLASS_NAMES_KO   ← 모델 출력 인덱스의 의미
    fastapi/notebooks/*.ipynb      label_names               ← 학습할 때의 인덱스
    backend/.../V1__baseline_schema.sql   disease 시드
    backend/.../AnalysisService.java      DISEASE_NAME_KO
    frontend/src/pages/Clinic.tsx         KCD 매핑

언어가 달라 한 곳으로 모을 수가 없다. 그런데 이 목록이 어긋났을 때의 증상이 최악이다 —
**서버는 정상적으로 뜨고, 확률도 그럴듯하게 나오고, 병명만 조용히 틀린다.** 흑색종을
모반이라고 말하는 화면과 제대로 동작하는 화면이 겉보기에 완전히 같다.

실제로 커밋돼 있던 노트북이 `nv, mel, bkl, bcc, akiec, df, vasc` (7개, 다른 순서)로
학습하게 되어 있었다. notebooks/README.md 가 "순서가 어긋나면 조용히 다른 병명이 나온다"고
경고해 둔 바로 그 상태였다. 사람이 지키기로 한 규칙은 언젠가 안 지켜진다.

실행
----
    docker compose exec -T fastapi python tests/test_model_contract.py
    (CI 에서는 .github/workflows/ci.yml 의 fastapi 잡이 매번 돌린다)
"""

import ast
import contextlib
import csv
import importlib.util
import inspect
import io
import json
import os
import re
import sys
import tempfile
import textwrap
from pathlib import Path

FASTAPI_DIR = Path(__file__).resolve().parent.parent
os.chdir(FASTAPI_DIR)
sys.path.insert(0, str(FASTAPI_DIR))

# evaluate.py 와 같은 이유 — 이 스크립트는 포트를 열지 않는다.
os.environ.setdefault("INTERNAL_API_SECRET", "test_model_contract.py-does-not-serve")

import main as serving  # noqa: E402
from evaluate import SEVERITY  # noqa: E402

# 저장소 루트. 컨테이너 안에는 fastapi/ 만 복사돼 있어 백엔드 파일이 없다 —
# 그 경우 백엔드 대조는 건너뛴다(러너/로컬 체크아웃에서는 돈다).
REPO_ROOT = FASTAPI_DIR.parent
SCHEMA_SQL = REPO_ROOT / "backend/src/main/resources/db/migration/V1__baseline_schema.sql"
# 노트북을 이름으로 하나 집지 않고 폴더 전체를 훑는다. 학습 노트북이 늘어날 때
# (실제로 pad_ham_training.ipynb 가 나중에 추가됐다) 테스트가 조용히 옛 파일만
# 보고 있는 상황을 막는다.
NOTEBOOK_DIR = FASTAPI_DIR / "notebooks"

failures: list[str] = []
skipped: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    if ok:
        print(f"  ✅ {name}")
    else:
        print(f"  ❌ {name}")
        if detail:
            print(f"       {detail}")
        failures.append(name)


def skip(name: str, why: str) -> None:
    print(f"  ⏭  {name} — {why}")
    skipped.append(name)


def response_keys(func) -> set[str]:
    """함수 안에 있는 dict 리터럴의 문자열 키를 전부 모은다.

    소스를 문자열로 훑지 않는 이유가 있다. 처음엔 `"is_valid" in getsource(...)` 로
    썼는데, 정작 그 필드를 **없앴다고 설명하는 주석**이 함수 안에 있어서 검사가
    자기 주석을 보고 실패했다. 검사가 봐야 하는 것은 문서가 아니라 응답의 모양이다.
    AST 로 키만 뽑으면 주석·문서화가 뭐라고 쓰여 있든 영향을 받지 않는다.
    """
    tree = ast.parse(textwrap.dedent(inspect.getsource(func)))
    return {
        key.value
        for node in ast.walk(tree)
        if isinstance(node, ast.Dict)
        for key in node.keys
        if isinstance(key, ast.Constant) and isinstance(key.value, str)
    }


# =============================================
print("\n[1] main.py 내부 정합성")

classes = serving.CLASSES
check("CLASSES 에 중복이 없다",
      len(classes) == len(set(classes)),
      f"중복: {[c for c in set(classes) if classes.count(c) > 1]}")

check("CLASS_NAMES_KO 가 CLASSES 와 정확히 같은 집합이다",
      set(classes) == set(serving.CLASS_NAMES_KO),
      f"CLASSES 에만: {set(classes) - set(serving.CLASS_NAMES_KO)} / "
      f"이름표에만: {set(serving.CLASS_NAMES_KO) - set(classes)}")

# 이것이 이 파일의 핵심이다. 모델의 출력 개수와 CLASSES 길이가 어긋나면
# CLASSES[idx] 가 엉뚱한 병명을 집거나 IndexError 로 터진다.
out_features = serving.model.get_classifier().out_features
check(f"모델 출력 차원({out_features}) == len(CLASSES)({len(classes)})",
      out_features == len(classes),
      "model.pth 가 다른 클래스 수로 학습됐습니다. "
      "가중치를 다시 만들거나 CLASSES 를 맞추세요.")

check("LOW_CONFIDENCE_THRESHOLD 가 0~1 범위다",
      0.0 < serving.LOW_CONFIDENCE_THRESHOLD < 1.0,
      f"현재 값: {serving.LOW_CONFIDENCE_THRESHOLD}")

# 신뢰도로 결과를 **막지 않는다**는 것이 2026-08-30 의 설계 결정이다(main.py 주석 참고).
# 차단이 되살아나면 홀드아웃에서 흑색종 재현율이 89.3% → 86.3% 로 떨어지므로,
# is_valid 가 다시 생기는 것을 여기서 막는다.
inference_keys = response_keys(serving.run_inference)

check("run_inference 응답에 차단 필드(is_valid)가 없다",
      "is_valid" not in inference_keys,
      "신뢰도 차단이 되살아났습니다. 경고(confidence_level)로 대체되어야 합니다.")

# 위가 "없어야 할 것"이라면 이쪽은 "있어야 할 것"이다. 백엔드가 이 키를 읽어
# analysis_result.confidence_level 에 넣으므로, 사라지면 경고가 조용히 꺼진다.
check("run_inference 응답에 confidence_level 이 있다",
      "confidence_level" in inference_keys,
      f"현재 키: {sorted(inference_keys)}")

check("등급 문자열이 백엔드/마이그레이션과 약속한 값이다",
      (serving.CONFIDENCE_LOW, serving.CONFIDENCE_NORMAL) == ("low", "normal"),
      f"현재 값: {serving.CONFIDENCE_LOW!r} / {serving.CONFIDENCE_NORMAL!r} — "
      "V6 마이그레이션의 confidence_level CHECK 제약과 어긋납니다.")

# =============================================
print("\n[2] 평가 하네스와의 정합성")

check("evaluate.py SEVERITY 의 키가 CLASSES 와 같다",
      set(SEVERITY) == set(classes),
      f"SEVERITY 에 없음: {set(classes) - set(SEVERITY)} / "
      f"CLASSES 에 없음: {set(SEVERITY) - set(classes)}")

# =============================================
print("\n[3] 학습 노트북")

notebooks = sorted(NOTEBOOK_DIR.glob("*.ipynb")) if NOTEBOOK_DIR.is_dir() else []

if not notebooks:
    skip("노트북 클래스 목록 대조", f"노트북 없음: {NOTEBOOK_DIR}")


def _python_only(source: str) -> str:
    """Colab 매직(!apt-get, %cd)은 파이썬 문법이 아니라 파싱 전에 지운다."""
    return "\n".join("" if ln.lstrip().startswith(("!", "%")) else ln
                      for ln in source.splitlines())


for notebook in notebooks:
    nb = json.loads(notebook.read_text(encoding="utf-8"))
    name = notebook.name

    # 개행이 리터럴 "\n" 문자로 저장되면 셀 전체가 주석 한 줄이 되어 **조용히 아무것도
    # 실행되지 않는다**. 실제로 skin_lesion_training_colab.ipynb 의 코드 셀 12개 중
    # 10개가 그 상태였다. ast.parse 는 통과하므로(주석은 올바른 파이썬이다)
    # 실행문 개수로 판별한다.
    inert = []
    for pos, cell in enumerate(nb["cells"]):
        if cell["cell_type"] != "code":
            continue
        stripped = _python_only("".join(cell["source"]))
        if not stripped.strip():
            continue
        try:
            body_nodes = ast.parse(stripped).body
        except SyntaxError as exc:
            inert.append(f"cell {pos}: SyntaxError {exc.msg}")
            continue
        if not body_nodes:
            inert.append(f"cell {pos}: 실행문이 없음(전부 주석)")
    check(f"{name}: 모든 코드 셀에 실행되는 문장이 있다",
          not inert,
          "; ".join(inert))

    # 클래스 목록을 담는 변수명은 노트북마다 다르다(label_names / CLASSES).
    # 이름이 무엇이든 리스트 리터럴이면 대조 대상이다.
    found = {}
    for cell in nb["cells"]:
        if cell["cell_type"] != "code":
            continue
        try:
            tree = ast.parse(_python_only("".join(cell["source"])))
        except SyntaxError:
            continue
        for node in ast.walk(tree):
            if not isinstance(node, ast.Assign) or not isinstance(node.value, ast.List):
                continue
            for target in node.targets:
                if getattr(target, "id", None) not in ("label_names", "CLASSES"):
                    continue
                try:
                    found[target.id] = ast.literal_eval(node.value)
                except ValueError:
                    pass

    if not found:
        check(f"{name}: 클래스 목록 리터럴을 찾았다", False,
              "label_names / CLASSES 중 하나가 리스트 리터럴로 있어야 합니다. "
              "노트북 구조가 바뀌었다면 이 테스트도 함께 고쳐야 합니다.")
    else:
        for var, value in sorted(found.items()):
            check(f"{name}: {var} 가 CLASSES 와 순서까지 같다",
                  list(value) == list(classes),
                  f"노트북: {list(value)}\n       main.py: {list(classes)}")

# =============================================
print("\n[4] 홀드아웃 목록")

# 이 목록이 이 저장소의 유일한 "공식 시험지"다. 모델을 갈아끼워도 같은 2,857장으로
# 재기 때문에 --compare 비교가 성립한다. 목록이 조용히 바뀌거나, 노트북이 다시
# 매번 새로 분할하게 되돌아가면 그 비교 기준이 통째로 무효가 된다 — 여기서 잡는다.
HOLDOUT_CSV = FASTAPI_DIR / "tests/baselines/holdout.csv"

if not HOLDOUT_CSV.exists():
    check("홀드아웃 목록이 있다", False, f"없음: {HOLDOUT_CSV}")
else:
    with HOLDOUT_CSV.open(encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh))

    check("홀드아웃 목록에 image_id·label 열이 있다",
          bool(rows) and {"image_id", "label"} <= set(rows[0]),
          f"열: {list(rows[0]) if rows else '(빈 파일)'}")

    ids = [r["image_id"] for r in rows]
    dups = {i for i in ids if ids.count(i) > 1} if len(ids) != len(set(ids)) else set()
    check("홀드아웃에 같은 사진이 두 번 들어있지 않다",
          not dups,
          f"중복 {len(dups)}건: {sorted(dups)[:5]}")

    unknown = {r["label"] for r in rows} - set(classes)
    check("홀드아웃의 라벨이 전부 CLASSES 안에 있다",
          not unknown,
          f"모르는 라벨: {sorted(unknown)}")

    # 기록해 둔 기준 결과와 장수가 맞아야 --compare 가 같은 시험지 비교가 된다.
    for baseline in sorted((FASTAPI_DIR / "tests/baselines").glob("holdout-*.json")):
        recorded = json.loads(baseline.read_text(encoding="utf-8")).get("sample_count")
        check(f"{baseline.name} 의 장수가 목록과 같다",
              recorded == len(rows),
              f"기록 {recorded}장 / 목록 {len(rows)}장 — "
              "목록을 고쳤다면 그 기준 결과는 더 이상 비교 대상이 아닙니다")

# 노트북이 다시 '매번 새로 분할' 로 돌아가지 않았는지 본다.
# 분할을 하는 노트북은 그 결과를 반드시 파일로 남겨야 한다 — 남기지 않은 분할은
# 나중에 아무도 재현할 수 없고, 그 모델의 평가 숫자는 근거를 잃는다.
for notebook in notebooks:
    source = "".join("".join(c["source"]) for c in
                     json.loads(notebook.read_text(encoding="utf-8"))["cells"])
    if "StratifiedGroupKFold" not in source:
        continue
    check(f"{notebook.name}: 분할 결과를 holdout.csv 로 남긴다",
          "holdout.csv" in source,
          "StratifiedGroupKFold 로 나누기만 하고 목록을 저장하지 않습니다. "
          "이대로 학습하면 '이 모델이 안 본 사진'을 아무도 말할 수 없게 됩니다")

# =============================================
print("\n[5] OOD 평가 세트")

# OOD 세트의 이미지는 커밋하지 않는다 — make_ood.py 가 결정적이라 언제든 다시 만들 수
# 있기 때문이다. 그 전제가 깨지는 두 가지를 여기서 잡는다.
MAKE_OOD = FASTAPI_DIR / "tests/make_ood.py"
ood_baselines = sorted((FASTAPI_DIR / "tests/baselines").glob("ood-*.json"))

if not MAKE_OOD.exists():
    if ood_baselines:
        check("OOD 생성기가 있다", False,
              f"{[b.name for b in ood_baselines]} 는 있는데 make_ood.py 가 없습니다 — "
              "그 기준값은 다시 만들 수 없습니다")
    else:
        skip("OOD 평가 세트", "make_ood.py 도 기준값도 없음")
else:
    ood_source = MAKE_OOD.read_text(encoding="utf-8")

    # normal_skin 은 '모델이 본 적 없는 정상 피부'여야 의미가 있다. 홀드아웃 목록에서
    # 뽑는 것이 그 보장이다 — 학습 사진의 모서리를 쓰면 재는 대상이 달라진다.
    check("make_ood.py 가 정상 피부를 홀드아웃 목록에서 뽑는다",
          "holdout.csv" in ood_source,
          "PAD 전체에서 뽑으면 학습에 쓴 피부가 섞여 '처음 보는 정상 피부'가 아니게 됩니다")

    # 기준값에 기록된 갈래를 생성기가 여전히 만들 수 있어야 --compare 가 성립한다.
    #
    # 소스에서 갈래 이름을 문자열로 찾는 방식은 쓰지 않는다. 실제로 그렇게 짰다가
    # surface -> texture 로 바꾼 것을 놓쳤다 — 파일을 쓰는 쪽 이름은 바뀌었는데
    # `args.out / "surface"` 라는 경로 리터럴이 소스에 남아 검사를 통과시켰다.
    # 그래서 여기서는 생성기를 **실제로 돌린다**. 합성 갈래 152장에 1초면 되고,
    # 덤으로 생성기가 아예 터지지는 않는지도 같이 잡힌다.
    def run_generator() -> tuple[set[str], str | None]:
        """합성 갈래 + 가짜 PAD 로 normal_skin 까지 만들어 본다. (만든 갈래, 오류)"""
        import numpy
        from PIL import Image

        spec = importlib.util.spec_from_file_location("make_ood", MAKE_OOD)
        make_ood = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(make_ood)

        # 생성기가 찍는 진행 출력은 이 보고서에 섞이면 읽기만 나빠진다.
        with tempfile.TemporaryDirectory() as tmp_name, \
                contextlib.redirect_stdout(io.StringIO()):
            tmp = Path(tmp_name)
            rng = numpy.random.default_rng(0)
            rows = []
            for builder in (make_ood.build_degenerate, make_ood.build_pattern,
                            make_ood.build_surface):
                rows += builder(tmp / builder.__name__.removeprefix("build_"), rng)

            # normal_skin 은 PAD 원본이 있어야 하니, 홀드아웃 목록의 첫 몇 장 이름으로
            # 가짜 피부색 사진을 만들어 넣는다. _corner_score 의 세 관문까지 함께 돈다.
            if HOLDOUT_CSV.exists():
                with HOLDOUT_CSV.open(encoding="utf-8", newline="") as fh:
                    pad_ids = [r["image_id"] for r in csv.DictReader(fh)
                               if r.get("src") == "pad"][:3]
                if pad_ids:
                    fake = tmp / "pad"
                    fake.mkdir()
                    for image_id in pad_ids:
                        arr = (numpy.array([198.0, 152.0, 128.0])
                               + rng.normal(0, 3, (256, 256, 3))).clip(0, 255)
                        Image.fromarray(arr.astype("uint8")).save(fake / image_id)
                    made = make_ood.build_normal_skin(fake, HOLDOUT_CSV,
                                                      tmp / "normal_skin")
                    if len(made) != len(pad_ids):
                        return set(), ("깨끗한 피부색 사진마저 정상 피부로 안 뽑힙니다 "
                                       f"({len(made)}/{len(pad_ids)}) — 선별 기준이 "
                                       "너무 조입니다")
                    rows += made

            produced = {r["category"] for r in rows}
            absent = [r["file"] for r in rows if not (tmp / r["file"]).is_file()]
            if absent:
                return produced, f"목록에 있는데 실제로 없는 파일: {absent[:3]}"
            return produced, None

    try:
        import numpy  # noqa: F401
        from PIL import Image  # noqa: F401
    except ImportError:
        skip("make_ood.py 실행", "numpy/Pillow 가 없습니다 (컨테이너 안에서는 돕니다)")
        skip("기준값의 갈래 대조", "생성기를 돌리지 못했습니다")
    else:
        try:
            produced, build_error = run_generator()
        except Exception as exc:            # noqa: BLE001 — 무엇이든 실패로 본다
            produced, build_error = set(), f"{type(exc).__name__}: {exc}"

        check("make_ood.py 를 실제로 돌려 이미지가 나온다",
              build_error is None, build_error or "")

        # 생성 자체가 실패했으면 갈래 대조는 의미가 없다. 그대로 돌리면 "이름을 바꿨다"는
        # 엉뚱한 진단이 함께 뜨고, 진짜 원인이 두 줄 중 어느 쪽인지 헷갈린다.
        if build_error is not None:
            for baseline in ood_baselines:
                skip(f"{baseline.name} 의 갈래 대조", "생성기가 실패했습니다")
            ood_baselines = []

        # photo 는 로컬 파일이 있어야만 나오는 갈래라 여기서는 만들 수 없다. 애초에
        # 커밋된 기준값에는 넣지 않기로 했으니, 기준값에 있으면 그것이 잘못이다.
        for baseline in ood_baselines:
            recorded = json.loads(baseline.read_text(encoding="utf-8"))
            missing = set(recorded.get("ood", {}).get("per_category", {})) - produced
            check(f"{baseline.name} 의 갈래를 make_ood.py 가 아직 만든다",
                  not missing,
                  f"생성기가 만들지 못하는 갈래: {sorted(missing)} — 이름을 바꿨거나, "
                  "재현되지 않는 갈래(photo 등)가 기준값에 섞여 있습니다")

# =============================================
print("\n[6] 백엔드 disease 시드")

if not SCHEMA_SQL.is_file():
    skip("V1__baseline_schema.sql 대조",
         "컨테이너 안에는 백엔드 소스가 없습니다 (체크아웃에서 실행하면 돕니다)")
else:
    sql = SCHEMA_SQL.read_text(encoding="utf-8")
    block = re.search(
        r"INSERT\s+INTO\s+disease\s*\([^)]*\)\s*VALUES(.*?);", sql,
        re.IGNORECASE | re.DOTALL)
    if block is None:
        check("disease INSERT 문을 찾았다", False, "마이그레이션 구조가 바뀌었습니다.")
    else:
        rows = re.findall(r"\(\s*'([^']+)'\s*,\s*'([^']*)'", block.group(1))
        seeded = {code: name_ko for code, name_ko in rows}
        check("disease 시드의 코드 집합이 CLASSES 와 같다",
              set(seeded) == set(classes),
              f"DB 에만: {set(seeded) - set(classes)} / 모델에만: {set(classes) - set(seeded)}")

        mismatched = {
            code: (serving.CLASS_NAMES_KO[code], seeded[code])
            for code in set(seeded) & set(classes)
            if serving.CLASS_NAMES_KO[code] != seeded[code]
        }
        check("한글 병명이 main.py 와 DB 시드에서 같다",
              not mismatched,
              "; ".join(f"{c}: main='{a}' vs db='{b}'"
                        for c, (a, b) in mismatched.items()))

# =============================================
print()
if failures:
    print(f"❌ 실패 {len(failures)}건: {', '.join(failures)}")
    sys.exit(1)
print(f"✅ 모두 통과 (건너뜀 {len(skipped)}건)")
