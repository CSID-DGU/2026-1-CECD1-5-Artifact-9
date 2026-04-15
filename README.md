# artifact-medical-ai
의료 영상 기반 AI 보조 진단/처방 지원 시스템 - 아티팩트팀

---
# GitHub 협업 가이드

---

## 0. 레포 정보

| 항목 | 내용 |
| --- | --- |
| 레포 이름 | `artifact-medical-ai` |
| 레포 URL | ‣https://github.com/kannikii/artifact-medical-ai |
| 기본 브랜치 | `main` |
| 협업 브랜치 | `dev` |

---

## 1. 최초 세팅

### 1-1. 레포 클론

```bash
git clone https://github.com/kannikii/artifact-medical-ai.git
cd artifact-medical-ai
```

### 1-2. 원격 브랜치 확인

```bash
git branch -a
# remotes/origin/main
# remotes/origin/dev
# remotes/origin/feat/backend  등이 보이면 정상
```

---

## 2. 브랜치 전략

> 핵심 원칙: **main에 직접 push하지 않는다.** 반드시 PR을 통해 머지한다.
> 

### 브랜치 구조

```
main
└── dev  ← 통합 브랜치 (여기서 전체 테스트)
    ├── kwon/backend      ← 이권형 백엔드 작업
    ├── kyeop/backend     ← 김경섭 백엔드 작업
    ├── sujin/frontend    ← 모수진 프론트 작업
    └── bogwang/ai        ← 최보광 AI 작업
```

### 브랜치 네이밍 규칙

```
[본인이름(영문)]/[작업내용]
```

| 예시 | 의미 |
| --- | --- |
| `kwon/patient-api` | 권형이 환자 API 작업 |
| `kyeop/db-schema` | 경섭이 DB 스키마 작업 |
| `sujin/visit-page` | 수진이 진료 페이지 작업 |
| `bogwang/efficientnet-train` | 보광이 모델 학습 작업 |

---

## 3. 매일 작업 흐름 (이 순서 꼭 지키기)

### Step 1 — 작업 전 항상 최신 코드 받기

```bash
git checkout dev
git pull origin dev
```

### Step 2 — 내 브랜치 만들기

```bash
# 본인 이름/작업내용 형식으로 생성
git checkout -b kwon/patient-api
```

> 이미 만들어진 내 브랜치로 이동할 때는:
> 

> `git checkout kwon/patient-api`
> 

### Step 3 — 코드 작성 후 커밋

```bash
git add .
git commit -m "feat: 환자 등록 API 구현"
```

### Step 4 — 원격에 푸시

```bash
git push origin kwon/patient-api
```

### Step 5 — PR(Pull Request) 생성

1. GitHub 레포 접속
2. 상단에 뜨는 **Compare & pull request** 버튼 클릭
3. **base: `dev`** ← compare: `kwon/patient-api` 확인
4. 제목과 설명 작성 후 **Create pull request**
5. 팀원 1명 이상 리뷰 요청

### Step 6 — 리뷰 완료 후 머지 & 브랜치 삭제

1. GitHub에서 **Merge pull request** 클릭
2. **Delete branch** 클릭 (머지된 브랜치는 바로 삭제)
3. 로컬에서도 정리:

```bash
git checkout dev
git pull origin dev
git branch -d kwon/patient-api
```

---

---

## 4. 충돌(Conflict) 났을 때

```bash
# 1. dev 최신 코드를 내 브랜치에 머지
git checkout kwon/patient-api
git merge dev

# 2. 충돌 파일 열어서 직접 수정
# <<<<<<< HEAD 와 >>>>>>> dev 사이의 내용을 정리

# 3. 수정 후 커밋
git add .
git commit -m "chore: dev 브랜치 충돌 해결"
```

> 충돌 해결이 어려우면 혼자 해결하려 하지 말고 **팀 카톡에 바로 공유**하기
> 

---

## 5. 폴더 구조

```
artifact-medical-ai/
├── backend/        # Spring Boot — 권형, 경섭
├── ai-server/      # FastAPI — 보광
├── frontend/       # React — 수진
└── docs/           # 설계 문서, 아키텍처 다이어그램
```

---

## 6. 자주 쓰는 명령어 치트시트

| 명령어 | 설명 |
| --- | --- |
| `git status` | 현재 변경 사항 확인 |
| `git log --oneline` | 커밋 이력 간단히 보기 |
| `git stash` | 작업 중인 내용 임시 저장 |
| `git stash pop` | 임시 저장한 내용 복원 |
| `git diff` | 변경된 내용 확인 |
| `git branch` | 현재 브랜치 목록 |
| `git branch -d [브랜치명]` | 로컬 브랜치 삭제 |

---

> 📌 궁금한 것 생기면 팀 카톡에 바로 물어보기
>
