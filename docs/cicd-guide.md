# GitHub Actions CI/CD 구축 가이드

> 대상: `docs/ec2-deployment-guide.md` 로 EC2 배포와 HTTPS 까지 끝낸 상태.
> 소요: 1단계 30분 + 2단계 40분. 3단계는 선택.

---

## 0. 그래서 뭘 하는 건가

### 지금

```
코드 수정 → 푸시 → (아무 검사 없음) → 머지
                                        ↓
                          누군가 SSH 접속 → git pull → up -d --build → 눈으로 확인
```

문제가 두 개다.

**하나. 깨진 코드가 main 에 들어가도 아무도 모른다.**
실제로 이 저장소에서 백엔드 테스트가 **컴파일조차 안 되는 상태로 오래 방치돼 있었다**
(`docs/security-remediation-plan.md` 감사 32번). 아무도 로컬에서 `./gradlew test` 를
돌리지 않았기 때문이다. 사람의 성실함에 기대는 규칙은 지켜지지 않는다.

**둘. 배포가 한 사람의 손에 묶여 있다.**
그 사람이 없으면 배포가 안 된다. 그리고 매번 명령어 세 줄을 손으로 치는 동안
`-f` 하나를 빠뜨리면 HTTPS 설정이 통째로 빠진 채로 뜬다.

### 목표

```
PR 올림 ──▶ [CI] 백엔드 테스트 · 프론트 린트+빌드 · FastAPI 기동 확인
                    │
                    ├─ ❌ 실패 → 머지 버튼이 잠긴다
                    └─ ✅ 통과 → 리뷰 후 머지
                                    │
                       main 에 들어감
                                    ↓
            [CD] EC2 안의 러너가 알아서 pull → build → up → 헬스체크
                                    ↓
                    https://artifact-prod.duckdns.org 반영 완료
```

사람이 하는 일은 **PR 올리기와 머지 버튼 누르기** 두 개로 줄어든다.

### 미리 알아둘 것

| 항목 | 내용 |
|---|---|
| 요금 | **공개 저장소는 GitHub Actions 무료·무제한.** ARM 러너도 무료 |
| 이미 만들어진 파일 | `.github/workflows/ci.yml`, `.github/workflows/deploy.yml` |
| 배포 대상 | EC2 `t4g.small` (ARM64, 2GB + 스왑 4GB) |
| 배포 경로 | EC2 의 `~/artifact` — **여기를 바꾸면 안 된다** (이유는 2-4) |

---

## 1단계 — CI: PR 검사 (30분)

### 1-1. 무엇을 검사하는가

`.github/workflows/ci.yml` 이 세 가지를 **병렬로** 돌린다.

| job | 하는 일 | 걸리는 시간 |
|---|---|---|
| `backend` | `./gradlew test` — 테스트 18개 | 1~2분 |
| `frontend` | `npm run lint` + `npm run build`(= `tsc -b` 포함) | 1분 |
| `fastapi` | 도커 이미지 빌드 → 컨테이너 기동 → `/health` 응답 확인 | 첫 회 5분, 이후 1분 |

세 개를 나눈 이유는 **어디가 깨졌는지 한눈에 보이게** 하기 위해서다.
하나로 합치면 백엔드에서 멈춘 순간 프론트는 검사조차 안 된다.

> **`fastapi` job 만 ARM 러너(`ubuntu-24.04-arm`)에서 도는 이유**
> 배포 대상인 EC2 t4g 가 ARM64 다. x86 에서는 되는데 ARM 에서 안 되는 일이
> 실제로 있다(특히 torch 계열 휠). CI 를 x86 에서만 돌리면 그걸 못 잡는다.

### 1-2. 파일 커밋하기

파일은 이미 만들어 뒀다. 내용을 한 번 읽어보고 커밋한다.

```bash
cd /Users/leekh/Downloads/CapstoneDesign/artifact-medical-ai
cat .github/workflows/ci.yml
git add .github/workflows/ci.yml
git commit -m "ci: PR 검사 워크플로 추가"
git push origin <현재브랜치>
```

### 1-3. 동작 확인

