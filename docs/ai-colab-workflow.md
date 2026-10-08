# Colab ↔ 서버 — 모델을 바꿔 끼우는 흐름

> 이 문서는 **"모델이 어떻게 서버에 실리는가"** 한 가지만 설명하는 개요다.
>
> - 노트북을 실제로 돌리는 절차 → [`fastapi/notebooks/README.md`](../fastapi/notebooks/README.md)
> - 새 모델이 기존보다 나은지 판정하는 절차 → [`fastapi/tests/README.md`](../fastapi/tests/README.md)
>
> 두 문서가 정본이다. 절차가 어긋나 보이면 그쪽을 따른다.

---

## 전체 그림

```
[Colab]  pad_ham_training.ipynb 로 학습
   │
   │  model.pth  +  label_map.json  다운로드
   ▼
[로컬]   fastapi/model.pth 교체
   │
   │  fastapi/main.py 의 CLASSES 순서와 맞는지 확인
   ▼
[평가]   tests/evaluate.py --compare 로 이전 모델과 대조
   │
   │  좋아졌을 때만 통과
   ▼
[배포]   docker compose up -d --build  (이미지에 model.pth 가 함께 들어간다)
```

---

## 1. 학습 — Colab

노트북은 `fastapi/notebooks/pad_ham_training.ipynb` 하나다. Google Drive 에 데이터셋을
올려두고 런타임을 GPU 로 바꾼 뒤 위에서부터 실행하면 된다.

산출물은 두 개다.

| 파일 | 쓰임 |
| --- | --- |
| `model.pth` | 학습된 가중치 |
| `label_map.json` | 인덱스 → 클래스 코드 매핑 (순서 확인용) |

---

## 2. 클래스 순서 — 여기서 가장 많이 틀린다

모델은 클래스를 **이름이 아니라 인덱스로** 내보낸다. 그래서 학습 때의 순서와
서버의 `CLASSES` 순서가 한 칸이라도 어긋나면, **오류 없이 조용히 틀린 병명이 나온다.**

```python
# fastapi/main.py
CLASSES = ["akiec", "bcc", "bkl", "df", "mel", "nv", "vasc", "inflammatory"]
```

`label_map.json` 의 순서가 이와 같은지 반드시 눈으로 확인한다.

```json
{
  "0": "akiec", "1": "bcc",  "2": "bkl",  "3": "df",
  "4": "mel",   "5": "nv",   "6": "vasc", "7": "inflammatory"
}
```

이 계약은 `fastapi/tests/test_model_contract.py` 가 강제한다. 바꿔 끼운 뒤 그 테스트를
돌리는 것이 가장 빠른 확인 방법이다.

> DB `disease` 테이블의 코드도 같은 8종이어야 한다. 백엔드는 FastAPI 가 돌려준
> `disease_code` 로 `disease` 를 찾아 한글명을 붙인다.

---

## 3. 교체

```bash
cp ~/Downloads/model.pth fastapi/model.pth
```

`model.pth` 는 **저장소에 커밋되어 있고**(약 16MB), Docker 이미지 빌드 시 그대로
복사되어 들어간다. 즉 배포는 "파일을 서버에 따로 올리는 일"이 아니라
**이미지를 다시 빌드하는 일**이다. 별도의 모델 스토리지나 다운로드 단계가 없다.

`/health` 가 돌려주는 `model_version` 은 `efficientnet_b0-{model.pth 의 sha256 앞 12자리}`
형태라, 가중치를 바꾸면 자동으로 값이 달라진다. **배포 후 이 값이 바뀌었는지 보는 것이
"새 모델이 실제로 올라갔는가"를 확인하는 가장 확실한 방법이다.**

---

## 4. 좋아졌는지 확인 — 건너뛰지 않는다

체감이나 몇 장 찍어보는 것으로 판단하지 않는다. **고정된 홀드아웃 2,857장**에 대고
이전 모델과 같은 조건으로 비교한다.

```bash
cd fastapi
python tests/evaluate.py --compare tests/baselines/holdout-2026-08-30.json
```

홀드아웃은 `tests/baselines/holdout.csv` 에 파일 목록으로 고정되어 있고,
`ALLOW_NEW_HOLDOUT=False` 가드가 실수로 새로 뽑는 것을 막는다. **매번 다른 표본으로
재면 숫자가 좋아진 건지 표본이 쉬워진 건지 구분할 수 없기 때문이다.**

무엇을 어떤 기준으로 보는지 — 악성 리콜, 미응답률, OOD 반응 — 는 전부
[`fastapi/tests/README.md`](../fastapi/tests/README.md) 에 있다.

---

## 5. 배포

```bash
docker compose up -d --build fastapi
```

확인:

```bash
curl -s http://localhost:8000/health
# device / model_version / classes / LOW_CONFIDENCE_THRESHOLD 가 보인다
```

`model_version` 이 교체 전 값과 같다면 이미지가 다시 빌드되지 않은 것이다.

---

## 자주 겪는 문제

| 증상 | 원인 |
| --- | --- |
| 오류 없이 병명이 계속 엉뚱함 | 클래스 **순서** 불일치. `label_map.json` ↔ `CLASSES` 확인 |
| FastAPI 기동 실패 | `fastapi/model.pth` 누락, 또는 구조가 다른 가중치 |
| `model_version` 이 그대로 | 이미지 재빌드가 안 됨 (`--build` 누락) |
| 백엔드에서 병명이 비어 나옴 | DB `disease` 에 해당 코드가 없음 |
| 히트맵만 안 나옴 | FastAPI 로그의 `[GradCAM] 히트맵 생성 실패` 확인 — 분석 자체는 정상 |
