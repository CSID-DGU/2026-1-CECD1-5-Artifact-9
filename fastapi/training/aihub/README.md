# fastapi/training/aihub — AI허브 데이터로 태스크별 모델 만들기

이 폴더는 **서빙 코드(`fastapi/main.py`)와 같은 클래스 순서·같은 전처리·같은 구현(timm EfficientNet-B0)** 으로
가중치를 만들기 위한 도구다. 노트북(`fastapi/notebooks/`)이 기본 태스크(`skin_lesion`)를 만든 방식의 규율을
그대로 따르되, 어느 서버에서나 돌도록 순수 파이썬 스크립트로 되어 있다.

| 파일 | 무엇 | 언제 |
|---|---|---|
| `inspect_dataset.py` | zip 을 풀지 않고 폴더·파일명·해상도·라벨 JSON 키·클래스 분포를 보고서로 뽑는다 (표준 라이브러리만) | 데이터를 **처음 받았을 때**, 안심존처럼 밖에서 못 보는 데이터일 때 |
| `inspect_mini.py` | 위의 30줄 축약판. 파일 반입도 클립보드도 막힌 안심존 Jupyter 셀에 **손으로 쳐 넣어** 폴더별 파일 수와 JSON 키 구조만 본다 | 안심존 열람환경에서 저장소를 반입할 수 없을 때 |
| `facial/classes.py` | `facial` 태스크의 `TASK`·`CLASSES` 리터럴과 폴더 토큰 표. `main.py` 와 순서까지 같아야 하며 계약 테스트가 대조한다 | 클래스를 바꿀 때만 (뒤에만 덧붙인다) |
| `facial/build_manifest.py` | AI허브 zip/폴더 → `manifest.csv` (어떤 파일이 어떤 클래스·뷰·분할인가 + 메타 열) | 학습 전 1회 |
| `facial/make_holdout.py` | manifest 의 Validation 분할을 공식 홀드아웃 목록 `fastapi/tests/baselines/facial-holdout.csv` 로 고정 | 데이터셋을 새로 받았을 때 1회 |
| `train.py` | 태스크 무관 범용 학습기. manifest + `<task>/classes.py` 만 있으면 cuda/mps/cpu 어디서나 | 학습할 때 |

전체 설계와 데이터 사실은 [`docs/aihub-datasets-plan.md`](../../../docs/aihub-datasets-plan.md) (§1 데이터, §2 환경, §3 계약, §7 현황) 에 있다.

---

## 0. 환경 — 어디서 돌리나

| 환경 | 용도 | 비고 |
|---|---|---|
| **Colab(T4) / 맥북(Apple Silicon)** | 안면부 학습 — **지금 바로 가능한 경로** | 안면부는 공개 다운로드 데이터라 안심존이 필요 없다. Colab 은 `--device cuda`, 맥북은 `--device mps`(연산 미지원 오류 시 `PYTORCH_ENABLE_MPS_FALLBACK=1` 또는 `--device cpu`) |
| **AI허브 안심존 GPU(분석환경) 서버** (V100 32GB · 8vCPU · 90GB RAM · CUDA 12.2 · Python 3.11) | 안면부 재학습·비교, 화상 학습 | **별도 분석 이용 신청**(IRB 연구계획서·결과통지서 필수, 시작일은 신청 2주 후~, 30일 제공). 실사용 전 **5일 외부망 기간**에 `git clone`·`pip install timm`·데이터 업로드를 끝내고 마운트를 요청하면 외부망이 끊긴다. 도커 이미지 v7.1(PyTorch 2.2.1) 선택. 접속은 가이드의 "03 GPU Server 접속"(SSH 터널 → 원격 윈도우 → MobaXterm → 분석환경접속). 압축 해제 후 약 20~30GB |
| 안심존 **열람** CPU 서버 (8vCPU · 64GB, 지금 할당된 것) | 화상 데이터 스키마 점검·지표 산출 | 할당 즉시 마운트 + 외부망 차단, **파일 반출 불가**. 저장소를 넣으려면 "서버 초기화 요청 → 외부망 → `git clone`/pip → 데이터 마운트 요청"(약 1일 소요). 그게 어려우면 `inspect_mini.py` 를 손으로 친다. 결과는 화면 스크린샷 |

