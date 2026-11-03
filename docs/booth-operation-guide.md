# 부스 운영 가이드 — 2026-11-16(월) ~ 11-20(금)

> 부스 기간 동안 여러 대의 휴대폰·태블릿이 동시에 키오스크로 붙어도 사진과 결과가 섞이지 않고, 결과가 환자별로
> EMR 에 저장돼 진료 화면까지 이어지게 하는 준비 절차다. **AWS 콘솔·GitHub 에서 사람이 눌러야 하는 것**은 전부
> 이 문서에 순서대로 적었다. 코드 쪽 설명은 각 파일의 주석이 원본이다.
>
> 관련 파일: `docker-compose.booth.yml`(AI 워커 2 + 로드밸런서), `docker/ai-lb/nginx.conf`,
> `docker-compose.ai-worker.yml`(수평 확장), `.github/workflows/deploy.yml`(BOOTH_MODE),
> `scripts/booth/kiosk_load_check.py`(리허설 점검)

---

## 0. 한눈에 보기

### 일정

| 날짜 | 할 일 | 하는 사람 | 이 문서 |
|---|---|---|---|
| ~11/12 | 개발 PR 머지 (키오스크 모델 선택·동시성·부스 구성) | 팀 | — |
| 11/12 (목) | 예산 알림·크레딧 확인 | 콘솔 | [1](#1-예산-알림과-크레딧-확인-한-번만) |
| **11/14 (금) 오전** | 백업(AMI) → 인스턴스 `c7g.xlarge` 로 변경 → 부스 구성 켜기 | 콘솔·GitHub | [2](#2-백업--인스턴스-이미지ami-만들기) [3](#3-수직-확장--인스턴스-유형-변경) [4](#4-부스-구성-켜기--ai-워커-2개--로드밸런서) |
| 11/14 (금) 오후 | 리허설: 점검 스크립트 + 실제 기기 2~3대로 처음부터 끝까지 | 팀 | [5](#5-리허설-1114) |
| 11/16 ~ 11/20 | 부스 운영. 매일 아침 점검표 | 팀 | [7](#7-부스-당일-점검표와-장애-대응) |
| **11/21 (토)** | 원복: 부스 구성 끄기 → `t4g.small` 로 되돌리기 → 백업 정리 | 콘솔·GitHub | [8](#8-원복-1121) |

수직 확장 기간은 11/14 ~ 11/21, 약 7일(168시간)이다.

### 비용 (서울 리전 온디맨드 Linux, AWS 가격 API 2026-10-01 적용분)

| 인스턴스 | vCPU / 메모리 | 시간당 | 7일 | 쓰임새 |
|---|---|---:|---:|---|
| `t4g.small` (지금) | 2 / 2GB | $0.0208 | $3.49 | 평소 운영 |
| **`c7g.xlarge` (권장)** | 4 / 8GB | $0.1632 | **$27.42** | 부스 기기 2~3대 |
| `c7g.2xlarge` | 8 / 16GB | $0.3264 | $54.84 | 리허설에서 느리면, 또는 기기 4대 이상 |
| `c7g.xlarge` 추가 1대 (수평 확장, 5일) | 4 / 8GB | $0.1632 | $19.58 (5일) | [6](#6-선택-수평-확장--ai-전용-ec2-추가) |

- 크레딧 잔액 **$143.63**(2026-11 기준, 2027-04-29 만료)으로 충분하다. 권장 구성은 평소보다 약 $24 더 든다.
- `t4g.xlarge`(4 vCPU/16GB, $0.1664)도 값은 비슷하지만 **버스트형**이다. CPU 크레딧을 다 쓰면 느려지거나
  추가 요금이 붙는다. 추론처럼 CPU 를 오래 쓰는 일에는 버스트가 없는 `c7g` 가 맞다.
- `t4g` 와 `c7g` 는 둘 다 ARM(Graviton)이라 **도커 이미지를 다시 만들 필요가 없다.**
- EBS(gp3 30GB, $0.0912/GB-월)와 공인 IPv4 주소 요금은 인스턴스 유형과 무관하게 그대로다.

---

## 1. 예산 알림과 크레딧 확인 (한 번만)

유료 플랜으로 바뀐 뒤로는 크레딧을 넘는 사용액이 바로 청구된다. 부스 주간에는 사용액이 평소의 8배쯤 되므로
알림을 다시 확인한다.

1. AWS 콘솔 오른쪽 위 **계정 이름** → **결제 및 비용 관리**
2. 왼쪽 **크레딧** → 남은 금액과 만료일 확인 (지금 $143.63, 2027-04-29)
3. 왼쪽 **예산(Budgets)** → 기존 `artifact-budget` 선택(없으면 `docs/ec2-deployment-guide.md` 2-10 대로 만든다)
4. **편집** → 예산 금액을 **`60`** (USD)으로 올린다. 11월 예상은 $45~50 이다
   (t4g.small 23일 약 $11.5 + c7g.xlarge 7일 $27.4 + 디스크·공인 IP 약 $6). 평소 기준 금액이면 부스 주간에 알림이 쏟아진다
5. 알림 조건(실제 85%·100%, 예상 100%)과 수신 메일이 그대로인지 확인 → **저장**

> 예산의 "고급 옵션"(요금 유형, Charge types — 화면에 따라 이름이 조금 다를 수 있다)에서 **크레딧** 항목이
> 빠져 있어야 "크레딧으로 결제되더라도 얼마를 썼는지"를 기준으로 알림이 온다. 크레딧이 포함돼 있으면 크레딧이
> 사용액을 상쇄해 알림이 오지 않는다.

---

## 2. 백업 — 인스턴스 이미지(AMI) 만들기

인스턴스 유형을 바꾸기 전에 디스크 통째로 사진을 찍어 둔다. 무언가 잘못돼도 이 이미지로 지금 상태를 되살릴 수
있다. **인스턴스를 멈춘 상태에서 만든다** — MySQL 이 쓰는 도중의 디스크를 찍지 않기 위해서다.
3단계와 이어서 하므로 순서대로 진행한다.

1. EC2 콘솔 → 왼쪽 **인스턴스** → artifact 인스턴스 체크
2. 위쪽 **인스턴스 상태** → **인스턴스 중지** → 확인. 상태가 **중지됨**이 될 때까지 기다린다(1~2분)
   - 이때부터 서비스가 멈춘다. 부스 전날이 아니라 **11/14 오전**에 하는 이유다
3. 같은 인스턴스가 체크된 채로 **작업** → **이미지 및 템플릿** → **이미지 생성**
4. 입력:
   - 이미지 이름: `artifact-before-booth-20261114`
   - 나머지는 기본값 그대로
5. **이미지 생성** → 왼쪽 **이미지 → AMI** 에서 상태가 `pending` 으로 보이면 된다.
   `available` 이 될 때까지 기다리지 않고 3단계로 넘어가도 된다 — 스냅샷은 만들기 시작한 순간의 디스크다.

> 비용: 스냅샷은 실제로 쓴 용량만큼 월 몇백 원 수준이다. 8단계에서 지운다.

---

## 3. 수직 확장 — 인스턴스 유형 변경

1. (인스턴스가 **중지됨**인지 다시 확인)
2. **작업** → **인스턴스 설정** → **인스턴스 유형 변경**
3. 새 인스턴스 유형에 `c7g.xlarge` 를 검색해 고른다 → **변경 적용**
   - "무료 플랜 적격 인스턴스 유형만 선택할 수 있습니다"가 뜨면 계정이 아직 무료 플랜이다. 결제 및 비용 관리 →
     무료 티어 → 유료 플랜 업그레이드가 먼저다(이미 했다면 뜨지 않는다)
4. **인스턴스 상태** → **인스턴스 시작**. 상태 검사 `2/2 통과`까지 2~3분
5. **공인 IP 확인** — 인스턴스를 껐다 켜면 공인 IP 가 바뀔 수 있다.
   - 인스턴스 세부 정보의 **탄력적 IP 주소** 칸에 값이 있으면 그대로다 → 6번으로
   - 비어 있으면 **퍼블릭 IPv4 주소**가 새 값이다. https://www.duckdns.org 에 로그인 → `artifact-prod` 줄의
     **current ip** 칸에 새 IP 를 넣고 **update ip**. 1~2분 뒤 내 PC 에서 `nslookup artifact-prod.duckdns.org`
     결과가 새 IP 인지 확인한다
   - 접수증 QR 에는 IP 가 아니라 도메인이 찍히므로, DuckDNS 만 맞추면 인쇄물은 그대로 쓸 수 있다
6. 컨테이너는 도커가 알아서 다시 띄운다(`restart: unless-stopped`). 3분쯤 뒤 브라우저에서
   `https://artifact-prod.duckdns.org` 가 열리고 로그인되면 된다
7. **배포 러너가 살아 있는지**: GitHub 저장소 → **Settings** → **Actions** → **Runners** →
   `artifact-prod` 러너가 **Idle**(초록)이어야 한다. **Offline** 이면 서버에 SSH 로 접속해서
   (`ssh -i ~/.ssh/artifact-key.pem ubuntu@<서버IP>` — `docs/ec2-deployment-guide.md` 3단계):
   ```bash
   cd ~/actions-runner && sudo ./svc.sh start && sudo ./svc.sh status
   ```
   (러너를 서비스로 등록한 방법은 `docs/cicd-guide.md` 2-2)

---

## 4. 부스 구성 켜기 — AI 워커 2개 + 로드밸런서

AI 서버(FastAPI)는 한 프로세스가 한 번에 한 장씩만 추론한다. 워커를 2개로 늘리고 앞에 로드밸런서를 두면, 두 기기가
동시에 눌러도 서로 기다리지 않는다. 켜고 끄는 스위치를 GitHub 에 두었다 — **SSH 가 필요 없다.**

1. GitHub 저장소 → **Settings** → 왼쪽 **Secrets and variables** → **Actions** → **Variables** 탭
2. **New repository variable** → Name `BOOTH_MODE`, Value `true` → **Add variable**
3. **Actions** 탭 → 왼쪽 **Deploy** → 오른쪽 **Run workflow** → Branch `main` → **Run workflow**
4. 실행이 초록 체크로 끝나면 끝이다(빌드 포함 5~10분). 로그의 "빌드 · 재기동" 단계에
   `부스 구성으로 배포 (BOOTH_MODE=true)` 줄이 보여야 한다
5. 확인 (서버에 SSH 로 접속해서):
   ```bash
   docker ps --format '{{.Names}}\t{{.Status}}' | sort
   # artifact-ai-lb, artifact-fastapi, artifact-fastapi2 가 Up 이어야 한다 (fastapi 들은 (healthy) 까지 1~2분)
   docker exec artifact-backend env | grep FASTAPI_URL
   # FASTAPI_URL=http://ai-lb:8000  ← 이게 아니면 워커가 떠 있어도 1번만 일한다
   free -h
   # 사용 중 메모리가 5GB 안쪽이면 정상 (8GB 중)
   ```

> 이 변수를 켜 둔 동안에는 main 에 머지돼 자동 배포가 돌아도 부스 구성이 유지된다. 끄는 것은 8단계.

---

## 5. 리허설 (11/14)

### 5-1. 점검 스크립트 — 동시 분석·섞임·대기 시간

여러 기기가 **같은 순간에** 분석해도 결과·히트맵이 접수끼리 섞이지 않는지, 의사 화면이 기기와 같은 결과를 보는지,
얼마나 기다리는지를 실제 서버에서 잰다. 팀원 맥에서 돌린다(파이썬 표준 라이브러리만 쓴다).

```bash
# 저장소 루트에서. 사진 폴더에는 서로 다른 사진이 5장 이상 (얼굴 사진이면 더 좋다)
export BOOTH_LOGIN_ID=admin            # ADMIN 또는 DOCTOR 계정
export BOOTH_PASSWORD='...'
python3 scripts/booth/kiosk_load_check.py --base-url https://artifact-prod.duckdns.org \
    --images ~/booth-photos --devices 3 --rounds 3 --task mixed
```

- 접수를 3건 만들고(접수증이 출력된다 — 프린터 점검을 겸한다) 끝나면 취소한다
- **통과 기준**: 마지막 줄이 `✅ 섞임·오류 없음`, 동시 분석 대기 p95 가 **15초 이하**
- p95 가 15초를 넘으면 → 인스턴스를 `c7g.2xlarge` 로 올리고 `docker-compose.booth.yml` 의 `fastapi3` 를 되살리거나,
  6단계 수평 확장
- 분산 확인: 서버에서 `docker logs --tail 30 artifact-ai-lb` — `upstream=` 주소가 번갈아 나와야 한다

### 5-2. 실제 기기로 처음부터 끝까지

기기 2~3대(휴대폰·태블릿)로 실제 동선을 한 번씩 돈다.

1. 접수 화면에서 환자 등록 → 접수 → 접수증 QR 출력
2. 키오스크 기기에서 QR 스캔 → 본인 확인 → **얼굴 피부**(기본) 선택 → 사진 촬영 → 분석하기
   - 결과·히트맵이 먼저 뜨고, 아래 "AI 참고 소견"이 몇 초 뒤 채워지는지
3. 진료실 화면에서 그 접수를 열어 둔다 → 키오스크 결과가 **다시 고르지 않아도** 10초 안에 나타나는지,
   카드 제목에 `안면부 피부질환` 이 붙는지
4. 진료 시작 → AI 분석 → 처방 → 완료까지 평소처럼 이어지는지
5. 키오스크 기기에서 **확인**을 누른 뒤 브라우저 **뒤로 가기** — 앞사람 화면으로 돌아가지 않고 대기 화면이어야 한다
6. 두 기기에서 **같은 QR** 로 동시에 분석 → 한쪽은 "다른 화면에서 분석 중입니다"가 떠야 한다

### 5-3. 안면부 결과를 어떻게 안내할지

안면부 모델은 실사진에서 약하다(`fastapi/tests/baselines/README.md` 의 안면부 실사진 기록). 부스에서 방문자에게
결과를 설명할 때는 1순위 하나보다 **상위 후보 몇 개를 참고로** 보여 주고, 반드시 진료 단계로 이어 간다고 안내한다.

---

## 6. (선택) 수평 확장 — AI 전용 EC2 추가

**언제**: 리허설 p95 가 15초를 넘는데 인스턴스를 더 키우기 싫을 때, 또는 기기가 4대를 넘을 때.
기기 2~3대면 3~4단계만으로 충분하다. 다른 EC2 에서 FastAPI 워커만 띄우고, 메인 서버의 로드밸런서에 한 줄로 붙인다.

### 6-1. 워커 서버 만들기

1. EC2 콘솔 → **인스턴스 시작**
   - 이름: `artifact-ai-worker`
   - AMI: **Ubuntu Server 24.04 LTS**, 아키텍처 **64비트(Arm)**
   - 인스턴스 유형: `c7g.xlarge`
   - 키 페어: 메인 서버와 같은 것
   - 네트워크 설정 → **편집**: VPC·서브넷은 메인 서버와 **같은 것**
   - 보안 그룹: **보안 그룹 생성** → 이름 `artifact-ai-worker-sg`
     - 규칙 1: SSH(22), 소스 **내 IP**
     - 규칙 2: **보안 그룹 규칙 추가** → 유형 **사용자 지정 TCP**, 포트 `8000`, 소스 **사용자 지정** →
       메인 서버의 보안 그룹 ID(`sg-...`, 메인 인스턴스 → 보안 탭에서 확인)
     - ⚠️ 8000 번의 소스를 `0.0.0.0/0` 으로 두지 않는다
   - 스토리지: `20` GiB gp3
2. **인스턴스 시작** → 만들어진 인스턴스의 **프라이빗 IPv4 주소**를 적어 둔다(예: `172.31.12.34`)
3. `ssh -i ~/.ssh/artifact-key.pem ubuntu@<워커 퍼블릭 IP>` 로 접속 → 도커 설치(`docs/ec2-deployment-guide.md` 4-3, 4-4 와 같다)

### 6-2. 워커 띄우기

내 맥(저장소 루트)에서 필요한 파일만 보낸다:

```bash
ssh -i ~/.ssh/artifact-key.pem ubuntu@<워커 퍼블릭 IP> mkdir -p artifact-worker
scp -i ~/.ssh/artifact-key.pem -r fastapi docker-compose.ai-worker.yml ubuntu@<워커 퍼블릭 IP>:~/artifact-worker/
```

워커 서버에서:

```bash
cd ~/artifact-worker
# 메인 서버 ~/artifact/.env 의 INTERNAL_API_SECRET 과 **같은 값**
echo "INTERNAL_API_SECRET=<같은 값>" > .env && chmod 600 .env
docker compose -f docker-compose.ai-worker.yml up -d --build
sleep 90 && curl -s localhost:8000/health | head -c 200   # "status":"ok" 와 두 태스크 loaded:true
```

### 6-3. 메인 서버에 붙이기

메인 서버에서:

```bash
cd ~/artifact
echo "server <워커 프라이빗 IP>:8000 max_fails=3 fail_timeout=15s;" > docker/ai-lb/remote.d/worker1.conf
docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.https.yml \
               -f docker-compose.booth.yml restart ai-lb
docker logs --tail 30 artifact-ai-lb    # 분석을 몇 번 하면 upstream=<워커 IP>:8000 이 섞여 나와야 한다
```

`remote.d/*.conf` 는 git 이 무시하므로 배포(`git reset --hard`)에도 지워지지 않는다.

### 6-4. 떼기 (부스 후)

1. 메인 서버: `rm docker/ai-lb/remote.d/worker1.conf` → 위 `restart ai-lb` 명령
2. EC2 콘솔: `artifact-ai-worker` 체크 → **인스턴스 상태** → **인스턴스 종료(삭제)**
3. **보안 그룹** → `artifact-ai-worker-sg` 삭제

---

## 7. 부스 당일 점검표와 장애 대응

### 매일 아침 (5분)

- [ ] `https://artifact-prod.duckdns.org` 로그인
- [ ] 접수 1건 → 접수증 출력 → 키오스크로 얼굴 사진 분석 → 진료실 카드에 뜨는지
- [ ] 서버: `docker ps` 에 ai-lb·fastapi·fastapi2 가 Up(healthy), `free -h` 사용 5GB 안쪽
- [ ] 끝난 점검 접수는 취소해 둔다

### 증상별 대응

| 증상 | 원인 | 할 일 |
|---|---|---|
| 키오스크에 "다른 화면에서 분석 중입니다" | 같은 QR 을 두 기기에서 썼거나, 응답이 늦어 다시 누름 | 한 기기만 쓰고 몇 초 뒤 다시 누른다. 데이터는 섞이지 않는다 |
| "AI 분석 서버에 'facial' 모델이 준비되어 있지 않습니다" | 안면부 가중치 로드 실패 | `docker logs artifact-fastapi 2>&1 \| grep TASKS` 로 원인 확인. 그동안 키오스크에서 **점·피부 병변**으로 진행 가능 |
| 분석이 느리다(20초 이상) | 워커가 바쁨 | `docker stats --no-stream` — fastapi 들의 CPU 가 계속 꽉 차 있으면 기기 수를 줄이거나 6단계 |
| AI 참고 소견이 안 채워진다 | LLM 혼잡·키 문제 | 결과에는 영향 없다. `docker logs artifact-backend 2>&1 \| grep "LLM"` |
| 사이트가 안 열린다 | 인스턴스·IP·컨테이너 | EC2 상태 검사 → DuckDNS IP → `docker ps` 순서로. 전부 다시 띄우기: 4단계의 Deploy 수동 실행 |
| 워커 하나가 계속 죽는다 | 메모리 부족 등 | `docker logs artifact-fastapi2`. 급하면 BOOTH_MODE 를 false 로 바꾸고 Deploy — 워커 1개로 돌아간다 |

---

## 8. 원복 (11/21)

1. GitHub → Settings → Secrets and variables → Actions → Variables → `BOOTH_MODE` 를 **`false`** 로 편집
2. Actions → **Deploy** → **Run workflow** — 로그에 "부스 구성" 줄이 없어야 하고, `docker ps` 에서
   ai-lb·fastapi2 가 사라진다(`--remove-orphans`)
3. EC2: 인스턴스 **중지** → **작업 → 인스턴스 설정 → 인스턴스 유형 변경** → `t4g.small` → **인스턴스 시작**
   - `t4g.small` 은 메모리가 2GB 라 **반드시 2번을 먼저** 한다. 워커 2개가 떠 있는 채로 줄이면 메모리가 넘친다
   - 3단계 5번처럼 공인 IP·DuckDNS 를 확인하고, 7번처럼 러너를 확인한다
4. (6단계를 했으면) 6-4
5. 백업 정리: **이미지 → AMI** → `artifact-before-booth-20261114` 체크 → **작업 → AMI 등록 취소** →
   **Elastic Block Store → 스냅샷** 에서 그 AMI 의 스냅샷 체크 → **작업 → 스냅샷 삭제**
   - 부스 데이터를 따로 보관하고 싶으면 지우기 전에 `docs/cicd-guide.md` 4절 "배포 전 DB 백업"대로 덤프를 받는다
6. 1주일쯤 뒤 결제 및 비용 관리 → **크레딧**에서 사용액이 표의 예상과 맞는지 본다
