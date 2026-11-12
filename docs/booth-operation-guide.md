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
| ✅ 11/10 | 개발 PR 머지 (#117 부스 구성, #118 읽을 수 없는 사진 400) | 팀 | — |
| ~11/12 (목) | 예산 알림 고치기 · vCPU 한도 확인 · 종료 방지 켜기 (서비스 안 멈춤) | 콘솔 | [1](#1-미리-할-일--예산-알림vcpu-한도종료-방지-한-번만) |
| **11/14 (토) 오전** | 백업(AMI) → 인스턴스 `c7g.xlarge` 로 변경 → 부스 구성 켜기 | 콘솔·GitHub | [2](#2-백업--인스턴스-이미지ami-만들기) [3](#3-수직-확장--인스턴스-유형-변경) [4](#4-부스-구성-켜기--ai-워커-2개--로드밸런서) |
| 11/14 (토) 오후 | 리허설: 점검 스크립트 + 실제 기기 2~3대로 처음부터 끝까지 | 팀 | [5](#5-리허설-1114) |
| 11/16 ~ 11/20 | 부스 운영. 매일 아침 점검표 | 팀 | [7](#7-부스-당일-점검표와-장애-대응) |
| **11/21 (토)** | 원복: 부스 구성 끄기 → `t4g.small` 로 되돌리기 → 백업 정리 | 콘솔·GitHub | [8](#8-원복-1121) |

수직 확장 기간은 11/14 ~ 11/21, 약 7일(168시간)이다. 11/14 는 **토요일**이다 — 주말에 모이기 어려우면 하루 당겨
11/13(금)에 해도 된다. 하루치(약 $3.9)가 더 들 뿐이다.

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

## 1. 미리 할 일 — 예산 알림·vCPU 한도·종료 방지 (한 번만)

부스 주간에는 사용액이 평소의 8배쯤 된다. 그 전에 세 가지를 해 둔다. 셋 다 서비스를 멈추지 않으므로 언제 해도 된다.
화면 이름은 한국어 콘솔 기준이고, 괄호 안은 영어 콘솔의 이름이다.

### 1-1. 예산 알림 — 크레딧을 빼고 센다

AWS 예산은 **기본값이 크레딧 포함**이다(Budgets API `CostTypes.IncludeCredit` 의 기본값이 true). 크레딧이 사용액을
상쇄해 예산의 현재 금액이 0달러 근처로 보이므로, 크레딧으로 결제되는 동안에는 금액을 얼마로 정하든 알림이 오지
않는다. 크레딧을 빼야 "실제로 얼마어치를 쓰고 있는지"로 알림이 온다.

1. AWS 콘솔 오른쪽 위 **계정 이름** → **결제 및 비용 관리(Billing and Cost Management)**
2. 왼쪽 **크레딧(Credits)** → 남은 금액과 만료일 확인 (11월 초 $143.63, 2027-04-29)
3. 왼쪽 **예산(Budgets)** → `artifact-budget` 이름 클릭 → 오른쪽 위 **편집(Edit)**
   - 예산이 없으면 **예산 생성(Create budget)** → **사용자 지정(고급)(Customize (advanced))** → **비용 예산(Cost budget)**
     으로 아래 값을 넣어 만든다. 템플릿(간소화) 화면에는 범위·크레딧 설정이 없다
4. 예산 금액 **`60`** (USD), 기간 **월별(Monthly)**. 11월 예상은 $45~50 이다
   (t4g.small 23일 약 $11.5 + c7g.xlarge 7일 $27.4 + 디스크·공인 IP 약 $6)
5. **예산 범위(Budget scope)** → **특정 AWS 비용 차원 필터링(Filter specific AWS cost dimensions)** → 차원
   **요금 유형(Charge type)** 을 고르고, 조건을 **제외(Excludes)** 로 둔 채 값 **크레딧(Credit)** 을 넣는다
   - 화면에 **고급 옵션(Advanced options)** 아래 요금 유형 체크 상자 목록이 보이면, 거기서 **크레딧(Credits)** 체크를
     해제하는 것이 같은 설정이다
6. **다음(Next)** 으로 넘기며 알림 조건(실제 85%·100%, 예상 100%)과 수신 메일을 확인 → 마지막 화면에서 **저장(Save)**
7. 확인: 몇 시간~하루 뒤 예산 목록의 **현재(Current)** 금액이 크레딧 화면의 이번 달 사용액과 비슷하면 맞다.
   0달러 근처면 크레딧이 아직 포함돼 있다

> 부스 주간에는 "예상 비용이 예산을 넘는다" 메일이 올 수 있다. 예측이 부스 기간의 하루 사용량을 월말까지 늘려
> 잡기 때문이고, 11/21 에 원복하면 정상이다. 이 알림의 진짜 쓸모는 **원복을 잊었을 때** 알려 주는 것이다 —
> `c7g.xlarge` 를 한 달 켜 두면 약 $118 로, 남은 크레딧이 한 달 만에 바닥난다.

### 1-2. vCPU 한도 확인

계정마다 동시에 켤 수 있는 vCPU 수에 한도가 있다. `c7g.xlarge` 는 4 vCPU, 6단계 수평 확장까지 하면 8 vCPU 가
필요하다. 한도를 늘리는 요청은 승인까지 몇 시간~며칠 걸리므로 **미리** 본다.

1. 콘솔 위쪽 검색창에 `Service Quotas` → 클릭
2. 왼쪽 **AWS 서비스(AWS services)** → 검색창에 `EC2` → **Amazon Elastic Compute Cloud (Amazon EC2)**
3. 할당량 검색창에 `Running On-Demand Standard` → **Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances**
4. **적용된 계정 수준 할당량 값(Applied account-level quota value)** 이 **4 이상**이면 된다(수평 확장까지 하려면 8 이상)
5. 모자라면 그 할당량을 클릭 → **계정 수준에서 증가 요청(Request increase at account level)** → 새 값 `8` →
   **요청(Request)**. 결과는 메일로 온다

### 1-3. 실수로 지우지 않게 — 종료 방지 켜기

EC2 의 **인스턴스 종료(삭제)(Terminate (delete) instance)** 는 "끄기"가 아니라 **삭제**다. 디스크와 DB 가 함께
사라지고 되돌릴 수 없다. 같은 메뉴의 **인스턴스 중지(Stop instance)** 와 헷갈리기 쉬우므로, 잘못 눌러도 막히게 해 둔다.

1. EC2 → 왼쪽 **인스턴스(Instances)** → `artifact-prod` 체크
2. **작업(Actions)** → **인스턴스 설정(Instance settings)** → **종료 방지 변경(Change termination protection)**
3. **활성화(Enable)** 체크 → **저장(Save)**

중지·시작·유형 변경은 그대로 할 수 있다. 중지까지 막는 **중지 방지(Stop protection)** 는 켜지 않는다.

---

## 2. 백업 — 인스턴스 이미지(AMI) 만들기

인스턴스 유형을 바꾸기 전에 디스크 통째로 사진을 찍어 둔다. 무언가 잘못돼도 이 이미지로 지금 상태를 되살릴 수
있다. **인스턴스를 멈춘 상태에서 만든다** — MySQL 이 쓰는 도중의 디스크를 찍지 않기 위해서다.
3단계와 이어서 하므로 순서대로 진행한다.

1. EC2 콘솔 → 왼쪽 **인스턴스** → `artifact-prod` 체크
2. 위쪽 **인스턴스 상태** → **인스턴스 중지** → 확인. 상태가 **중지됨**이 될 때까지 기다린다(1~2분)
   - ⚠️ 같은 메뉴의 **인스턴스 종료(삭제)** 가 아니다 — 그쪽은 서버 삭제다(1-3)
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
3. 새 인스턴스 유형에 `c7g.xlarge` 를 검색해 고른다 → **적용**
   - "무료 플랜 적격 인스턴스 유형만 선택할 수 있습니다"가 뜨면 계정이 아직 무료 플랜이다. 결제 및 비용 관리 →
     무료 티어 → 유료 플랜 업그레이드가 먼저다(이미 했다면 뜨지 않는다)
   - 목록에 없거나 지원되지 않는다고 나오면 같은 ARM 인 `c8g.xlarge`(4 vCPU/8GB, $0.1795/h, 7일 $30.16),
     그것도 안 되면 `m7g.xlarge`(4 vCPU/16GB, $0.2006/h, 7일 $33.70)를 고른다
4. **인스턴스 상태** → **인스턴스 시작**. 상태 **실행 중**, 상태 검사 `2/2`(콘솔에 따라 `3/3`) 통과까지 2~5분
   - 시작이 용량 부족(`InsufficientInstanceCapacity`)으로 실패하면 몇 분 뒤 다시 시작한다. 계속되면 2번으로 돌아가
     `c8g.xlarge` 로 바꾼다
   - vCPU 한도 오류(`VcpuLimitExceeded`)면 1-2 의 한도가 모자란 것이다. 유형을 `t4g.small` 로 되돌려 시작해 서비스를
     먼저 살리고, 한도가 승인되면 다시 한다
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
   (러너를 서비스로 등록한 방법은 `docs/cicd-guide.md` 2-2 — 서비스라 보통은 부팅과 함께 저절로 뜬다)
   - SSH 가 한참 멈췄다가 `Connection timed out` 이면 집·학교 IP 가 바뀐 것이다. EC2 → 왼쪽 **보안 그룹** →
     `artifact-sg` → **인바운드 규칙 편집** → SSH 규칙의 소스를 **내 IP** 로 다시 고른다 → **규칙 저장**
     (`docs/ec2-deployment-guide.md` 9단계 "내 IP가 바뀌어 SSH가 안 될 때")

---

## 4. 부스 구성 켜기 — AI 워커 2개 + 로드밸런서

AI 서버(FastAPI)는 한 프로세스가 한 번에 한 장씩만 추론한다. 워커를 2개로 늘리고 앞에 로드밸런서를 두면, 두 기기가
동시에 눌러도 서로 기다리지 않는다. 켜고 끄는 스위치를 GitHub 에 두었다 — **SSH 가 필요 없다.**

**반드시 3단계(`c7g.xlarge`)가 끝난 뒤에 켠다.** `t4g.small`(메모리 2GB)에서 워커 2개를 띄우면 메모리가 넘친다.
저장소 변수를 만들려면 저장소 **admin** 권한이 필요하다 — Settings 탭이 안 보이면 권한이 있는 팀원에게 부탁한다.

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

### 5-1. 점검 스크립트 — 부스 시나리오·동시 분석·대기 시간

부스에서 쓰는 환자는 **운영진 이름의 3명(이권형·김경섭·모수진)뿐**이다. 방문객 개인정보를 받지 않고, 방문객이 올 때마다
이 중 한 명으로 새로 접수한다. 두 스크립트 모두 이 3명을 쓰므로 **접수 화면에서 먼저 등록해 둔다**(없으면 멈춘다).
팀원 맥에서 돌린다(파이썬 표준 라이브러리만 쓴다).

**① 부스 시나리오** — 운영진 3명이 동시에 접수부터 증명서까지 가고, 같은 링크 두 폰·같은 환자 두 방문객·재촬영·
잘못된 파일·닫힌 링크·인쇄까지 실제 서버에서 일으킨다(시나리오 S1~S7 은 스크립트 머리말).

```bash
# 저장소 루트에서. 사진 폴더에는 서로 다른 사진이 4장 이상 (얼굴 사진이면 더 좋다)
export BOOTH_LOGIN_ID=doctor           # DOCTOR 또는 ADMIN 계정
export BOOTH_PASSWORD='...'
python3 scripts/booth/booth_scenarios.py --base-url https://artifact-prod.duckdns.org \
    --images ~/booth-photos --allow-print
```

- **통과 기준**: 마지막 줄이 `✅ 부스 시나리오 통과`. ⚠️(LLM 이 답하지 않음 등)은 읽고 판단한다
- `--allow-print` 는 프린터 점검을 겸할 때 준다 — 접수증 5장·진료요약 3장·발급확인증 3장이 나온다. 종이 없이
  돌리려면 맥북의 print-agent 를 끄고 1분 반 뒤에 돌린다(자세한 것은 스크립트 머리말)
- S1 의 접수 3건은 '진료 완료' 기록으로 남는다(메모 "부스 리허설 자동 점검"). 남기기 싫으면 `--kiosk-only`
- 유료 LLM 을 아끼려면 `--no-llm`

**② 동시 분석 부하** — 여러 라운드를 돌려 대기 시간을 잰다.

```bash
python3 scripts/booth/kiosk_load_check.py --base-url https://artifact-prod.duckdns.org \
    --images ~/booth-photos --devices 3 --rounds 3 --task mixed --patients operators
```

- 운영진 3명에게 접수를 3건 만들고(접수증이 출력된다 — 프린터 점검을 겸한다) 끝나면 취소한다
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
| 화면은 뜨는데 로그인하면 "서버에 연결할 수 없습니다", `docker ps` 는 전부 Up | 화면 서버(nginx)가 백엔드의 옛 내부 주소로 보낸다 — 백엔드만 새로 만든 배포 뒤(2026-11-12 에 겪음) | `docker restart artifact-frontend`. 화면 서버가 주소를 다시 찾게 고친 배포본(frontend/nginx.conf 의 resolver)부터는 생기지 않는다 |
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
5. 백업 정리 — 원복한 서버가 하루쯤 문제없이 돈 뒤에 한다: **이미지 → AMI** → `artifact-before-booth-20261114` 체크 →
   **작업 → AMI 등록 취소** → 대화상자의 **연결된 스냅샷 삭제(Delete associated snapshots)** 체크 → **AMI 등록 취소**
   - 체크 상자가 없는 화면이면 **Elastic Block Store → 스냅샷** 에서 설명에 그 AMI ID(`ami-...`)가 적힌 스냅샷을
     체크 → **작업 → 스냅샷 삭제**
   - 부스 데이터를 따로 보관하고 싶으면 지우기 전에 `docs/cicd-guide.md` 4절 "배포 전 DB 백업"대로 덤프를 받는다
6. 1주일쯤 뒤 결제 및 비용 관리 → **크레딧**에서 사용액이 표의 예상과 맞는지 본다
