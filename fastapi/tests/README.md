# fastapi/tests

| 파일 | 데이터 필요? | 무엇을 하나 |
|------|:---:|------|
| `test_model_contract.py` | 없음 | 병명 목록이 서빙·학습·DB 사이에서 어긋나지 않았는지 검사. CI 가 매번 돌린다. |
| `evaluate.py` | **필요** | 홀드아웃 이미지로 클래스별 정확도·재현율·혼동행렬·임계값 곡선을 낸다. |
| `test_concurrent_heatmap.py` | 없음 | 동시 요청에서 Grad-CAM 히트맵이 뒤섞이지 않는지 재현. 서버를 띄운 상태에서 실행. |
| `make_holdout.py` | **필요** | 공식 홀드아웃 목록(`baselines/holdout.csv`)을 만든 스크립트. 컨테이너 밖에서 실행. |
| `baselines/` | — | 지금까지 잰 결과. `--compare` 의 대상. |

---

## 왜 평가 하네스가 필요했나

이 저장소에는 "이 모델이 얼마나 맞히는가"를 답할 수단이 없었다. 그래서

- 모델을 갈아끼웠을 때 **나아졌는지 나빠졌는지 판단할 근거가 없었고**,
- `MIN_TOP1_CONFIDENCE` 기본값 `0.45` 가 왜 하필 0.45 인지 아무도 설명할 수 없었으며,
  (이 하네스로 실측한 뒤 0.35 로 내렸다)
- torch 버전을 올린 뒤 확률이 달라진 것을 눈으로만 확인했다.

모델을 손대기 전에 이것부터 있어야 한다. 기준선이 없으면 "바꿨다"는 있어도 "좋아졌다"는 없다.

## 홀드아웃 이미지를 저장소에 넣지 않는 이유

이미지 자체는 커밋하지 않는다. HAM10000 은 CC BY-NC 라 재배포 조건이 걸려 있고,
수천 장을 넣으면 git 저장소가 감당하지 못한다.

대신 **어떤 이미지를 평가에 썼는지 목록만** 텍스트로 고정한다(`holdout.csv`, 수십 KB).
목록이 고정돼 있으면 반년 뒤 다른 사람이 같은 수치를 다시 낼 수 있다.
이 저장소의 공식 목록은 `baselines/holdout.csv` 다.

> ⚠️ 학습에 쓴 이미지로 평가하면 안 된다. 점수는 올라가지만 그 점수는 거짓말이다.

### 목록은 학습과 평가가 공유한다

`baselines/holdout.csv` 는 **학습 노트북과 평가 스크립트가 같이 보는 한 장의 목록**이다.

- 학습(`notebooks/pad_ham_training.ipynb` 6번 셀): 이 목록의 사진을 학습에서 **뺀다**
- 평가(`evaluate.py`): 이 목록의 사진으로 점수를 **매긴다**

한 파일이 양쪽을 정의하니 둘이 어긋날 수가 없다. 예전처럼 노트북이 매번 새로 나누고
그 결과를 아무도 저장하지 않으면, 나중에 재는 점수가 진짜 실력인지 외운 걸 다시 맞힌
건지 구분할 방법이 사라진다. 실제로 그 일이 일어났다 — 전말은
[`baselines/README.md`](baselines/README.md).

> **그래서 이 목록을 함부로 고치지 말 것.** 고치는 순간 `baselines/` 의 기록들과
> 비교가 성립하지 않는다. `test_model_contract.py` 의 [4] 항목이 장수와 중복을
> 감시하고, 노트북이 다시 "매번 새로 분할" 로 되돌아가는 것도 여기서 막는다.

### 이 목록은 어떻게 나왔나 — `make_holdout.py`

현재 `fastapi/model.pth`(`efficientnet_b0-03061a31e63f`)를 학습할 때 쓴 분할 목록은
파일로 남지 않았다. 하지만 `notebooks/pad_ham_training.ipynb` 의 분할이
**시드가 고정된 결정적 연산**이라 절차를 그대로 재현하면 목록을 되살릴 수 있다.

```
StratifiedGroupKFold(n_splits=5, shuffle=True, random_state=42)
```

`make_holdout.py` 가 그 노트북의 5번·6번 셀을 그대로 옮긴 것이다. 세 데이터셋의
메타데이터만 있으면 홀드아웃 2,857장(전체 14,285장의 1/5) 목록이 나온다.