### 안심존 분석환경의 반출 규칙 (통합 매뉴얼 p36~38)

- 반출 파일은 Jupyter 의 `nasw/download/` 에 두고 포털에서 신청한다. **자원 종료일 기준 3영업일 전**까지. 심의 2~3영업일, 기간 내 최대 3회 승인, 승인 파일은 10일간 URL 로 다운로드.
- 가능: 소스코드, AI 모델, 원천데이터가 들어 있지 않은 성능지표 파일(csv·png·json 등).
- 불가: **zip·npz·npy·pkl**, 이미지·영상(모델이 만든 합성물 포함), 압축 파일, **외부에서 반입한 파일**. `.git`·`.ipynb_checkpoints` 는 지우고 신청.
- 그래서 `train.py` 는 `<task>.pth` 와 함께 `<task>.safetensors` 사본도 쓴다. `.pth` 는 내부적으로 zip+pickle 이라 심의자가 "pkl/압축"으로 볼 여지가 있다. safetensors 가 승인되면 맥북에서 `.pth` 로 되돌려 `fastapi/models/` 에 넣는다(README §1-5).

필요한 패키지는 `torch`, `torchvision`, `timm`, `Pillow`, `numpy` 뿐이다. scikit-learn 은 일부러 쓰지 않는다(혼동행렬·재현율은 numpy 로 센다).
들어가자마자 아래로 확인한다.

```bash
python3 -c "import torch, torchvision, timm, PIL, numpy; print(torch.__version__, torchvision.__version__, timm.__version__, 'cuda', torch.cuda.is_available())"
```

`timm` 이 없으면 `train.py` 가 안내 메시지와 함께 멈춘다. **torchvision 모델로 대체하지 않는다** — state_dict 키가 달라 `main.py` 가 읽지 못한다.
외부망이 막힌 곳은 다른 PC 에서 `pip download timm==1.0.11 --no-deps -d wheels/` 로 받은 wheel 을 반입해 `pip install --no-index --find-links wheels/ timm` 으로 넣는다.

---

## 1. 안면부 피부질환 (`facial`) — 순서대로

### 1-1. 데이터 점검 (처음 한 번)

```bash
DATA="/Users/<me>/Downloads/14.안면부 피부질환 이미지 합성데이터/3.개방데이터/1.데이터"   # 경로에 공백 → 항상 따옴표
python3 fastapi/training/aihub/inspect_dataset.py "$DATA" --out ~/facial_report.txt
```

보고서 머리 `[0] 실행 정보` 의 "찾은 파일" 이 0 이면 경로나 맥 폴더 권한 문제다(보고서에 원인이 찍힌다).
이미 확인된 구조는 `build_manifest.py` 독스트링과 계획서 §7-1 에 있다 — 48개 zip, 10,800장, 라벨 키 `diagnosis_name`.

### 1-2. manifest 만들기 (zip 을 풀면서)

```bash
python3 fastapi/training/aihub/facial/build_manifest.py "$DATA" \
    --extract-to ~/facial_data --out ~/facial_run/manifest.csv
```

- zip 은 `~/facial_data/<zip이름>/` 아래로 풀린다. 두 번째 실행부터는 이미 풀린 zip 을 건너뛴다(`--re-extract` 로 강제).
- 끝에 `label × split × view` 표와 **JSON diagnosis_name 교차검증 결과**가 찍힌다. 기대값: 클래스마다 train 1,600(정면 800+측면 800) / val 200, 총 10,800, `✅ 교차검증 통과`.
- `❌ 폴더 라벨 ≠ JSON diagnosis_name` 이 하나라도 나오면 종료코드 1 이다. 그대로 학습하지 말고 예시 파일을 열어 본다.

### 1-3. 홀드아웃 목록 고정 (데이터셋을 받았을 때 한 번)

```bash
python3 fastapi/training/aihub/facial/make_holdout.py --manifest ~/facial_run/manifest.csv
# → fastapi/tests/baselines/facial-holdout.csv (1,200행: image_id,label,view,src) — 커밋한다
```