GitHub 저장소 → 상단 **Actions** 탭 → 방금 푸시한 커밋의 실행이 보인다.

- 노란 점 = 실행 중
- 초록 체크 = 통과
- 빨간 X = 실패 → 클릭하면 어느 job 의 어느 단계에서 멈췄는지 나온다

**첫 실행은 캐시가 없어서 느리다.** 특히 `fastapi` job 은 torch 를 처음 받느라
5분 정도 걸린다. 두 번째부터는 1분 안쪽이다.

> 로컬에서 확인해 본 결과 백엔드 테스트는 **18개 전부 통과**한다
> (`AnalysisResilienceTest` 2 · `DiagnosisApplicationTests` 10 · `EndpointAuthorizationTest` 6).
> 처음부터 초록불이 떠야 정상이다.

### 1-4. 검사를 통과해야만 머지되게 만들기 (권장)

CI 를 붙여도 **머지를 막지 않으면 의미가 절반이다.** 빨간 X 를 무시하고 머지할 수 있으니까.

저장소 → **Settings** → **Rules** → **Rulesets** → **New branch ruleset**

1. Ruleset Name: `main 보호`
2. Enforcement status: **Active**
3. Target branches → **Add target** → `Include default branch`
4. Rules 에서 체크:
   - ☑ **Require a pull request before merging** — main 에 직접 푸시 금지
   - ☑ **Require status checks to pass**
     → **Add checks** 에서 `백엔드 테스트`, `프론트 린트·빌드`, `FastAPI 빌드·기동` 셋을 추가
5. **Create**

> 검사 이름이 목록에 안 뜨면 **아직 한 번도 실행된 적이 없는 것이다.**
> PR 을 하나 올려 CI 를 한 번 돌린 뒤 다시 들어오면 보인다.

> ⚠️ 4인 팀이면 `Require approvals` 는 1명 정도가 적당하다. 그 이상으로 잡으면
> 마감 직전에 서로 리뷰를 기다리다 막힌다.

### 1-5. 안 될 때

| 증상 | 원인과 해결 |
|---|---|
| `Permission denied` — `./gradlew` | 실행 권한 누락. 로컬에서 `git update-index --chmod=+x backend/gradlew` 후 커밋 |
| `npm ci` 가 `lock file mismatch` | `package.json` 과 `package-lock.json` 이 어긋남. 로컬에서 `npm install` 후 lock 파일도 커밋 |
| `fastapi` job 이 `no space left` | 캐시가 부풀었을 때. Actions → Caches 에서 삭제 |
| ARM 러너가 대기만 함 | 저장소가 비공개로 바뀐 경우. `ubuntu-24.04-arm` → `ubuntu-24.04` 로 바꾸면 동작(대신 x86 검증) |

---

## 2단계 — CD: main 머지 시 자동 배포 (40분)

### 2-0. ⚠️ 왜 SSH 방식을 쓰지 않는가 — 먼저 읽을 것

인터넷의 대부분의 예제는 이렇게 한다: GitHub Actions 가 EC2 로 **SSH 접속해서** 명령을 친다.
그러려면 **22번 포트를 인터넷 전체(0.0.0.0/0)에 열어야 한다.** GitHub 러너의 IP 는
매번 바뀌기 때문이다.

지금 이 서버는 환자 이름·연락처·병변 사진이 들어가는 곳이고, 배포 가이드에서
**"SSH(22)는 절대 전체 공개하지 않는다"** 고 못 박아 둔 상태다. CI/CD 를 붙이자고
그걸 되돌리는 것은 앞뒤가 맞지 않는다.

| 방식 | 22번 포트 | 저장소에 둘 비밀값 | 판단 |
|---|---|---|---|
| SSH (흔한 예제) | **전체 공개 필요** | SSH 개인키 | ❌ 이 프로젝트엔 부적합 |
| SSH + 보안그룹 자동 개폐 | 순간만 열림 | AWS 액세스 키 | △ 되지만 AWS 키가 새로 생김 |
| **self-hosted 러너** | **열 필요 없음** | **없음** | ✅ 채택 |
| AWS SSM Run Command | 열 필요 없음 | (OIDC 로 없앨 수 있음) | ○ 더 안전하나 설정이 길다 |