```bash
# pandas / scikit-learn 이 필요하다 — 추론 이미지에는 없으므로 컨테이너 밖에서 돌린다
python3 tests/make_holdout.py \
  --ham-dir  ~/Downloads/CapstoneDesign/archive \
  --pad-dir  ~/Downloads/pad-work/pad-ufes-20-small \
  --scin-dir ~/Downloads/scin-work/scin-small \
  --out /tmp/holdout.csv --verify
```

> 이 스크립트는 **목록을 처음 만들 때 한 번** 쓰는 것이다. 평소에는 다시 돌릴 일이
> 없다 — 돌리면 `baselines/holdout.csv` 와 다른 목록이 나올 수 있고, 그러면 지금까지의
> 기준 결과와 비교가 깨진다. 데이터셋을 새로 구성해 목록 자체를 다시 정할 때만 쓴다.

**복원이 맞았는지는 확정하지 못했다.** `--verify` 로 학습분(11,428장)도 같이 쟀는데
84.84% 대 83.86%, 격차가 표준오차 안이라 판정이 안 됐다. 다만 **모델이 자기가 학습한
사진에서도 84.84% 밖에 못 맞힌다**는 사실이 오염의 상한을 준다 — 외우지 않았으므로
오염이 있어도 점수를 1%p 이상 부풀리지 못한다. 자세한 논증은
[`baselines/README.md`](baselines/README.md).

**데이터셋별로 나눠 보기** — `holdout.csv` 의 `src` 열로 걸러 따로 재면
출처별 성능이 보인다. `pad`(스마트폰 촬영)가 실제 키오스크 환경에 가장 가깝고,
`ham`(더모스코피)보다 어렵다.

---

## 실행

컨테이너 안에서 돌린다. 전처리(Resize·Normalize)를 `main.py` 에서 그대로 가져다 쓰기 때문에
**실제로 서빙되는 것과 똑같은 조건**으로 측정된다. 값을 복사해 두면 언젠가 한쪽만 바뀌고,
그때부터 이 평가는 서빙되지 않는 다른 모델을 재게 된다.

### 계약 테스트 — 데이터 없이 지금 바로

```bash
docker compose exec -T fastapi python tests/test_model_contract.py
```

백엔드 마이그레이션까지 대조하려면 저장소를 통째로 붙여서 돌린다
(컨테이너 이미지에는 `fastapi/` 만 들어 있어 백엔드 소스가 없다):

```bash
docker run --rm -v "$PWD:/repo:ro" -w /repo/fastapi \
  -e INTERNAL_API_SECRET=contract-test \
  artifact-medical-ai-fastapi python tests/test_model_contract.py
```

### 평가 — 홀드아웃 이미지가 있을 때

호스트의 데이터 폴더를 `/data` 로 붙인다. `docker-compose.yml` 은 건드리지 않는다.

**(1) 이미지 폴더 + 목록 CSV** — 노트북이 만든 `holdout.csv` 를 쓰는 경우

```bash
docker compose run --rm -v /데이터경로:/data:ro fastapi \
  python tests/evaluate.py --image-dir /data/images --csv /data/holdout.csv
```

CSV 컬럼명 기본값은 HAM10000 기준(`image_id`, `dx`)이다. 다르면 `--id-col` / `--label-col`.
파일 확장자는 CSV 에 없어도 된다 — `.jpg`, `.png` 등을 알아서 찾는다.

`--image-dir` 는 폴더를 여러 개 받는다. HAM10000 배포본은 이미지가 두 폴더로 쪼개져 있는데
metadata.csv 는 하나라서 필요하다:

```bash
docker compose run --rm -v ~/Downloads/CapstoneDesign/archive:/data:ro fastapi \
  python tests/evaluate.py \
    --image-dir /data/HAM10000_images_part_1 /data/HAM10000_images_part_2 \
    --csv /data/HAM10000_metadata.csv --batch-size 64
```

> ⚠️ 위 명령은 **HAM10000 전체**를 재는 것이라 학습에 쓴 이미지가 섞여 있다.
> 나오는 수치는 진짜 성능이 아니라 낙관적인 상한선이다. 정식 측정은 `--csv` 에
> `baselines/holdout.csv` 를 주는 아래 방식으로 한다.

**(2) 클래스별 하위 폴더** — `/data/mel/*.jpg`, `/data/nv/*.jpg` …

```bash
docker compose run --rm -v /데이터경로:/data:ro fastapi \
  python tests/evaluate.py --image-dir /data
```

### 모델을 교체할 때 — 이게 원래 목적이다