AI허브가 준 Validation 분할을 그대로 쓴다. 사람이 시드나 비율을 고를 여지가 없고 누구나 같은 목록을 다시 만들 수 있다.
이미 파일이 있으면 `--force` 없이는 덮어쓰지 않는다 — 목록이 바뀌면 `baselines/facial-holdout-*.json` 과의 `--compare` 가 성립하지 않는다.

### 1-4. 학습

**가장 빠른 경로는 Colab 래퍼 노트북** [`fastapi/notebooks/facial_training_colab.ipynb`](../../notebooks/facial_training_colab.ipynb) 이다.
Drive 의 `MyDrive/artifact_medical_ai/facial/1.데이터/` 에 zip 48개를 올려 두고 위에서부터 실행하면 아래 1-2 ~ 1-4 가 그대로 돈다
(저장소 clone → manifest → 홀드아웃 → `train.py` → 산출물 Drive 저장). 맥북이나 GPU 서버에서는 같은 명령을 터미널에서 친다.


```bash
# GPU 환경 (V100)
python3 fastapi/training/aihub/train.py \
    --manifest ~/facial_run/manifest.csv --task facial --out-dir ~/facial_run \
    --holdout-csv fastapi/tests/baselines/facial-holdout.csv \
    --device cuda --init repo --epochs 15 --batch-size 64 --image-size 224 --num-workers 8

# 맥북 예비 (느리다. 1만 장·15 epoch 에 1~3시간대 추정)
python3 fastapi/training/aihub/train.py --manifest ~/facial_run/manifest.csv --task facial \
    --out-dir ~/facial_run --holdout-csv fastapi/tests/baselines/facial-holdout.csv \
    --device mps --init repo --epochs 15 --batch-size 32 --num-workers 4
```

- `--init repo`(기본)는 저장소의 `fastapi/model.pth`(피부 사진으로 이미 학습된 EfficientNet-B0)에서 **백본만** 물려받고 분류 헤드는 새로 만든다.
  저장소가 함께 올라가 있어야 한다(`git clone` 또는 zip). 인터넷이 되면 `--init imagenet` 으로 timm 사전학습을 쓸 수도 있다(실패 시 repo 로 폴백).
- `--holdout-csv` 의 image_id 는 **절대 학습에 넣지 않고** 검증 세트로 쓴다. 목록의 id 가 manifest 에 하나도 없으면 멈춘다(다른 태스크 목록을 잘못 준 경우).
- 검증 전처리는 서빙과 같다(Resize 224 → ImageNet 정규화). 학습 증강은 기본 태스크 노트북과 같은 계열(RandomResizedCrop 0.7~1.0, 뒤집기, ±15°, 약한 ColorJitter).
- 클래스 가중치는 역빈도에 상한 `--w-cap 5.0`. 이 데이터는 클래스가 균등(1,600장씩)이라 사실상 1.0 이다.
- 매 epoch 검증 macro recall 로 best 를 저장하고 `--patience 5` 로 조기 종료. 혼합정밀(`--amp auto`)은 cuda 에서만 켜진다.
- 시간 추정(검증 전 수치): V100 · 224 · batch 64 기준 epoch 당 수 분 → 15 epoch 수십 분. 실제 값은 리포트의 epoch 시간으로 확인한다.

산출물 (`--out-dir`):

| 파일 | 내용 |
|---|---|
| `facial.pth` | `timm.create_model('efficientnet_b0', num_classes=6)` 의 state_dict. `main.py` 가 그대로 `load_state_dict` 한다 |
| `facial-train-report.json` | 인자·환경·장수·epoch 곡선·best epoch·클래스별 recall/precision·혼동행렬·가중치 sha256 앞 12자리(= 서빙 `model_version`). 매 epoch 덮어쓴다 |
| `facial-val-predictions.csv` | image_id, label, pred, confidence (best 가중치로 검증 세트 재추론) |
| `facial.safetensors` | 같은 가중치의 safetensors 사본(`safetensors` 패키지가 있을 때만 — timm 이 의존하므로 보통 있다). 안심존 반출 심의에서 `.pth` 가 막힐 때의 대안 |
| `facial-val-split.csv` | val 이 없어 train 에서 떼어 냈을 때만. 이 데이터에서는 생기지 않는다 |

