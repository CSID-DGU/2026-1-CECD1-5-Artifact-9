# AI허브 데이터셋 도입 계획 — 안면부 피부질환(1순위) · 화상(2순위)

> 이 문서는 "어떤 데이터를, 어디서, 어떻게 학습해서, 서비스의 어느 층에 어떤 모양으로 붙이는가"를
> 한 곳에 못박아 두는 계약서다. 코드 세 층(FastAPI·Spring·React)이 병명 코드를 각자 들고 있는
> 구조라, 계약이 흐려지면 "서버는 멀쩡히 뜨고 병명만 조용히 틀리는" 사고가 난다
> (`fastapi/tests/test_model_contract.py` 머리말 참고).

## 0. 결론 먼저

| 순위 | 데이터셋 | 입수 경로 | 학습 장소 | 서비스 반영 |
|:---:|---|---|---|---|
| 1 | **안면부 피부질환 이미지 합성 데이터** (AI허브 71863) | 일반 다운로드 (INNORIX, 약 10GB) | 제공받은 GPU 클라우드(V100 32GB · 8vCPU · 90GB), 대안 Colab | 이번 PR 에서 끝까지 (`facial` 태스크) |
| 2 | **화상 이미지 및 임상 데이터** (AI허브 71398) | **안심존 온라인** — 반출 불가 | 안심존 서버 안에서만 | 라벨 스키마 확인 + 반출 경로 확정 후 후속 PR |
| — | 피부질환 병리검사 이미지 데이터 (AI허브 71519) | 안심존 온라인, WSI(svs/mrxs) | — | **범위 제외** (사용자 결정 2026-10-23) |

서비스 쪽 설계 원칙은 하나다. **기존 8클래스 모델(`fastapi/model.pth`)은 한 바이트도 건드리지 않는다.**
새 데이터는 별도 태스크(별도 가중치·별도 클래스 목록)로 **옆에 추가**하고, 요청에 `task` 를
안 적으면 지금과 100% 같은 동작을 한다. 그래야 "기존 서비스와 충돌 없음"이 검증 가능한 문장이 된다.

---

## 1. 데이터셋 사실 정리

### 1-1. 안면부 피부질환 이미지 합성 데이터 (71863) — 1순위

