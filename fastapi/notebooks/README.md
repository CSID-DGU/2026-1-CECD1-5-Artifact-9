# 학습 노트북

## pad_ham_training.ipynb

**지금 배포돼 있는 `fastapi/model.pth` 를 만든 노트북이다.** 모델을 다시 학습하려면
반드시 이 노트북에서 시작한다.

HAM10000(더모스코피) · PAD-UFES-20(스마트폰) · SCIN(일반인 제출) 세 데이터셋을 합쳐
8클래스 EfficientNet-B0 을 학습한다.

### 학습 전 준비 — Drive 작업폴더 채우기

`DRIVE` 상수(2번 셀)가 가리키는 Drive 폴더에 아래 4개가 있어야 한다.

```
MyDrive/artifact_medical_ai/
├── holdout.csv              ← ⚠️ 가장 자주 빠뜨리는 것. 아래 설명
├── pad-ufes-20-small.zip
├── scin-small.zip
└── model.pth                (선택 — 있으면 백본을 물려받아 시작한다)
```

HAM10000 은 4번 셀이 Kaggle 에서 직접 받으므로 올리지 않아도 된다.
(Kaggle API 토큰은 준비해 둘 것 — kaggle.com/settings → API → Create New Token)

#### `holdout.csv` 올리는 법

**매번 새로 학습할 때마다 필요하다.** Colab 런타임은 초기화돼도 Drive 는 남으므로,
한 번 올려두면 그 폴더를 지우기 전까지는 계속 쓰인다. 하지만 Drive 를 정리했거나
다른 계정으로 옮겼다면 다시 올려야 한다.

1. 이 저장소에서 [`../tests/baselines/holdout.csv`](../tests/baselines/holdout.csv)
   를 내려받는다 (약 79KB). GitHub 웹에서 열고 **Download raw file** 을 누르면 된다.
2. Drive 의 `MyDrive/artifact_medical_ai/` 폴더에 그대로 올린다.
   **파일명을 바꾸지 않는다** — 노트북이 `holdout.csv` 라는 이름으로 찾는다.
3. 끝. 6번 셀이 알아서 읽는다.

**잊어버려도 사고가 나지는 않는다.** 6번 셀이 목록을 못 찾으면 `FileNotFoundError`
로 **멈추고** 위 절차를 그대로 출력한다. 예전처럼 조용히 새 분할을 만들어 학습을
끝까지 진행해 버리는 일은 없다 — 그게 제일 위험한 실패 방식이라 일부러 막았다
(`ALLOW_NEW_HOLDOUT = False`).

### 실행 순서

1. Colab 에서 열고 `런타임 > 런타임 유형 변경 > T4 GPU` 를 선택한다.
2. 위 준비물을 Drive 에 올린다.
3. 위에서부터 순서대로 실행한다.
4. 나온 `model_v4_best.pth` 를 `fastapi/model.pth` 로 넣는다.
5. 아래 "모델을 바꾼 뒤에 할 일" 로 새 모델을 검증한다.

### 홀드아웃 규칙 — 이게 이 노트북에서 제일 중요하다

**`holdout.csv` 에 적힌 사진은 절대 학습에 넣지 않는다.** 6번 셀이 그 목록을
읽어서 학습에서 빼는 일만 한다. 매번 새로 분할하지 않는다.

왜 이렇게 하냐면, 예전에는 6번 셀이 실행할 때마다 `StratifiedGroupKFold` 를 새로
돌렸고 **그 결과를 아무 데도 저장하지 않았다.** 학습이 끝나고 나면 "이 모델이 어떤
사진을 안 봤는지" 를 아무도 말할 수 없었다. 그러면 나중에 재는 정확도가 진짜 실력인지
외운 걸 다시 맞힌 건지 구분이 안 된다. 실제로 그 일이 일어나서 배포된 모델의 분할을
메타데이터만 가지고 사후에 복원해야 했고, 그 복원이 맞는지는 끝내 증명하지 못했다.
(전말: [`../tests/baselines/README.md`](../tests/baselines/README.md))

목록을 고정하면 얻는 게 하나 더 있다. 모델을 몇 번을 갈아끼워도 **같은 2,857장으로**
재게 되니, `tests/evaluate.py --compare` 로 옛 모델과 새 모델을 곧바로 비교할 수 있다.
분할이 매번 달라지면 그 비교는 성립하지 않는다.

#### 목록 자체를 다시 정해야 할 때

데이터셋을 새로 구성했다면(사진을 추가했다거나 클래스를 바꿨다거나) 기존 목록은
더 이상 맞지 않는다. 그때만 6번 셀의 `ALLOW_NEW_HOLDOUT` 를 `True` 로 바꾼다.
그러면 새 목록을 만들어 Drive 에 저장하고 "커밋하라" 고 출력한다.

그 경우 **반드시 `fastapi/tests/baselines/holdout.csv` 로 커밋해야 한다.** 안 하면
다음 학습에서 또 다른 분할이 나오고, 지금까지 쌓은 비교 기준이 전부 무효가 된다.
그리고 `baselines/` 의 기존 기준 결과들은 **다른 시험지로 잰 값**이 되므로 더 이상
`--compare` 대상이 아니다 — 새 목록으로 옛 모델을 다시 재서 기준을 새로 깔아야 한다.

### 클래스 목록은 `fastapi/main.py` 와 같아야 한다

5번 셀의 `CLASSES` 와 `fastapi/main.py` 의 `CLASSES` 는 **순서까지** 같아야 한다.
인덱스가 어긋나면 에러 없이 조용히 다른 병명이 나온다. 앞의 7개는 기존 배포 모델과
인덱스가 같아야 하므로 건드리지 않고, 새 클래스는 뒤에만 덧붙인다.

`tests/test_model_contract.py` 가 이 대조를 자동으로 한다 — 노트북을 고쳤으면 그걸
돌려서 확인한다.

### 모델을 바꾼 뒤에 할 일

```bash
cd fastapi
python3 tests/evaluate.py --holdout tests/baselines/holdout.csv \
    --compare tests/baselines/holdout-2026-08-30.json
```

`--compare` 가 이전 모델 대비 무엇이 좋아지고 무엇이 나빠졌는지 항목별로 보여준다.
전체 정확도가 올라도 `mel` 재현율이 떨어졌다면 그건 개선이 아니다.
자세한 사용법은 [`../tests/README.md`](../tests/README.md).

### 참고 — 지운 노트북

`skin_lesion_training_colab.ipynb` 는 삭제했다. 어떤 배포 모델도 그 노트북에서
나오지 않았는데 저장소에 있는 유일한 학습 노트북이라, "재학습하려면 이걸 쓰면 되겠다"
는 오해를 부르는 파일이었다. 필요하면 git 이력에서 꺼낼 수 있다:

```bash
git show b455632:fastapi/notebooks/skin_lesion_training_colab.ipynb
```
