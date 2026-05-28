# Reference Excel Files

Spring Boot 시작 시 `DataInitializer`가 이 폴더의 엑셀 파일을 읽어 DB에 자동 적재한다.

필요한 파일명:

```text
kcd_disease.xlsx  # 멘토 제공 상병코드.xlsx
drug_master.xlsx  # 멘토 제공 처방코드.xlsx
```

현재 원본 엑셀은 저장소에 커밋하지 않는다. 파일을 받은 뒤 아래처럼 이름을 맞춰 복사한다.

```bash
mkdir -p backend/src/main/resources/data
cp /path/to/상병코드.xlsx backend/src/main/resources/data/kcd_disease.xlsx
cp /path/to/처방코드.xlsx backend/src/main/resources/data/drug_master.xlsx
```

적재 확인 API:

```text
GET /api/v1/kcd-diseases?query=콜레라
GET /api/v1/drugs?query=아스피린
```