공개 페이지에서 확인한 사실(출처: [AI허브 71863](https://www.aihub.or.kr/aihubdata/data/view.do?aihubDataSe=data&dataSetSn=71863),
[공공데이터포털 등록 정보](https://www.data.go.kr/data/15150279/fileData.do)):

- 2024년 구축, 헬스케어 / 이미지 / **합성 데이터**. 내국인만 신청 가능. 국방데이터라 일부 제한 표기.
- 원천데이터 총 **1만 2천 장**. 질환별 **정면(1024×1024 PNG)** 과 **측면(512×512 PNG)** 으로 나뉘고 각 1천 장.
- 질환: 페이지 표에 **건선 · 아토피 · 여드름 · 주사 · 지루** 가 보이고, 다운로드 목록(사용자 화면)에
  `VL_여드름_정면.zip`, `VL_정상_정면.zip`, `VL_주사_정면.zip`, `VL_지루_정면.zip` … 처럼 **정상** 폴더가 있다.
  6종 × 2뷰 × 1,000장 = 12,000장으로 수량이 맞아떨어진다. → **6클래스(정상 포함)** 로 본다.
- 라벨: PNG(세그멘테이션 마스크) + JSON(바운딩박스, 임상정보 텍스트, 중증도).
- 파일명 접두어 규칙(AI허브 표준): `TS_` 학습 원천 / `TL_` 학습 라벨 / `VS_` 검증 원천 / `VL_` 검증 라벨.

**아직 모르는 것 — 라벨 JSON 의 정확한 키 이름.** 다운로드가 끝나면
`fastapi/training/aihub/inspect_dataset.py` 를 돌려 보고서를 공유한다(§4).
클래스는 폴더/zip 이름(`_여드름_`, `_정상_` …)에서 확정할 수 있어 JSON 키가 달라도 학습은 가능하다.

### 1-2. 화상 이미지 및 임상 데이터 (71398) — 2순위

출처: [AI허브 71398](https://aihub.or.kr/aihubdata/data/view.do?currMenu=115&topMenu=100&dataSetSn=71398)

- 2022년 구축, 2023-12 갱신. 주관 서울대학교 산학협력단(협력 한림대·순천향대·수원대). 구축량 23,010건.
- 구성: (1) 화상 이미지 JPG + 라벨 JSON(바운딩박스·세그멘테이션) (2) 영상검사 DICOM(라벨 없음)
  (3) 임상정보 JSON. **화상 이미지 라벨과 임상정보가 하나의 JSON** 에 들어 있다.
- 목적: 화상 진단 보조와 **화상 단계(깊이) 판정** AI. 화상 원인 항목(예: 화학 화상)이 있다.
- **온라인 안심존** 데이터다. 다운로드가 없고, 할당된 서버 안에서만 열람·학습한다.

### 1-3. 안심존 제약 — 이게 2순위가 된 이유

안심존 운영자 메일(2026-10-23)의 요지:

- 할당 자원은 **신청승인일로부터 7일** 이용 가능(할당 작업일 포함). 첫날 15:00 이전 할당 완료.
- 서버는 데이터 마운트 + **외부망 차단** 상태로 제공된다 → pip 설치, 사전학습 가중치 다운로드가 안 된다.
- **데이터 열람 이용자는 파일 반출이 불가**하다. 만료 후 서버는 회수·초기화된다(복구 불가).

즉 지금 할당된 것(CLOUD-042, 8core/64GB = 통합 매뉴얼 p61 의 **열람 CPU 서버** 사양)은 "열람환경"이라
**학습한 가중치 파일을 밖으로 가져올 공식 경로가 없다.** 통합 사용자 매뉴얼 v1.1 로 확인한 두 서비스의 차이:

| | 열람 (지금 할당) | 분석 (GPU V100) |
|---|---|---|
| 신청 서류 | 이용신청서·보안서약서 (IRB·연구계획서·소속증빙 **불필요**, p44) | **IRB 연구계획서 + 결과통지서**, 소속증빙, 이용신청서·보안서약서·GPU자원활용 동의서 (p10·15) |
| 리드타임 | 신청일로부터 3일 내 승인 | 시작일은 **신청일로부터 2주 후 ~ 한 달 내** 선택, 심의 지연 시 2주 이상 (p7·11) |
| 기간 | 7일 (연장 불가) | **30일**, 종료 후 30일간 재신청 불가 (p7) |
| 서버 | 8vCPU·64GB·SSD 50+500GB, GPU 없음 (p61) | 8vCPU·90GB·V100 32GB·CUDA 12.2·Python 3.11, 도커 이미지 버전 선택(v7.1: PyTorch 2.2.1) (p13·32) |
| 외부망 | 할당 즉시 마운트 + 차단. 설치가 필요하면 "서버 초기화 요청 → 외부망 → 설치 → 마운트 요청"(약 1일 소요, p48·63) | **실사용 전 5일 외부망 기간**에 환경 구성·**자체 보유 데이터 업로드** → 마운트 요청 후 차단 (p33) |
| 반출 | **불가** (p48) | 가능. 소스코드·AI 모델·(원천데이터 미포함) 성능지표 파일만. **zip·npz·npy·pkl·이미지·압축·반입 파일 불가**. `nasw/download` 에 두고 **종료 3영업일 전**까지 신청, 심의 2~3영업일, 기간 내 최대 3회, 승인 파일 10일간 URL 다운로드 (p36~38) |
| 기타 | 윈도우 서버 안에서만 복사·붙여넣기 가능 — 바깥 클립보드 격리 (p57) | 동일. 모든 작업은 컨테이너 안에서 (p13) |

따라서 결정은 이렇다.

1. **안면부(공개 다운로드 데이터)는 안심존이 필요 없다.** 분석환경을 기다리면 IRB + 2주가 든다. 학습은 Colab(T4) 또는 맥북(MPS)에서 바로 하고,
   분석환경이 열리면 그때 V100 으로 다시 돌려 비교한다 — `train.py` 는 어디서나 같은 명령이다.
2. **화상(안심존 전용)은 지금의 열람환경에서 스키마 확인과 지표 산출까지**, 가중치가 필요하면 **분석 이용 신청(IRB 필요)** 을 별도로 낸다.
   30일 자원이 열리면 "5일 외부망 기간에 저장소·timm 반입 → 마운트 → 학습 → 종료 3영업일 전 반출 신청(.pth/.safetensors + JSON/CSV)" 순서다.

---

## 2. 학습 환경

### 2-1. 1순위: 제공받은 GPU 클라우드 (V100 32GB · 8vCPU · 90GB)

- 10GB zip → 압축 해제 후 약 20~30GB. 90GB 디스크면 데이터 + 체크포인트가 들어간다.
- 학습 코드는 **순수 Python 스크립트**(`fastapi/training/aihub/train.py`)로 만든다. 노트북은 Colab 에서
  같은 스크립트를 부르는 얇은 래퍼만 둔다. 서버 종류가 바뀌어도 코드가 바뀌지 않게 하기 위해서다.
- **외부망이 막혀 있어도 돌아가게** 만든다:
  - 사전학습 가중치: ① 저장소의 `fastapi/model.pth`(피부 사진으로 이미 학습된 EfficientNet-B0 백본)를
    기본 초기값으로 쓴다 — `git clone` 만 되면 함께 따라온다. ② 인터넷이 되면 `--pretrained imagenet` 으로 timm 다운로드.
  - 의존성: torch · torchvision · timm · Pillow · numpy · scikit-learn 뿐. 환경에 이미 있는지 §4 의 점검으로 확인한다.
- **확정(사용자 + 통합 매뉴얼 v1.1):** 이 GPU 환경은 **안심존 GPU(분석환경) 서버**이며, 지금 할당된 열람환경과는 **별개의 신청**이다(§1-3 표).
  접속은 §5 가이드의 "03 | GPU Server 접속" 경로(SSH 터널 → 원격 윈도우 서버 → MobaXterm → 분석환경접속). 쓰게 되면:
  - **5일 외부망 기간**(p33)에 `git clone`·`pip install timm==1.0.11`·안면부 zip(10GB) 업로드·압축 해제까지 끝내고, 완료를 담당자에게 알린 뒤 데이터 마운트를 요청한다. 마운트되면 외부망이 끊긴다.
  - 도커 이미지는 **v7.1**(Ubuntu 22.04·CUDA 12.3·PyTorch 2.2.1·Python 3.11)을 고른다 — timm 1.0.11 과 `train.py` 가 그대로 돈다. 모든 작업은 컨테이너 안에서(p13).
  - 결과는 **종료 3영업일 전**까지 `nasw/download` 에 두고 반출 신청한다. 가능한 것: `facial.pth`(AI 모델), `facial.safetensors`(pkl 해석 논란 대비 사본), `facial-train-report.json`, `facial-val-predictions.csv`, `facial-holdout.csv`.
    **불가**: zip·npz·npy·pkl·이미지·압축 파일·외부에서 반입한 파일. `.git`·`.ipynb_checkpoints` 는 지우고 신청(p38). 심의 2~3영업일, 최대 3회.

### 2-2. 대안: Colab T4

기존 `fastapi/notebooks/pad_ham_training.ipynb` 와 같은 방식(Drive 에 zip 업로드 → 노트북 실행).
10GB 를 Drive 에 올리고 매번 `/content` 에 푸는 비용이 커서 GPU 클라우드가 되면 그쪽을 쓴다.

### 2-3. 안심존 열람환경 (화상)

- 접속 절차는 §5 에 가이드 전문 해설. 리눅스 서버에 Jupyter 가 있고 CPU 8코어/64GB 다(GPU 없음).
- 할 수 있는 것: 라벨 스키마 점검(`inspect_dataset.py`), manifest 생성, 소규모 학습·평가로 **지표 산출**.
- 할 수 없는 것: 파일 반출. 결과는 **화면 스크린샷**으로만 남긴다 → 스크립트 출력은 한 화면에 들어가게 짧게 찍는다.

---

## 3. 서비스 통합 계약 (이 PR 의 범위)

### 3-1. 태스크와 클래스 코드

| task | 의미 | 가중치 | 클래스 (순서 고정, 뒤에만 추가) |
|---|---|---|---|
| `skin_lesion` | 기존 피부 병변 8클래스 — **기본값, 변경 없음** | `fastapi/model.pth` | `akiec, bcc, bkl, df, mel, nv, vasc, inflammatory` |
| `facial` | 안면부 피부질환 (71863) | `fastapi/models/facial.pth` | `acne, atopic, normal, psoriasis, rosacea, seb_derm` |
| `burn` *(후속 PR)* | 화상 깊이 (71398) | `fastapi/models/burn.pth` | 라벨 스키마 확인 후 확정 (예정: `burn_1, burn_2s, burn_2d, burn_3`) |

`facial` 클래스 코드 ↔ 데이터셋 폴더명 ↔ 한글 병명 ↔ KCD(프론트 검색 선입력용):

| code | 폴더명 토큰 | 한글 | 영문 | severity | KCD |
|---|---|---|---|---|---|
| `acne` | 여드름 | 여드름 | Acne | LOW | L70 |
| `atopic` | 아토피 | 아토피피부염 | Atopic dermatitis | LOW | L20 |
| `normal` | 정상 | 정상 피부 | Normal skin | LOW | (없음) |
| `psoriasis` | 건선 | 건선 | Psoriasis | LOW | L40 |
| `rosacea` | 주사 | 주사(장미증) | Rosacea | LOW | L71 |
| `seb_derm` | 지루 | 지루피부염 | Seborrheic dermatitis | LOW | L21 |

코드는 알파벳순으로 고정한다. 기존 코드와 겹치지 않고 `disease.disease_code VARCHAR(20)` 안에 들어간다.
`normal` 은 KCD 가 없으므로 프론트 매핑에서 비워 둔다(검색 모달에 선입력하지 않음).

### 3-2. FastAPI (`fastapi/main.py`)

- 모듈 상수 `TASKS = {"skin_lesion": {...}, "facial": {...}}` — 각 항목에 `classes`, `names_ko`, `weights`, `input_size`.
- 가중치 파일이 **있는 태스크만 로드**한다. 없으면 로드하지 않고 `/health` 에 `loaded: false` 로만 보인다
  (CI 에는 `facial.pth` 가 없어도 통과해야 한다).
- `POST /predict-base64` 본문: `{"image_base64": ..., "task": "skin_lesion"}` — `task` 생략 시 기존과 동일.
  모르는 task → 400, 등록됐지만 미로드 → 503. 응답에 `"task"` 필드 추가(Spring 은 모르는 필드를 무시한다).
- `POST /predict`(multipart) 도 선택 폼 필드 `task`.
- `/health` 는 기존 키를 그대로 두고 `tasks: {name: {loaded, model_version, classes, weights}}` 를 덧붙인다.
- 기존 모듈 변수 `CLASSES`, `CLASS_NAMES_KO`, `model`, `transform`, `MODEL_VERSION` 은 **기본 태스크의 별칭으로 유지** —
  `tests/evaluate.py`, `evaluate_cam.py`, `test_model_contract.py` 가 그대로 돈다.
- 태스크마다 자기 `_model_lock` 과 CAM 레이어를 가진다(히트맵 교차오염 금지 규칙은 태스크별로 동일 적용).
- 모델 버전은 태스크별 가중치 파일 SHA256 앞 12자리: `efficientnet_b0-<hash>`.

### 3-3. Spring (`backend/`)

- `AnalysisRequest(imageIds, analysisType)` — `analysisType` 은 `enum AnalysisType { SKIN_LESION("skin_lesion"), FACIAL("facial") }`,
  **null 이면 SKIN_LESION**. FastAPI 호출 본문에 `task` 로 전달.
- `analysis_result.analysis_type VARCHAR(20) NOT NULL DEFAULT 'skin_lesion'` (V10) + 엔티티 필드(`@Builder.Default`).
- `AnalysisResponse.analysisType` 을 `modelVersion` 바로 뒤에 추가. 기존 필드의 이름·타입은 불변(record 라 위치 기반 생성자가 바뀌므로 호출처 2곳을 함께 수정; JSON 키 순서는 클라이언트가 의존하지 않는다).
- `disease` 시드: **V10 에서 `facial` 6행을 `ON DUPLICATE KEY UPDATE disease_id = disease_id` 로 추가.**
  **V1 은 고치지 않는다** — Flyway 가 V1 을 직접 적용한 DB(2026-08-30 이후 새로 만든 개발 DB)에서 체크섬 불일치로
  기동이 막힌다. 대신 계약 테스트가 **모든 마이그레이션의 `INSERT INTO disease`** 를 합쳐 모델 클래스와 대조한다.
- `AnalysisService.DISEASE_NAME_KO` / `DISEASE_REASON`, `KioskService.DISEASE_NAME_KO` 에 6종 추가 —
  `Map.of` 는 10쌍 한도라 `Map.ofEntries` 로 바꾼다(계약 테스트의 정규식도 함께).
- 키오스크 예비분석은 `skin_lesion` 고정(변경 없음). `AnalysisService.analyze(visitId, imageIds)` 2-인자 메서드는
  남겨 두고 기본 태스크로 위임한다(`AnalysisResilienceTest` 호환).

### 3-4. React (`frontend/`)

- `api/analysis.ts`: `AnalysisType = "skin_lesion" | "facial"`, `requestAnalysis(visitId, imageIds, analysisType)`, 응답에 `analysisType`.
- `Clinic.tsx`: "AI 분석" 버튼 옆에 분석 모델 선택(피부 병변 / 안면부 피부질환). 결과 카드에 어떤 모델인지 표시.
  `DISEASE_TO_KCD_CODE` 에 위 표의 KCD 추가.
- `Lookup.tsx`: 분석 종류 라벨 표시(소폭).

### 3-5. 계약 테스트 (`fastapi/tests/test_model_contract.py`)

- 태스크별로: `names_ko` 집합 = classes, 로드된 모델의 출력 차원 = len(classes).
- 노트북/학습 스크립트는 `TASK = "<task>"` 와 `CLASSES = [...]` 리터럴을 선언하고, 해당 태스크의 classes 와 **순서까지** 같아야 한다.
- DB 시드(모든 마이그레이션 합집합) = 모든 태스크 클래스 합집합. Java 두 맵 = 같은 합집합. 한글 병명 일치.
- 태스크별 홀드아웃 목록(`tests/baselines/<task>-holdout.csv`)이 있으면 라벨·중복 검사, 없으면 건너뜀(경고).

---

## 4. 사용자가 해야 할 일 (호출 지점)

1. **[지금]** 안면부 데이터 다운로드가 끝나면, 맥에서 아래를 돌려 `report.txt` 를 공유한다(압축 안 풀어도 된다).
   ```bash
   cd 2026-1-CECD1-5-Artifact-9
   python3 fastapi/training/aihub/inspect_dataset.py ~/Downloads/<다운로드폴더> --out ~/facial_report.txt
   ```
   → 라벨 JSON 키 이름과 중증도 값 체계를 확정하고 manifest 빌더의 키 매핑을 고정한다.
2. **[지금]** GPU 클라우드의 정체 확인(§2-1 의 a~d). 가능하면 그 서버에서
   `python3 -c "import torch, torchvision, timm; print(torch.__version__, torch.cuda.is_available())"` 결과를 알려 준다.
3. **[안심존 할당 후]** 화상 데이터 쪽: 윈도우 서버 → 리눅스 서버(Jupyter) 에서
   `inspect_dataset.py` 를 돌려 화면을 **스크린샷**으로 공유한다(반출 불가). 동시에 운영자에게
   "열람환경에서 학습한 모델 가중치의 반출 가능 여부 / 분석환경 전환 절차"를 문의한다.
4. **[학습 후]** `facial.pth` 와 평가 JSON 을 저장소에 넣는다(`fastapi/models/facial.pth`, `fastapi/tests/baselines/facial-*.json`).
   가중치가 들어오기 전까지 서비스는 `facial` 을 "미로드" 상태로 안전하게 표시한다.

> 보안: 포털 접속정보(터널링 서버 주소·ID·암호, 원격 윈도우/리눅스 서버 암호, 주피터 암호, `temp.pem`)는
> **어떤 파일에도 적지 않는다.** 이 문서의 명령은 전부 자리표시자다.

---

## 5. 안심존 접속 가이드 해설 (제공 문서 2종 + 운영자 메일)

### 5-1. 「안심존 온라인망 CPU 서버 접속 가이드 for MAC」(docx) — 전문 해설

> 이 매뉴얼은 Mac OS 사용자를 위한 안심존 프로젝트의 온라인망 CPU 서버 접속 가이드이며, macOS Monterey 12.4 에서 테스트했다.

**1. iTerm ssh 터널링 연결**

1) 터널링 연결 및 확인
- 터널링 연결 후 **터미널 창은 유지해야 한다** (닫으면 터널링이 해제된다).
- 터널링 연결 후 접속 대상 서버는 **127.0.0.1 또는 localhost** 로 접속해야 한다.
- `temp.pem` 은 키 파일이 있는 경로에서 실행하거나, 키 파일 경로를 입력한다.

```bash
# 운영자 메일에 적힌 값을 그대로 넣는다 (여기서는 자리표시자)
ssh -i temp.pem ubuntu@<터널링서버_공인IP> -p <터널링_PORT> -L 3389:<원격윈도우서버_내부IP>:3389
# 또는
ssh -i <경로>/temp.pem ubuntu@<터널링서버_공인IP> -p <터널링_PORT> -L 3389:<원격윈도우서버_내부IP>:3389
```

- 그림 설명: 터미널에 `ssh ncloud@[터널링서버_IP] -p 2324 -L 3389:[Connect_SVR]:3389` 형태로 입력하고 암호를 넣는 화면.
  `Connect_SVR` 자리에는 전달받은 서버 IP 를 넣는다(Connect server 는 3389 포트를 사용한다).
  로그인 프롬프트가 떨어지면 **[터널링 연결 완료 — Close 금지]** 상태다.
- 우리 경우(메일 기준): 사용자 `ubuntu`, 키 파일 인증, 로컬 3389 → 원격윈도우서버 내부 IP 의 3389 로 포워딩.
  `temp.pem` 권한은 `chmod 400 temp.pem` 으로 맞춰 둔다(권한이 열려 있으면 ssh 가 키를 거부한다).

**2. Windows 서버 접속**

1) Microsoft Remote Desktop 설치: App Store → "Microsoft Remote Desktop" 검색 → 설치. (그림: App Store 검색 결과 화면)
2) PC 에서 Microsoft Remote Desktop 실행 및 서버 추가. (그림: "Add your first PC connection to get started" 화면의 **Add PC**)
3) 원격접속 설정: **Add PC → User account: Ask when required → Add User Account** → 유저 및 패스워드 등록.
   **PC name 에는 localhost 또는 127.0.0.1 을 입력**한다(그림에 빨간 글씨로 강조). 서버 IP 가 아니다 — 1 단계 터널이 3389 를 로컬로 끌어왔기 때문이다.
   (그림: Add a User Account — Username `User@Domain or Domain\User`, Password, Friendly name)
   - Username 은 포털 "원격윈도우서버 접속 ID", Password 는 "원격윈도우서버 접속 암호".