```bash
# 1. 지금 모델의 성적을 남긴다
docker compose run --rm -v /데이터경로:/data fastapi \
  python tests/evaluate.py --image-dir /data --csv /data/holdout.csv \
  --json /data/before.json

# 2. fastapi/model.pth 를 새 가중치로 교체하고 다시 빌드
docker compose build fastapi && docker compose up -d fastapi

# 3. 같은 데이터로 다시 재고 비교
docker compose run --rm -v /데이터경로:/data fastapi \
  python tests/evaluate.py --image-dir /data --csv /data/holdout.csv \
  --compare /data/before.json
```

`model_version`(가중치 파일의 SHA256 앞 12자리)이 결과에 함께 찍히므로,
어떤 가중치의 성적인지 나중에도 헷갈리지 않는다.

지금까지 잰 값은 `baselines/` 에 있다. 현재 모델의 기준선은
`baselines/holdout-2026-08-30.json`(공식 홀드아웃 2,857장, 83.86%)이다.
`ham10000-full-2026-08-30.json` 은 학습 이미지가 섞인 옛 측정이라 참고용이다.
두 값의 관계와 신뢰 범위는 `baselines/README.md` 에 적어뒀다.

---

## 보고서 읽는 법

**혼동행렬** — 행이 실제 병명, 열이 모델의 답. 대각선 밖에 몰려 있는 큰 수가 그 모델의 약점이다.
`mel` 행의 `nv` 열이 크다면 흑색종을 점이라고 말하고 있다는 뜻이다.

**놓치면 안 되는 것** — 악성·전암(`mel`·`bcc`·`akiec`)을 양성으로 판정한 비율.
전체 정확도가 아무리 높아도 이 값이 크면 임상적으로는 쓸 수 없는 모델이다.
심각도 기준은 `backend/.../V1__baseline_schema.sql` 의 `disease` 시드를 따랐다.

**클래스별 표의 거절률** — 그 병의 실제 사진 중 임계값에 걸려 답을 못 받은 비율.
전체 거절률만 보면 "몇 장 중 한 장이 거절된다"까지밖에 모른다. 그게 어느 병에
몰려 있는지가 훨씬 중요하다 — 흑색종에서만 거절이 잦다면, 그 임계값은 정확도를
지키는 게 아니라 **가장 위험한 병에서만 입을 다무는** 장치라는 뜻이다.

**임계값 표** — `MIN_TOP1_CONFIDENCE` 를 0.05 간격으로 훑는다. 임계값을 올리면
틀린 답이 줄어드는 대신 화면에 "판단하기 어렵습니다"가 뜨는 비율(거절률)이 오른다.
그 교환비를 보고 현재 값을 유지할지 정한다. 기본값 0.45 는 근거 없이 정해진 값이었고,
이 표를 근거로 0.35 로 내렸다 (`main.py` 의 해당 상수 주석에 계산 근거가 있다).

이 표를 읽을 때 임계값의 **원래 목적**을 잊지 말아야 한다. 이건 정확도를 올리는
장치가 아니라 "피부 병변 사진이 아닌 것"을 걸러내는 장치다(거절 시 문구가
`INVALID_IMAGE_MESSAGE` — "피부 병변 이미지로 판단하기 어렵습니다"). 그러니
정상적인 병변 사진으로만 이뤄진 홀드아웃에서는 거절률이 0 에 가까워야 정상이고,
여기서 나오는 거절률은 전부 **오거절**이다. 정확도가 오르는 것은 그 대가로
얻은 것이지 목적이 아니다.

`--min-mel-recall 0.7` 처럼 하한을 주면 미달 시 종료코드 1 이라, 나중에 CI 게이트로 쓸 수 있다.

---

## 자주 겪는 것

**`INTERNAL_API_SECRET` 관련 에러** — `main.py` 는 이 값이 없으면 기동을 거부한다.
두 스크립트는 포트를 열지 않으므로 값이 없으면 대체값을 쓴다. `docker compose run` 으로
돌리면 compose 가 실제 값을 넣어주므로 신경 쓸 일이 없다.

**"평가할 이미지가 하나도 없습니다"** — CSV 의 라벨이 `CLASSES` 에 없거나(예: HAM10000 원본에
`inflammatory` 는 없다) 파일을 못 찾은 경우다. 실행 직후 뜨는 ⚠ 줄에 건수와 예시가 나온다.

**추론이 느림** — 배포 대상에 GPU 가 없어 CPU 로 돈다. `--batch-size` 를 올리면 빨라진다.
Grad-CAM 은 확률에 영향이 없어 평가에서는 아예 건너뛴다.