**self-hosted 러너**는 EC2 안에서 도는 작은 프로그램이다. 이쪽에서 GitHub 로
**나가는 방향으로** "저 할 일 있나요?" 를 물어본다. 들어오는 문을 열 필요가 없다.
SSH 키도, AWS 키도 GitHub 에 저장하지 않는다.

> **다만 공개 저장소에서 self-hosted 러너는 조건부로만 안전하다.**
> 누군가 fork 해서 PR 을 올리면, 설정에 따라 **그 사람의 코드가 우리 EC2 안에서 실행될 수 있다.**
> 그래서 2-3 의 보안 설정은 **선택이 아니라 필수**다. 건너뛰지 말 것.

### 2-1. 러너 설치 (EC2 안에서)

GitHub 저장소 → **Settings** → **Actions** → **Runners** → **New self-hosted runner**

- Runner image: **Linux**
- Architecture: **ARM64** ← t4g 는 ARM 이다. x64 를 고르면 설치는 되는데 실행이 안 된다

화면에 나오는 명령어를 **그대로 복사해서** EC2 에서 실행한다. 토큰이 들어 있고
**1시간 뒤 만료**되므로 여기에 옮겨 적지 않는다. 대략 이런 모양이다:

```bash
mkdir actions-runner && cd actions-runner
curl -o actions-runner-linux-arm64-X.Y.Z.tar.gz -L https://github.com/actions/runner/releases/download/...
tar xzf ./actions-runner-linux-arm64-X.Y.Z.tar.gz
./config.sh --url https://github.com/CSID-DGU/2026-1-CECD1-5-Artifact-9 --token XXXX
```

`./config.sh` 가 네 가지를 물어본다:

| 질문 | 답 |
|---|---|
| Enter the name of the runner group | 그냥 엔터 (Default) |
| Enter the name of this runner | 그냥 엔터 (`ip-172-31-2-91`) |
| **Enter any additional labels** | **`artifact-prod`** ← 반드시 입력 |
| Enter name of work folder | 그냥 엔터 (`_work`) |

> **라벨을 빠뜨리면 배포 job 이 영원히 대기 상태로 멈춘다.** `deploy.yml` 의
> `runs-on: [self-hosted, artifact-prod]` 와 정확히 맞아야 한다.
> 잘못 넣었으면 GitHub 의 Runners 목록에서 해당 러너를 클릭해 라벨을 고칠 수 있다.

### 2-2. 서비스로 등록 (재부팅해도 살아 있게)

`./run.sh` 로 띄우면 SSH 를 끊는 순간 죽는다. systemd 서비스로 등록한다.

```bash
cd ~/actions-runner
sudo ./svc.sh install ubuntu
sudo ./svc.sh start
sudo ./svc.sh status
```

`Active: active (running)` 이 보이면 된다.
GitHub 의 **Settings → Actions → Runners** 에서도 초록색 **Idle** 로 바뀐다.

도커 권한 확인 (배포 가이드 4-4 를 했으면 이미 되어 있다):

```bash
docker ps        # sudo 없이 목록이 나와야 한다
```

> 안 나오면: `sudo usermod -aG docker ubuntu && sudo ./svc.sh stop && sudo ./svc.sh start`

### 2-3. ⚠️ 공개 저장소 보안 설정 — 반드시 한다

저장소 → **Settings** → **Actions** → **General**

1. **Fork pull request workflows from outside collaborators**
   → **`Require approval for all outside collaborators`** 선택
   (모르는 사람이 올린 PR 이 승인 없이 워크플로를 돌리지 못하게 한다)

2. **Workflow permissions**
   → **`Read repository contents and packages permissions`** 선택
   (워크플로가 저장소에 쓰기를 못 하게 한다)

그리고 `deploy.yml` 의 트리거는 **`push: branches: [main]`** 뿐이다.
`pull_request` 가 아니다. **fork 한 사람은 main 에 푸시할 수 없으므로,
외부인의 코드는 러너에 절대 닿지 않는다.** 이 조건이 이 구성의 안전을 떠받친다.