4) 생성된 원격 PC(서버) 더블클릭으로 접속. (그림: `test.pc — No user account` 타일)

### 5-2. 「AI허브 Safezone 사용자 가이드 ver1.0 (MAC 사용자)」(PDF 9쪽) — 쪽별 전문 해설

이 PDF 는 **SSL VPN(BLUEMAX) 방식**이다. 포털 접속정보 화면에 "SSL VPN 서버 URL / 사용자 이름 / 암호" 가
**"Mac OS 사용자는 제공되지 않습니다"** 로 떠 있으므로, **맥 사용자는 5-1 의 SSH 터널링 방식으로 간다.**
그래도 윈도우 서버 이후 절차(원격 데스크톱 → MobaXterm → 분석환경)는 공통이라 전부 옮겨 둔다.

- **1쪽 — 01 | SSL VPN 접속 가이드 / 가. SSL VPN 프로그램 다운로드**
  ① AI허브 안심존 포털 "데이터 접속 정보" → [MAC용 VPN 프로그램 다운로드] 클릭.
  ② `BLUEMAXESP.pkg` 더블클릭 설치.
- **2쪽 — 나. SSL VPN 클라이언트 에이전트 설치**
  ① BLUEMAXCLIENT 설치 프로그램 시작 → "BLUEMAXESP 설치".
  ② 설치 완료 후 응용프로그램에 생성된 "BLUEMAXESP" 실행 → "시스템 확장 프로그램 차단됨" 안내가 뜬다.