**반출/복사는 이 산출물만** 한다. 이미지는 옮기지 않는다. safetensors 만 반출됐다면 맥북에서 되돌린다:

```bash
python3 -c "from safetensors.torch import load_file; import torch; torch.save(load_file('facial.safetensors'), 'facial.pth')"
```

### 1-5. 서비스에 넣기

```bash
cp ~/facial_run/facial.pth fastapi/models/facial.pth                                  # .gitignore 예외 있음 → 커밋
cp ~/facial_run/facial-train-report.json fastapi/tests/baselines/facial-train-$(date +%F).json
cd fastapi && INTERNAL_API_SECRET=test python3 tests/test_model_contract.py            # facial 출력 차원 6 ✅ 가 되어야 한다
python3 tests/evaluate.py --task facial --image-dir ~/facial_data --csv tests/baselines/facial-holdout.csv --label-col label \
    --json tests/baselines/facial-holdout-$(date +%F).json                              # 홀드아웃 1,200장 재측정 — 학습 리포트와 맞아야 한다
```

그 다음 `docker compose up --build` 로 띄워 진료 화면에서 분석 모델을 "안면부 피부질환"으로 바꿔 분석이 되는지, `/health` 의 `tasks.facial.loaded` 가 `true` 인지 본다.
가중치 파일의 SHA256 앞 12자리가 서버가 말하는 `model_version` 이다: `sha256sum fastapi/models/facial.pth | cut -c1-12`.

### 1-6. 이 데이터의 한계 — 결과를 읽을 때

- **합성 이미지**다. 실제 환자 사진 분포와 다르므로 홀드아웃 점수는 "합성 분포 안에서의 성능" 이다. 실사진 몇 장으로라도 따로 확인하기 전까지는 참고용으로 표시한다.
- 중증도 라벨이 없다. 모델은 질환 종류만 낸다.
- 정면은 얼굴 전체(1024²)를 224 로 줄여 보므로 작은 병변이 뭉개질 수 있다. 측면(512²)은 상대적으로 유리하다. 리포트의 뷰별 recall 을 따로 본다(`facial-val-predictions.csv` 와 manifest 의 `view` 열을 조인).

---

## 2. 화상 (`burn`) — 안심존 안에서

아직 코드는 없다(§7-3 의 8번). 순서만 적어 둔다.

1. 안심존 리눅스 서버에서 `python3 -I inspect_dataset.py <마운트경로> --out ~/burn_report.txt` → 화면을 **스크린샷** (반출 불가).
   라벨 JSON 의 깊이 키(1도/표재성 2도/심재성 2도/3도 등)와 화상 이미지·임상정보 JSON 의 결합 구조를 확정한다.
2. `training/aihub/burn/classes.py` (예정 코드 `burn_1, burn_2s, burn_2d, burn_3` — 실제 라벨에 맞춰 확정) 와 `burn/build_manifest.py` 를 만든다. 이미지 안에 여러 화상 부위가 bbox 로 있으면 부위별 크롭을 샘플로 쓸지 결정한다.
3. 같은 `train.py --task burn` 으로 안에서 학습한다. GPU 가 없으면(열람환경 CPU 8코어) `--image-size 192 --batch-size 32 --epochs 8 --limit` 로 지표부터 낸다.
4. 반출 가능 여부(분석환경 전환·반출 심사)가 확정되면 가중치·리포트·홀드아웃 목록만 반출한다.
5. 서비스 등록: `main.py TASKS["burn"]`, V11 disease 시드, Java 맵, 프론트 KCD(T30.x), 그리고 `LowConfidenceCaution` 의 "악성·전암" 문구는 화상에 맞지 않으므로 태스크별 문구로 나눈다.

---

## 3. 규칙 요약

- 클래스 순서를 바꾸지 않는다. 새 클래스는 뒤에만 붙인다. `tests/test_model_contract.py` 가 `main.py`·`classes.py`·DB 시드·Java 맵을 대조한다.
- 홀드아웃 목록은 커밋하고 다시 고르지 않는다. 이미지는 커밋하지 않는다.
- 가중치는 `fastapi/models/<task>.pth` 에만 둔다(`fastapi/models/README.md`).
- 산출물·데이터·접속정보를 저장소에 넣지 않는다.
