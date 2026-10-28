# fastapi/models/ — 기본 태스크 외 가중치가 놓이는 곳

`fastapi/main.py` 의 `TASKS` 레지스트리가 태스크별 가중치를 이 폴더에서 찾는다.
기본 태스크(`skin_lesion`)의 가중치만 예전 자리(`fastapi/model.pth`) 그대로다 —
그 경로를 옮기면 `tests/evaluate.py`·기존 배포 스크립트가 전부 따라 바뀌어야 해서 두지 않았다.

| 파일 | task | 클래스 수 | 출처 |
|---|---|:---:|---|
| `facial.pth` | `facial` | 6 (`acne, atopic, normal, psoriasis, rosacea, seb_derm`) | AI허브 안면부 피부질환 이미지 합성 데이터(71863) — 만드는 방법은 `fastapi/training/aihub/README.md` |

### 현재 `facial.pth` (2026-10-28)

- `model_version`: `efficientnet_b0-2d3a2141c3d2`, 입력 **384×384** (`main.py` `TASKS["facial"]["input_size"]` 와 같아야 한다)
- 학습: Colab T4, `train.py --init repo --image-size 384 --batch-size 32 --epochs 15`, best epoch 13. 리포트 `tests/baselines/facial-train-2026-10-28.json`
- 홀드아웃(`tests/baselines/facial-holdout.csv`, AI허브 Validation 1,200장): 전체 92.3% · 정면 84.7% · 측면 100%. 정상 피부 재현율 100%, 가장 약한 쪽은 주사(89.0%)·지루피부염(90.5%, 정밀도 81.2%) — 주로 서로 헷갈린다
- 224 로 학습한 첫 모델은 전체 89.3% · 정면 79.8% 였다. 정면 사진(1024² 얼굴 전체)을 224 로 줄이면 작은 병변이 뭉개져서 384 로 올렸다
- **합성 이미지끼리의 평가다.** 실제 환자 사진에서의 성능은 재지 않았다

## 규칙

- **형식**: `timm` `efficientnet_b0` 의 `state_dict` 를 `torch.save` 한 파일. `num_classes` 는 그 태스크의
  `classes` 길이와 같아야 한다. 다르면 서버는 뜨지만 그 태스크만 미로드가 되고, 이유가 로그와
  `/health` 의 `tasks.<task>.error` 에 남는다(`tests/test_model_contract.py` 의 [1] 도 잡는다).
- **클래스 순서**: `TASKS[task]["classes"]` 가 곧 출력 인덱스다. 학습 스크립트는 반드시
  `fastapi/training/aihub/<task>/classes.py` 의 `CLASSES` 리터럴을 쓰고, 그 리터럴은 계약 테스트가
  `main.py` 와 순서까지 대조한다.
- **버전**: 따로 적지 않는다. 파일의 SHA256 앞 12자리가 `model_version`
  (`efficientnet_b0-<해시>`)이 되어 응답과 `/health` 에 실린다. 파일을 바꾸면 버전이 저절로 바뀐다.
  `sha256sum fastapi/models/facial.pth | cut -c1-12` 로 서버가 말하는 값과 대조할 수 있다.
- **없어도 서버는 뜬다**: 파일이 없으면 해당 태스크만 `/health` 에 `loaded: false` 로 보이고, 그 태스크
  요청은 503 을 받는다. CI 는 이 상태로 돈다(가중치는 학습이 끝난 뒤 별도 커밋으로 들어온다).
- **다른 경로를 쓰려면** 환경변수 `MODEL_PATH_<TASK 대문자>`(예: `MODEL_PATH_FACIAL=/data/facial-v2.pth`)로
  덮어쓴다. 모델을 갈아끼워 `tests/evaluate.py --task facial --compare` 로 비교할 때 쓴다.
- `.gitignore` 는 `*.pth` 를 전역으로 무시하되 이 폴더의 `*.pth` 만 예외로 둔다. 다른 이름으로 두면 커밋되지 않는다.