- **3쪽**
  ③ 시스템 확장 프로그램 차단됨 → 보안 환경설정 열기 → 자물쇠 클릭(변경 허용) → "허용".
  ④ "허용" 클릭 후 → 클라이언트 종료 → 에이전트 "재실행" 및 CA 인증서 등록.
- **4쪽**
  ⑤ URL 입력(가이드 예시: `https://<VPN서버>:8443/index.html`) → Connect → ID/PW 입력 후 "Send EMAIL" →
  등록된 이메일로 OTP 번호 전송 → 인증번호 입력 후 Login → 로그인 완료.
  (포털 안내: VPN 장비는 macOS 12.0~14.0 만 지원, 그 외 버전은 관리자 문의.)
- **5쪽 — 02 | 사용자 Windows Server 접속 가이드**
  가. BLUEMAX SSL VPN 실행 및 로그인.
  나. Windows Server 접속 절차 ① 안심존 포털 → 데이터 접속정보 → 각 서버별 "접속 IP 및 접속 ID, 암호" 확인
  ② BLUEMAX SSL VPN 실행 및 로그인.
- **6쪽** ③ 단말(데스크탑/노트북)에서 App Store → "Microsoft remote desktop" 검색/설치
  ④ 설치 후 "Add PC" 클릭 ⑤ 사용자 접속 ID / 패스워드 입력.