> **앞으로 `deploy.yml` 에 `pull_request` 나 `pull_request_target` 을 추가하지 말 것.**
> 그 한 줄이 "인터넷의 누구나 우리 의료 서버에서 명령을 실행" 으로 바뀐다.

### 2-4. 배포 워크플로가 하는 일

`.github/workflows/deploy.yml` 은 손으로 하던 것과 같은 순서다.

```
git fetch origin main
git reset --hard <이번 커밋>
docker compose -f ... -f ... -f ... up -d --build
헬스체크 (200 이 뜰 때까지 최대 200초)
docker image prune -f
```

**여기서 가장 중요한 한 가지** — 워크플로가 `actions/checkout` 을 **쓰지 않는다.**

`checkout` 은 코드를 러너 작업폴더(`~/actions-runner/_work/...`)에 푼다.
그런데 도커 컴포즈는 **프로젝트 이름을 폴더 이름에서 가져오고, 볼륨 이름 앞에 그걸 붙인다.**

```
~/artifact          에서 실행 → 볼륨 artifact_mysql-data      ← 지금 데이터가 여기 있다
_work/...-Artifact-9 에서 실행 → 볼륨 2026-1-...-9_mysql-data  ← 텅 빈 새 DB
```

즉 checkout 을 쓰면 **환자 데이터가 사라진 것처럼 보인다.** (실제로 지워지진 않지만
앱은 빈 DB 를 보게 된다) 그래서 기존 `~/artifact` 폴더에서 그대로 실행한다.

> **`git reset --hard` 를 쓰는 이유와 주의점**
> 서버는 main 과 항상 똑같아야 하므로 로컬 변경은 버리는 게 맞다. `git pull` 은
> 추적되지 않은 파일이 있으면 거부하지만 `reset --hard` 는 덮어쓰고 넘어간다.
> **`.env` 는 `.gitignore` 에 있고 main 에도 없으므로 안전하다.** 다만 서버에서
> 직접 만들고 커밋하지 않은 파일은 언젠가 사라질 수 있다는 점은 알고 있어야 한다.

### 2-5. 첫 배포

```bash
cd /Users/leekh/Downloads/CapstoneDesign/artifact-medical-ai
git add .github/workflows/deploy.yml
git commit -m "ci: main 푸시 시 EC2 자동 배포 추가"
git push origin <현재브랜치>
```

그리고 **PR 을 만들어 main 에 머지**한다. 머지되는 순간 Actions 탭에 `Deploy` 가 뜬다.

머지 전에 EC2 상태를 한 번 맞춰 둔다:

```bash
# EC2 에서
cd ~/artifact
git branch --show-current     # main 이어야 한다
git log --oneline -1
```

`main` 이 아니면 `git checkout main`, 로컬 커밋이 남아 있으면 첫 배포의
`reset --hard` 가 알아서 정리한다.

성공하면 Actions 로그 마지막에 이렇게 뜬다:

```
✅ 배포 성공 (7회차에 응답)
```

### 2-6. 안 될 때

| 증상 | 원인과 해결 |
|---|---|
| job 이 `Waiting for a runner` 에서 멈춤 | 라벨 불일치. Runners 목록에서 `artifact-prod` 라벨 확인 |
| `Offline` 로 표시됨 | 러너 죽음. EC2 에서 `sudo ~/actions-runner/svc.sh start` |
| `permission denied ... docker.sock` | `ubuntu` 가 docker 그룹에 없음. 2-2 마지막 참고 |
| `fatal: not a git repository` | `~/artifact` 경로가 다름. EC2 에서 `ls ~/artifact/docker-compose.yml` 확인 |
| 빌드 중 `Killed` / OOM | 메모리 부족. 스왑 확인 `free -h`, 없으면 배포 가이드 4-2. 자주 나면 **3단계**로 |
| 헬스체크만 실패 | 앱은 떴는데 응답이 늦은 것. `docker compose ... logs backend` 로 확인 |

---

## 3단계 (선택) — 빌드를 EC2 밖으로 빼기

### 언제 필요한가

