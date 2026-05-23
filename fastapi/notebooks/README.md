# Colab Notebooks

## skin_lesion_training_colab.ipynb

Colab에서 이미지 분류 모델을 빠르게 학습하고 테스트하는 템플릿이다.

사용 순서:

1. 이 노트북을 Google Drive에 업로드하거나 GitHub에서 Colab으로 연다.
2. Colab 메뉴에서 `런타임 > 런타임 유형 변경 > GPU`를 선택한다.
3. `DATA_ROOT`, `METADATA_CSV`, `IMAGE_DIR`를 본인 데이터 위치에 맞게 수정한다.
4. 위에서부터 순서대로 실행한다.
5. 생성된 모델 파일을 `fastapi/model.pth`로 맞춰 넣는다.

생성 파일:

- `model.pth`
- `label_map.json`
- `preprocess_config.json`

주의:

- 라벨 값은 백엔드 `disease.disease_code`와 같아야 한다.
- 현재 백엔드 기본 질병코드는 `nv`, `mel`, `bkl`, `bcc`, `akiec`, `df`, `vasc`다.