- **7쪽** ⑥ 생성된 서버 더블클릭 ⑦ RDP 호스트 연결 "Continue" ⑦ Windows 서버 접속 화면.
- **8쪽 — 03 | GPU Server 접속 가이드 / 가. GPU 서버 접속 절차**
  ① Windows 서버 바탕화면의 "MobaXterm" 아이콘 더블클릭 실행 ② "분석환경접속" 더블클릭.
  → 우리 할당에서는 이 자리가 **리눅스 서버(포털의 "리눅스 서버 내부 IP", ID `ubuntu`)** 다.
  리눅스 서버의 Jupyter 는 포털의 "주피터노트북 암호"로 들어간다.
- **9쪽** 표지/여백(본문 없음).

### 5-3. 운영자 메일 요지 (2026-10-23)

- "첨부파일의 temp 키가 저장된 위치에서 `ssh -i temp.pem ubuntu@<공인IP> -p <PORT> -L 3389:<윈도우서버내부IP>:3389` 를 입력하고,
  터널링 서버 접근 후에 첨부 가이드를 참고해 순서대로 수행."
- 자원 할당: 이용시작일 첫날 할당 작업(15:00 이전 완료), 포털(safezone.aihub.or.kr)에서 확인, **승인일로부터 7일**.
- 열람서비스: 데이터 마운트 + **외부망 차단**. 상세는 안심존 통합 사용자 매뉴얼 41p~.
- **반출 불가**: 열람 이용자는 파일 반출 불가. 만료 후 서버 자동 회수·초기화(복구 불가).