지금 구조는 **EC2 안에서 빌드한다.** t4g.small 은 vCPU 2개 · 메모리 2GB 다.
Gradle 과 Vite 를 동시에 돌리면 스왑을 쓰면서 몇 분씩 걸리고, 그동안 서비스도 느려진다.
발표 직전에 배포했다가 5분간 먹통이 되는 상황이 실제로 나올 수 있다.

**증상이 나타나면** 그때 옮기면 된다. 미리 할 필요는 없다.

### 방법

빌드는 GitHub 의 ARM 러너(4코어·16GB, 무료)에서 하고, 결과 이미지를
**GHCR**(GitHub Container Registry, 공개 저장소는 무료)에 올린다.
EC2 는 **받아서 띄우기만** 한다 — 1분 안에 끝난다.

**① 이미지 주소를 쓰는 오버라이드 파일**

`docker-compose.ghcr.yml`:

```yaml
services:
  backend:
    build: !reset null
    image: ghcr.io/csid-dgu/2026-1-cecd1-5-artifact-9/backend:latest
  frontend:
    build: !reset null
    image: ghcr.io/csid-dgu/2026-1-cecd1-5-artifact-9/frontend:latest
  fastapi:
    build: !reset null
    image: ghcr.io/csid-dgu/2026-1-cecd1-5-artifact-9/fastapi:latest
```

> 이미지 주소는 **전부 소문자여야 한다.** GHCR 규칙이다.

**② `deploy.yml` 을 두 job 으로 나눈다**

```yaml
jobs:
  build:
    runs-on: ubuntu-24.04-arm          # GitHub 무료 ARM 러너
    permissions:
      contents: read
      packages: write                  # GHCR 에 올리기 위해 필요
    steps:
      - uses: actions/checkout@v4
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}   # 자동 발급, 따로 만들 것 없음
      - uses: docker/setup-buildx-action@v3
      - uses: docker/build-push-action@v6
        with:
          context: ./backend
          push: true
          tags: ghcr.io/.../backend:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max
      # frontend, fastapi 도 같은 식으로 3번 반복

  deploy:
    needs: build                        # 빌드가 끝나야 배포
    runs-on: [self-hosted, artifact-prod]
    steps:
      - run: |
          cd ~/artifact
          git fetch origin main && git reset --hard ${{ github.sha }}
          docker compose -f docker-compose.yml -f docker-compose.prod.yml \
                         -f docker-compose.https.yml -f docker-compose.ghcr.yml \
                         pull
          docker compose -f docker-compose.yml -f docker-compose.prod.yml \
                         -f docker-compose.https.yml -f docker-compose.ghcr.yml \
                         up -d
```

`--build` 가 사라지고 `pull` 이 생긴 것이 핵심이다.

> **주의: `latest` 태그만 쓰면 롤백이 안 된다.** 실전에서는 `:${{ github.sha }}` 를
> 같이 붙여 두고, 문제가 생기면 그 태그로 되돌린다.

---

## 4. 운영

### 배포가 잘못됐을 때 되돌리기

CD 는 롤백을 자동으로 하지 않는다. **일부러 그렇게 뒀다** — 자동 롤백은
"왜 실패했는지" 를 감춰서 같은 사고가 반복된다.

가장 빠른 방법은 GitHub 에서 **Revert** 하는 것이다:

1. 문제의 PR → 아래쪽 **Revert** 버튼 → 되돌리는 PR 생성 → 머지
2. main 이 바뀌었으므로 **CD 가 다시 돌아 이전 상태로 배포된다**

급하면 EC2 에서 직접:

```bash
cd ~/artifact
git reset --hard <되돌릴커밋>
docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.https.yml up -d --build
```

단 이렇게 하면 서버가 main 보다 뒤처진 상태가 된다. 다음 배포 때 자동으로 맞춰지지만,
그 사이 팀에는 알려야 한다.

### 배포 전 DB 백업

CD 는 DB 를 건드리지 않으므로(`up -d` 는 볼륨을 그대로 둔다) 매번 백업하지는 않는다.
대신 **하루 한 번 자동 백업**을 걸어 두는 편이 낫다 —
`docs/ec2-deployment-guide.md` 의 "DB 백업" 절 참고.

