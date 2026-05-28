# AI 학습과 서버 연동 흐름

Colab은 모델을 학습하는 곳이고, `fastapi`는 학습된 모델로 추론 API를 제공하는 서버다.

## 1. Colab에서 학습

1. 이미지 데이터셋을 Google Drive 또는 Colab 런타임에 준비한다.
2. 학습 코드에서 라벨 순서를 고정한다.
3. 학습 완료 후 모델과 라벨맵을 저장한다.

권장 산출물:

```text
model.pth
label_map.json
preprocess_config.json
```

`label_map.json` 예시:

```json
{
  "0": "nv",
  "1": "mel",
  "2": "bkl",
  "3": "bcc",
  "4": "akiec",
  "5": "df",
  "6": "vasc"
}
```

라벨 값은 백엔드 DB의 `disease.disease_code`와 같아야 한다.

## 2. 모델 파일 배치

학습 완료 후 모델 파일을 다음 위치에 둔다.

```text
fastapi/
├── model.pth
├── main.py
└── requirements.txt
```

현재 팀원 구현은 `fastapi/main.py`가 `fastapi/model.pth`를 로드한다.
모델 파일은 용량이 크므로 일반적으로 Git에 커밋하지 않고, 팀 공유는 Google Drive, S3, GitHub Release 등을 사용한다.

## 3. AI 서버 연동

`fastapi`는 Spring Boot 백엔드가 호출하는 FastAPI 서버다.

```text
Frontend
→ Spring Boot Backend
→ FastAPI /predict-base64
→ 모델 추론
→ Backend DB analysis_result 저장
→ Frontend 표시
```

AI 서버 응답 형식:

```json
{
  "top1": {
    "rank": 1,
    "disease_code": "mel",
    "disease_name_ko": "악성 흑색종",
    "confidence": 0.72
  },
  "top5": []
}
```

## 4. 상병코드/처방코드와의 관계

멘토 제공 엑셀은 AI 학습 데이터가 아니라 기준 코드 데이터다.

- `kcd_disease`: 표준 상병코드 검색/선택용
- `drug_master`: 처방 검색/자동완성용

AI가 `mel`을 예측하면 백엔드는 `disease` 테이블에서 `mel`을 찾아 `analysis_result`에 저장한다.

## 5. 멘토 제공 엑셀 배치

팀원 구현은 Spring Boot 시작 시 아래 경로의 엑셀을 자동 적재한다.

```text
backend/src/main/resources/data/kcd_disease.xlsx
backend/src/main/resources/data/drug_master.xlsx
```

멘토님 파일을 다음 이름으로 복사한다.

```text
상병코드.xlsx → kcd_disease.xlsx
처방코드.xlsx → drug_master.xlsx
```

검색 API:

```text
GET /api/v1/kcd-diseases?query=콜레라
GET /api/v1/drugs?query=아스피린
```