### 5-4. 접속 체크리스트 (맥)

1. `chmod 400 temp.pem` → 5-1 의 ssh 터널 명령 실행 → 터미널 유지.
2. Microsoft Remote Desktop → Add PC(`localhost`) → 포털의 원격윈도우서버 ID/암호 → 접속.
3. 윈도우 서버 안에서 MobaXterm(또는 브라우저) → 리눅스 서버 내부 IP 로 SSH/Jupyter.
4. 리눅스 서버에서 §4-3 의 점검 스크립트 실행 → 화면 스크린샷.
   스크립트 파일은 반입이 안 되면 **윈도우 서버 안의 메모장에 타이핑/붙여넣기**로 옮긴다
   (RDP 클립보드가 막혀 있으면 수동 입력 — 그래서 점검 스크립트는 표준 라이브러리만 쓰고 짧게 유지한다).

---

## 6. 단계별 커밋 계획

| 단계 | 커밋 | 내용 |
|:---:|---|---|
| 1 | `docs: AI허브 데이터셋 도입 계획 및 점검 스크립트` | 이 문서 + `inspect_dataset.py` |
| 2 | `feat(fastapi): 태스크별 모델 레지스트리 (기본 동작 불변)` | `main.py` 레지스트리, `/health` 확장, 계약 테스트 확장 |
| 3 | `feat(training): 안면부 피부질환 데이터 파이프라인·학습 스크립트` | manifest 빌더, 홀드아웃, `train.py`, 평가 |
| 4 | `feat(backend): 분석 태스크(facial) 지원 + disease 시드 V10` | enum, 요청/응답 필드, 마이그레이션, 병명 맵 |
| 5 | `feat(frontend): 분석 모델 선택 UI + 안면부 KCD 매핑` | Clinic/Lookup, api 타입 |
| 6 | PR | CI(백엔드 테스트·프론트 빌드·FastAPI 계약 테스트) 통과 확인 |

가중치(`facial.pth`)와 기준 성적(`facial-holdout-*.json`)은 학습이 끝난 뒤 **별도 커밋**으로 들어온다.

---

## 7. 진행 현황과 인수인계 (2026-10-23)

작업 환경이 아래처럼 정해졌다(사용자 결정 2026-10-23).

- **코드 작업**: 사용자 맥북의 로컬 작업 세션이 이 브랜치(`kannikii/admiring-mccarthy-nfen9c`)를 이어받아 진행한다.
- **학습**: AI허브가 제공하는 GPU 환경(NVIDIA Tesla V100 32GB · 8vCPU · 90GB)을 쓴다. 맥북 MPS 는 예비 수단이다.
- **데이터**: 이미 맥북에 받아 둔 안면부 데이터(10GB zip)를 그 GPU 환경으로 올려 쓴다. 다시 내려받지 않는다.
- **화상 데이터**: 안심존 안에서만 열람·학습 가능(§1-3). 반출 경로가 확정되면 같은 `train.py` 로 안에서 학습한다.

이 절은 로컬 세션이 그대로 이어받을 수 있도록 "무엇이 확정됐고, 무엇이 어디까지 됐고, 다음에 무엇을 어떤 순서로 하는가"를 적는다.

### 7-1. 확정된 사실 — 안면부 데이터 실제 구조 (점검 보고서로 확인)

- 경로: `~/Downloads/14.안면부 피부질환 이미지 합성데이터/3.개방데이터/1.데이터/` (경로에 공백이 있으니 항상 큰따옴표).
- 레이아웃: `Training/01.원천데이터/TS_<질환>_<뷰>.zip`(png 800) · `Training/02.라벨링데이터/TL_…zip`(json 800) ·
  `Validation/01.원천데이터/VS_…zip`(png 100) · `Validation/02.라벨링데이터/VL_…zip`(json 100). 질환 6 × 뷰 2 → **총 10,800장**(train 9,600 / val 1,200).
  정면 1024×1024, 측면 512×512. `1.데이터` 바로 아래 `.DS_Store` 가 있다(무시).