### 디스크 관리

배포할 때마다 이전 이미지가 쌓인다. `deploy.yml` 이 매번 `docker image prune -f` 를
돌리지만, 러너 작업폴더도 따로 자란다.

```bash
du -sh ~/actions-runner/_work    # 커지면
rm -rf ~/actions-runner/_work/*  # 비워도 된다 (다음 실행 때 다시 만들어짐)
df -h                            # 전체 여유 확인
```

### 러너 관리

```bash
sudo ~/actions-runner/svc.sh status    # 상태
sudo ~/actions-runner/svc.sh stop      # 정지 (배포 잠시 막고 싶을 때)
sudo ~/actions-runner/svc.sh start
journalctl -u actions.runner.* -f      # 로그
```

> **EC2 를 종료(terminate)할 때는 GitHub 에서도 러너를 지운다.**
> Settings → Actions → Runners → 해당 러너 → Remove. 안 지우면 목록에
> 죽은 러너가 남아 배포 job 이 그쪽으로 배정될 수 있다.

---

## 5. CI 가 잡아주지 못하는 것 — 정직하게

초록불이 떴다고 "안전하다" 는 뜻은 아니다. 지금 CI 의 한계는 다음과 같다.

**하나. DB 스키마 문제를 전혀 못 잡는다.**
테스트는 H2 + `ddl-auto=create-drop` 으로 돌고, 운영은 MySQL + `ddl-auto=none` +
`DataInitializer` 의 수동 DDL 이다. **완전히 다른 코드 경로**라서, 스키마가 어긋나도
테스트는 초록불이다 (`security-remediation-plan.md` (J)항). 해결하려면 Testcontainers 로
바꿔야 하고, 그건 G4 과제다.

**둘. 화면이 실제로 동작하는지 모른다.**
프론트는 "빌드가 되는지" 만 본다. 버튼을 눌렀을 때 API 가 제대로 불리는지는 검사하지 않는다.
E2E 테스트(Playwright 등)가 있어야 하는데 지금은 없다.

**셋. 배포된 앱의 기능 검증이 없다.**
CD 의 헬스체크는 첫 화면이 200 을 주는지만 본다. 로그인이 되는지, 이미지 분석이
도는지는 확인하지 않는다. 최소한의 확인은 배포 후 손으로 해야 한다:

```bash
# 인증이 살아 있는지 (401 이 정상)
curl -s -o /dev/null -w '%{http_code}\n' https://artifact-prod.duckdns.org/api/v1/patients

# Swagger 가 꺼져 있는지 (404 가 정상)
curl -s -o /dev/null -w '%{http_code}\n' https://artifact-prod.duckdns.org/api/swagger-ui/index.html
```

**넷. FastAPI 의 동시성 버그는 CI 로 안 잡힌다.**
`fastapi/tests/test_concurrent_heatmap.py` 는 **서버가 떠 있어야 도는 통합 스크립트**라
CI 에 넣지 않았다. Grad-CAM 히트맵 오염(감사 (A)항)은 지금도 재현 가능하며,
`threading.Lock` 수정이 들어가기 전까지는 유효한 위험이다.

---

## 문제 해결 모음

| 증상 | 확인할 것 |
|---|---|
| CI 가 아예 안 돌아감 | 파일 경로가 정확히 `.github/workflows/*.yml` 인지. `workflow` (단수) 오타 주의 |
| PR 에는 도는데 push 에는 안 됨 | `on:` 의 브랜치 목록 확인 |
| 배포는 성공인데 화면이 그대로 | 브라우저 캐시. 강력 새로고침(⌘⇧R) 후 확인 |
| `!reset` 관련 오류 | EC2 의 compose 가 2.24 미만. `docker compose version` 확인 |
| 러너에서만 빌드 실패 | 디스크·메모리. `df -h`, `free -h` |

---

## 참고

- GitHub Actions 문서: https://docs.github.com/actions
- self-hosted 러너 보안: https://docs.github.com/actions/security-guides/security-hardening-for-github-actions
- 관련 문서: `docs/ec2-deployment-guide.md`, `docs/security-remediation-plan.md`