- zip 안 항목은 **`/H0_205860_P1_L2.png` 처럼 루트에 평평하게, 앞에 `/` 가 붙어** 있다. 하위 폴더 없음.
- 파일명(=identifier) 패턴 `<접두2자>_<숫자>_P<n>_L<n>` (예: `H0_…`=여드름, `Z4_…`=정상). P/L 의 의미는 미문서 → 메타 열로만 보존.
- 라벨 JSON(파일당 `annotations` 1개):
  - `annotations[0].diagnosis_info.diagnosis_name` = 여드름 | 건선 | 아토피 | 정상 | 주사 | 지루 ← **클래스 라벨**(폴더 토큰과 일치, 교차검증용)
  - `diagnosis_info.{onset, distribution, bodypart, symptom, desc}` — 발병 시기·침범 범위(부분/넓은)·부위·증상(none/itching)·설명문
  - `generated_parameters.{gender(여/남/N/A), age_range, bodypart, race(황인)}`
  - `photograph.{file_path, width, height}` — **`file_path` 는 `여드름/정면/H0_….png` 로 적혀 있어 실제 zip 경로와 다르다 → stem(identifier)으로 짝짓는다.**
  - `bbox.{xpos, ypos, width, height}` — 정면 (0,0,1024,1024) 전체, 측면 500×500 오프셋 0~12 → 사실상 전체 이미지, **크롭 불필요**.
  - 여드름에만 `bbox.lesions[] {xpos, ypos, width, height, inflammatory}` 와 `bbox.lesion_area`(마스크 경로; 파일은 배포본에 없음).
  - **중증도(severity) 필드는 없다.** 학습 라벨은 질환명 하나. 나머지는 manifest 메타 열(gender, age_range, distribution, onset, symptom, lesion_count, inflammatory_lesions, prefix, p_code, l_code).
- 분할: AI허브가 준 Training/Validation 을 그대로 쓴다. 합성 데이터라 환자 ID 그룹핑이 필요 없다. Validation 1,200장이 공식 홀드아웃(`tests/baselines/facial-holdout.csv`)이 된다.

### 7-2. 어디까지 됐나

| 항목 | 상태 | 비고 |
|---|---|---|
| git identity kannikii 고정, 서명 비활성 | ✅ 커밋됨 | 로컬에서도 `git config user.name kannikii`, `user.email kwnnh0124@dgu.ac.kr` 확인 |
| 이 문서 + `inspect_dataset.py` | ✅ 커밋됨 (`92c90a9`, `244074e`) | |
| FastAPI 태스크 레지스트리 (`fastapi/main.py`, `tests/*.py`, `fastapi/models/`, `.gitignore`) | 🟡 구현 완료 · **검증 전 스냅샷** | 클라우드 세션의 병렬 에이전트가 작성. 로컬에서 계약 테스트·스모크 재실행 필요 |
| 백엔드 (`AnalysisType`, `V10__facial_task.sql`, 병명 맵, 엔티티·응답 필드, 테스트) | 🟡 구현 완료 · 검증 전 스냅샷 | `./gradlew test` 미실행 |
| 프론트 (`api/analysis.ts`, `Clinic.tsx`, `Lookup.tsx`) | 🟡 구현 완료 · 검증 전 스냅샷 | `npm run lint && npm run build` 미실행 |
| 학습 파이프라인 (`fastapi/training/aihub/train.py`, `facial/*`, README) | 🟡 작성 중이던 스냅샷 | 7-1 의 실제 스키마가 확정되기 **전** 에 범용으로 짜였다. `facial/build_manifest.py` 를 7-1 에 맞게 고정해야 한다 |
| 화상(`burn`) 태스크 | ⬜ 미착수 | §1-2·§1-3. 안심존 안에서만 가능 |

### 7-3. 로컬 세션이 할 일 (순서대로)

1. **스냅샷 검증부터.** 아무것도 고치기 전에 세 층의 검사를 돌려 현재 상태를 안다.
   ```bash
   # FastAPI (venv 에 torch CPU + timm + fastapi + httpx 설치 후)
   cd fastapi && INTERNAL_API_SECRET=test python tests/test_model_contract.py
   # 백엔드
   cd backend && ./gradlew test --no-daemon
   # 프론트
   cd frontend && npm ci && npm run lint && npm run build
   ```
   실패하는 것은 §3 계약 기준으로 고친다. 특히 확인할 것: task 생략 시 `/predict-base64` 응답이 변경 전과 같은가, `facial.pth` 없이 서버가 뜨고 `/health.tasks.facial.loaded == false` 인가,
   계약 테스트가 `Map.ofEntries` 와 모든 마이그레이션의 `INSERT INTO disease` 를 읽는가, Grad-CAM 락이 태스크별로 걸리는가.
2. **`fastapi/training/aihub/facial/build_manifest.py` 를 7-1 실제 구조로 고정.** zip 항목의 선행 `/` 제거, stem 으로 이미지↔JSON 짝짓기, 라벨은 `diagnosis_name`(폴더 토큰과 불일치 시 에러), 메타 열 추가, 중증도 인자 제거.
   그 다음 실제 데이터로 `build_manifest → make_holdout` 을 돌려 `fastapi/tests/baselines/facial-holdout.csv`(1,200행) 를 만든다. 이 파일은 커밋한다.
3. **층별 커밋 (검증 통과 후), 메시지는 한글 conventional commit, author 는 kannikii, 트레일러 없음:**
   `feat(fastapi): 태스크별 모델 레지스트리 (기본 동작 불변)` → `feat(training): 안면부 피부질환 데이터 파이프라인·학습 스크립트` →
   `feat(backend): 분석 태스크(facial) 지원 + disease 시드 V10` → `feat(frontend): 분석 모델 선택 UI + 안면부 KCD 매핑`.
4. **학습 — 두 경로 중 하나.** 분석환경(V100)은 IRB 와 2주 리드타임이 필요하다(§1-3 표). 안면부는 공개 데이터라 **Colab/맥북에서 먼저 학습**하고,
   분석환경이 열리면 같은 명령을 V100 에서 다시 돌려 비교한다. 아래는 V100 기준 명령이고, Colab/맥북은 `--device cuda` 를 `mps`(또는 Colab 의 `cuda`)로 바꾸면 된다.
   **AI허브 GPU 환경(V100)에서 학습.**
   - 반입: 맥북의 `1.데이터/` 폴더(10GB zip)와 이 저장소(`git clone` 또는 zip)를 GPU 환경으로 올린다. 환경이 외부망을 막고 있으면 pip 설치가 안 되므로
     먼저 `python -c "import torch, torchvision, timm; print(torch.__version__, torch.cuda.is_available())"` 로 기본 패키지 유무를 확인한다.
     timm 이 없을 때를 대비해 `train.py` 는 timm 미설치 시 torchvision 의 efficientnet_b0 로 대체하도록 보강하는 것이 안전하다(로컬 세션 할 일에 포함).
   - 실행(압축 해제 후 약 20~30GB 필요, 90GB 디스크면 충분):
     ```bash
     python fastapi/training/aihub/facial/build_manifest.py "<GPU환경의 1.데이터 경로>" --extract-to ~/facial_data --out ~/facial_run/manifest.csv
     python fastapi/training/aihub/facial/make_holdout.py --manifest ~/facial_run/manifest.csv --out ~/facial_run/facial-holdout.csv
     python fastapi/training/aihub/train.py --manifest ~/facial_run/manifest.csv --task facial --device cuda \
       --init repo --epochs 15 --batch-size 64 --image-size 224 --out-dir ~/facial_run
     ```
     `--init repo` 는 `fastapi/model.pth`(피부 사진으로 학습된 EfficientNet-B0) 백본을 물려받는다 — 저장소가 함께 올라가 있어야 한다.
     V100 에서 1만 장·15 epoch 은 수십 분 단위(추정). 산출물: `facial.pth`, `facial-train-report.json`, `facial-val-predictions.csv`, `facial-holdout.csv`.
   - 반출: 가중치·리포트·홀드아웃 목록만 맥북으로 가져온다(이미지 반출 없음). 안심존 분석환경이라면 반출 심사 절차를 따른다.
   - 예비: GPU 환경을 못 쓰는 날은 맥북에서 `--device mps`(연산 미지원 오류 시 `PYTORCH_ENABLE_MPS_FALLBACK=1` 또는 `--device cpu`)로 같은 명령을 돌린다. 1~3시간대(추정).
5. **가중치 적재와 검증.** `facial.pth` → `fastapi/models/facial.pth`, 리포트 → `fastapi/tests/baselines/facial-holdout-<날짜>.json` 으로 넣고
   `tests/evaluate.py --task facial` 로 홀드아웃 1,200장을 다시 재서 학습 리포트와 일치하는지 본다. 그 다음 `docker compose up --build` 로 화면에서 "안면부 피부질환" 모델을 선택해 분석이 되는지 확인.
   커밋: `feat(fastapi): 안면부 피부질환 모델 가중치 및 기준 성적 추가`.
6. **PR.** `kannikii/admiring-mccarthy-nfen9c` → `main`. 본문에 §0 의 표와 "기존 8클래스 경로는 변경 없음(task 생략 시 동일 응답)" 을 명시. CI 세 잡(백엔드 테스트·프론트 빌드·FastAPI 빌드/계약/동시요청)이 전부 초록이어야 한다.
7. **화상(`burn`) 후속.** 안심존 리눅스 서버에서 `inspect_dataset.py` 로 라벨 스키마 확인(스크린샷) → `training/aihub/burn/classes.py`(예정 코드 `burn_1, burn_2s, burn_2d, burn_3`) 와 manifest 빌더 작성 →
   같은 `train.py` 로 안심존 안에서 학습 → 반출 가능 여부(분석환경/반출 심사) 확인 후 가중치·리포트만 반출 → V11 시드 + 레지스트리 + 프론트 매핑 추가.

### 7-4. 지키는 규칙 (변하지 않음)

- GitHub 업로드는 **무조건 kannikii** 로. 커밋 author/committer 가 kannikii 가 아니면 올리지 않는다. Co-Authored-By 등 트레일러 금지, PR 본문에도 AI 표기 금지.
- 기존 8클래스 모델(`fastapi/model.pth`)과 그 응답 형식은 건드리지 않는다. 새 태스크는 옆에 추가한다.
- 홀드아웃 목록은 커밋하고 다시 고르지 않는다. 이미지 자체는 커밋하지 않는다.
- 안심존 접속정보·암호·`temp.pem` 은 어떤 파일에도 적지 않는다.
