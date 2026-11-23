# AWS 실습 — Vercel 화면 · ALB · AI 오토스케일링으로 나누기

> 지금까지는 EC2 한 대(`t4g.small`)에 화면·백엔드·AI·DB·HTTPS(Caddy)를 전부 올렸다. 이 문서는 그것을
> **화면은 Vercel, API 는 ALB 뒤 백엔드 EC2, AI 는 내부 ALB 뒤 오토스케일링 그룹**으로 나누는 실습 절차다.
> **Vercel 부터 한다** — 화면을 먼저 Vercel 로 옮기고(그동안 API 는 지금 서버), 도메인도 Vercel 에서 산다.
> 그다음 AWS 를 만들고, 마지막에 API 주소만 바꿔 넘어간다. 콘솔에서 사람이 눌러야 하는 것을 순서대로 적었다.
>
> 관련 파일: `frontend/vercel.json`, `frontend/src/api/base.ts`(`VITE_API_BASE_URL`), `docker-compose.elb.yml`(백엔드 EC2),
> `.github/workflows/deploy.yml`(`DEPLOY_TARGET`), `.github/workflows/deploy-ai.yml`(ECR · 인스턴스 새로 고침),
> `deploy/aws/ai-user-data.sh`(AI 인스턴스 시작 스크립트), `deploy/aws/iam/*.json`(IAM 정책 견본)

---

## 0. 한눈에 보기

### 구성 (끝난 모습)

```mermaid
flowchart LR
  U["브라우저<br/>의료진 PC · 환자 휴대폰 · 로비 모니터"]
  V["Vercel<br/>React 화면 · 도메인 · DNS<br/>&lt;도메인&gt;"]
  subgraph AWS["AWS 서울 리전 · 기본 VPC"]
    ALB["공개 ALB<br/>HTTPS 443 · ACM 인증서<br/>api.&lt;도메인&gt;"]
    BE["백엔드 EC2 t4g.small<br/>Spring Boot + MySQL(도커) :8080"]
    IALB["내부 ALB<br/>HTTP 80"]
    subgraph ASG["AI 오토스케일링 그룹 · 1~4대"]
      AI1["AI EC2 t4g.small<br/>FastAPI :8000"]
      AI2["AI EC2 t4g.small<br/>FastAPI :8000"]
    end
    ECR[("ECR<br/>artifact-ai")]
    SSM[("SSM 파라미터<br/>INTERNAL_API_SECRET")]
  end
  GH["GitHub"]
  U -->|화면| V
  U -->|"API (CORS)"| ALB --> BE --> IALB --> AI1 & AI2
  GH -->|"main 머지 → 화면 자동 배포"| V
  GH -->|"Actions · OIDC → 이미지 푸시 · 인스턴스 새로 고침"| ECR
  AI1 & AI2 -.->|"부팅 때 이미지 · 비밀값"| ECR & SSM
  GH -->|"self-hosted 러너(지금 그대로)"| BE
```

| 무엇 | 어디서 | 배포 |
|---|---|---|
| 화면(React) | Vercel | Vercel Git 연동 — main 에 머지하면 Vercel 이 `frontend/` 만 빌드해 배포. `frontend/` 가 안 바뀐 커밋은 건너뛴다 |
| 도메인 · DNS | Vercel 에서 산 도메인(Vercel DNS) | 화면 = `<도메인>`, API = `api.<도메인>`(ALB 를 가리키는 CNAME) |
| 백엔드 + MySQL | 지금 쓰는 EC2(그대로 백엔드 서버가 된다 — DB 볼륨 유지) | `deploy.yml` (self-hosted 러너, `DEPLOY_TARGET=elb`) |
| AI(FastAPI) | 오토스케일링 그룹의 EC2 들 | `deploy-ai.yml` (ECR 푸시 → 인스턴스 새로 고침) |
| HTTPS | 화면은 Vercel, API 는 공개 ALB(ACM 인증서) | — |

### 순서

| 단계 | 하는 일 | 화면 | API |
|---|---|---|---|
| **1** | **Vercel 프로젝트 · 도메인 구매 · 지금 서버에 새 화면 주소 허용** | **Vercel(새 도메인)** + duckdns 도 그대로 | 지금 서버(duckdns) |
| 2 | 보안 그룹 4개 | 〃 | 〃 |
| 3 | IAM — AI 인스턴스 역할, GitHub OIDC 역할 | 〃 | 〃 |
| 4 | SSM 파라미터(내부 시크릿) | 〃 | 〃 |
| 5 | ECR + 첫 AI 이미지 | 〃 | 〃 |
| 6 | 내부 ALB + AI 오토스케일링 그룹 | 〃 | 〃 |
| 7 | 공개 ALB + ACM + `api.<도메인>` (DNS 레코드는 Vercel 에서) | 〃 | 〃 |
| **8** | **백엔드 .env → `DEPLOY_TARGET=elb` → 배포 → Vercel 의 API 주소 바꿔 다시 배포** | Vercel 만 | **ALB(`api.<도메인>`)** |
| 9 | 오토스케일링 실습(부하 걸기) | | |
| 10~11 | 되돌리기 · 정리 | | |

1 단계만 끝내도 화면은 Vercel 과 새 도메인으로 돈다. 2~7 단계는 지금 사이트를 그대로 둔 채 만들고, 8 단계에서 넘어간다.
`DEPLOY_TARGET` 이 비어 있는 동안 EC2 배포는 지금까지와 같은 한 서버 구성으로 돈다.

### 비용 (서울 리전 온디맨드, 30일 기준 대략)

| 항목 | 개수 | 월 | 비고 |
|---|---:|---:|---|
| Vercel Hobby | 1 프로젝트 | 무료 | 개인·비상업 용도. 학생 프로젝트는 여기에 든다 |
| 도메인(Vercel 에서 구매) | 1 | 연 $10~20 안팎 | TLD 마다 다르다 — 구매 화면에서 확인. DNS 는 무료 |
| EC2 `t4g.small` ($0.0208/h) | 3 (백엔드 1 + AI 2) | 약 $45 | AI 가 4대로 늘면 그 시간만큼 더 |
| ALB (시간당 + LCU) | 2 (공개 1 + 내부 1) | 약 $35~40 | 끌 수 없다 — 안 쓰면 지운다 |
| 공인 IPv4 ($0.005/h) | 5 안팎 (공개 ALB 2 + EC2 3) | 약 $18 | 내부 ALB 는 공인 IP 가 없다 |
| EBS gp3 · ECR | 30 + 16×2 GB · 이미지 몇 개 | 약 $6 | |
| **AWS 합계** | | **약 $105/월 (하루 $3.5)** | 지금 한 서버 구성은 약 $22/월 |

- AWS 부분(2~8)은 **기간을 정해 두고, 끝나면 11 단계로 지운다.** 그대로 두면 크레딧이 한 달 반 정도면 바닥난다.
  크레딧 잔액과 예산 알림은 `docs/booth-operation-guide.md` 1-1 의 화면 순서대로 먼저 확인한다.
- 값은 콘솔 요금표로 한 번 더 확인한다. 특히 ALB 는 트래픽(LCU)에 따라 조금 달라진다.
- vCPU 한도: `t4g.small` 은 2 vCPU 다. AI 가 최대 4대면 백엔드 포함 10 vCPU 가 필요하다. EC2 → 한도(Limits)에서
  "Running On-Demand Standard instances" 가 10 이상인지 본다(`docs/booth-operation-guide.md` 1-2).

### 이 문서에서 쓰는 이름

`<도메인>` 은 Vercel 에서 산 도메인(예: `artifact-emr.com`), `<프로젝트>` 는 Vercel 프로젝트 이름이다.

| 이름 | 무엇 |
|---|---|
| `https://<도메인>` | 화면(Vercel). `www.<도메인>` 은 여기로 넘긴다 |
| `https://api.<도메인>` | API(공개 ALB) |
| `artifact-ai` | ECR 저장소 |
| `artifact-ai-tg` / `artifact-ai-alb` | AI 대상 그룹 / 내부 ALB |
| `artifact-ai-lt` / `artifact-ai-asg` | AI 시작 템플릿 / 오토스케일링 그룹 |
| `artifact-backend-tg` / `artifact-api-alb` | 백엔드 대상 그룹 / 공개 ALB |

AWS 리전은 전부 **아시아 태평양(서울) ap-northeast-2**. 콘솔 오른쪽 위에서 먼저 확인한다.

---

## 1. Vercel — 화면 먼저 (API 는 아직 지금 서버)

### 1-1. 프로젝트 만들기 — 모노레포에서 `frontend/` 만

1. https://vercel.com → **Sign Up** → **Continue with GitHub** (Hobby 플랜)
2. **Add New… → Project** → **Import Git Repository** 에서 `CSID-DGU/2026-1-CECD1-5-Artifact-9` 옆 **Import**
   - 목록에 없으면 같은 화면의 **Adjust GitHub App Permissions** → GitHub 에서 Vercel 앱을 **CSID-DGU** 조직에 설치하고
     이 저장소를 고른다. 조직 소유자가 아니면 "요청"만 가고, 소유자가 승인해야 목록에 뜬다
   - 그래도 가져올 수 없다고 나오면(플랜 제한 등) 거기서 멈추고 메시지를 그대로 알려 달라 — GitHub Actions 에서
     Vercel CLI 로 배포하는 방법으로 바꿀 수 있다
3. **Configure Project**
   - Project Name: 예) `artifact` (기본 주소가 `https://artifact.vercel.app` 처럼 된다. 이미 있으면 뒤에 글자가 붙는다)
   - **Root Directory → Edit → `frontend` 선택** ← 이게 핵심이다. Vercel 은 이 폴더만 프로젝트로 보고 빌드한다
   - Framework Preset 은 `Vite` 로 잡힌다. Build·Output 설정은 건드리지 않는다 — `frontend/vercel.json` 이 정한다
     (빌드 `npm run build`, 결과 `dist`, 모든 경로를 `index.html` 로 넘겨 `/main/clinic`·`/kiosk/<토큰>` 을 새로 고쳐도 열린다)
   - **Environment Variables**: Key `VITE_API_BASE_URL`, Value `https://artifact-prod.duckdns.org` (끝에 `/` 없이)
     - 지금은 API 가 지금 서버(duckdns)에 있으므로 그 주소다. 8 단계에서 `https://api.<도메인>` 으로 바꾼다
     - 화면은 이 값으로 API 를 직접 부른다(`frontend/src/api/base.ts`). **빌드할 때 굳는다** — 바꾸면 다시 배포해야 반영된다
4. **Deploy** → 1~2분 뒤 `https://<프로젝트>.vercel.app` 가 뜬다
   - 이 시점에는 로그인이 아직 안 된다(지금 서버가 이 주소를 모른다 — 1-4 에서 허용한다). 데모 체험은 된다

### 1-2. 자동 배포(CI/CD) — 무엇이 언제 배포되나

Vercel 의 Git 연동이 GitHub Actions 없이 알아서 한다.

| 언제 | Vercel 이 하는 일 |
|---|---|
| main 에 머지(또는 push) | **Production** 배포 → `<도메인>` 이 새 화면이 된다 |
| PR 을 열거나 PR 브랜치에 push | **Preview** 배포 → PR 에 미리보기 주소가 댓글로 달린다 |
| 그 커밋에 `frontend/` 변경이 없음 | 빌드를 **건너뛴다**(배포 목록에 Ignored/Canceled) — 백엔드·AI·문서만 바뀐 커밋 |

- 건너뛰기는 `frontend/vercel.json` 의 `ignoreCommand` 다: 직전 성공 배포 커밋(`VERCEL_GIT_PREVIOUS_SHA`, 없으면 직전 커밋)과
  비교해 `frontend/` 가 그대로면 빌드하지 않는다. 비교할 커밋을 못 찾으면 빌드하는 쪽으로 넘어간다
- Production 브랜치는 기본값 `main` 그대로 둔다(Project → Settings → Git)
- **환경변수를 바꾼 것만으로는 반영되지 않는다.** Deployments → 가장 위 Production 배포의 `⋯` → **Redeploy**
- 미리보기 주소(`*.vercel.app`)는 기본으로 Vercel 로그인이 걸려 있고(Deployment Protection), 백엔드 CORS 목록에도 없어
  미리보기에서는 로그인이 안 된다. 미리보기에서도 써 보려면 1-4 의 `CORS_ALLOWED_ORIGINS` 에 그 주소를 더한다
- 지금처럼 EC2 의 화면(duckdns)도 8 단계까지는 같이 배포된다 — 두 곳이 잠깐 공존한다

### 1-3. 도메인 사기 — Vercel 에서

1. Vercel 대시보드 → 왼쪽(또는 위) **Domains** → **Buy** (또는 Add → Buy a new domain)
2. 원하는 이름을 검색 → 가격 확인 → 구매. 카드 결제가 필요하다(연 단위, 자동 갱신 여부를 확인해 둔다)
   - Vercel 에서 산 도메인은 **네임서버가 처음부터 Vercel** 이다. DNS 레코드도 Vercel 화면에서 고친다(7 단계에서 쓴다)
3. 프로젝트 → **Settings → Domains** → `<도메인>` 입력 → **Add**
   - `www.<도메인>` 도 같이 추가하겠냐고 물으면 추가하고, **www → `<도메인>` 리디렉션**을 고른다
   - 같은 계정에서 산 도메인이라 DNS 가 자동으로 잡히고 HTTPS 인증서도 몇 분 안에 붙는다. 상태가 **Valid Configuration** 이면 된다

### 1-4. 지금 서버가 새 화면을 받아 주게

화면(`https://<도메인>`)과 API(`https://artifact-prod.duckdns.org`)의 출처가 달라져 브라우저가 CORS 를 확인한다.
지금 서버에 SSH 로 접속해 `~/artifact/.env` 에 다음을 넣는다(이미 있는 줄이면 고친다). **duckdns 주소를 빼지 않는다** — 빼면
지금 화면(duckdns)의 로그인이 막힌다.

```bash
CORS_ALLOWED_ORIGINS=https://artifact-prod.duckdns.org,https://<도메인>,https://<프로젝트>.vercel.app
PRINT_KIOSK_ALLOWED_BASE_URLS=https://artifact-prod.duckdns.org,https://<도메인>
# 텔레그램 접수 알림을 켜 두었다면 — 접수 화면 주소와 같아야 보낸다(docs/telegram-notification.md)
TELEGRAM_PUBLIC_BASE_URL=https://<도메인>
```

`.env` 는 컨테이너를 다시 만들어야 읽는다. GitHub → **Actions → Deploy → Run workflow**(main). 초록 체크가 뜨면 된다.

### 1-5. 확인

- `https://<도메인>` 로그인 → 접수 → **접수증 QR 이 `https://<도메인>/kiosk/...`** 인지(접수 화면의 키오스크 주소 칸을 비우면 화면 주소를 쓴다)
- 휴대폰으로 QR → 키오스크 분석 → 진료실 AI 분석 → 처방 → 증명서 → 대기 현황판(`/waiting`)
- 브라우저 개발자 도구 → 네트워크: API 요청이 `https://artifact-prod.duckdns.org/api/...` 로 가는지
- main 에 백엔드만 바꾼 커밋이 머지되면 Vercel 배포 목록에 빌드 없이 넘어간 항목이 생기는지(1-2)

여기까지가 Vercel 이다. AWS 실습을 안 하더라도 이 상태로 계속 써도 된다.

---

## 2. 보안 그룹 4개

요청이 지나가는 길을 그대로 막는다: 인터넷 → 공개 ALB → 백엔드 → 내부 ALB → AI. **각 단계는 바로 앞 단계의
보안 그룹에서 온 것만 받는다.** IP 가 아니라 보안 그룹을 소스로 적으므로 인스턴스가 늘고 줄어도 규칙을 고칠 일이 없다.

EC2 → 왼쪽 **보안 그룹** → **보안 그룹 생성**. VPC 는 지금 서버와 같은 것(기본 VPC). 아웃바운드는 기본값(전체 허용).

| 이름 | 인바운드 | 쓰는 곳 |
|---|---|---|
| `artifact-alb-public-sg` | HTTP 80 · HTTPS 443 / 소스 `0.0.0.0/0` (IPv6 쓰면 `::/0` 도) | 공개 ALB |
| `artifact-alb-ai-sg` | HTTP 80 / 소스 = 지금 서버의 보안 그룹(`artifact-sg`) | 내부 ALB |
| `artifact-ai-sg` | 사용자 지정 TCP 8000 / 소스 = `artifact-alb-ai-sg` | AI 인스턴스 |

지금 서버의 보안 그룹(`artifact-sg`)에는 규칙 하나를 **추가**한다(기존 규칙은 8 단계 뒤에 정리):

- 사용자 지정 TCP **8080** / 소스 = `artifact-alb-public-sg`

> ⚠️ 8000·8080 의 소스를 `0.0.0.0/0` 으로 두지 않는다. 시크릿 헤더·JWT 가 있어도 ALB 를 우회하는 입구를 남길 이유가 없다.

---

## 3. IAM — 키 대신 역할

이 구성에서는 **AWS 액세스 키를 어디에도 저장하지 않는다.** EC2 는 인스턴스 역할로, GitHub Actions 는 OIDC 로
그때그때 임시 자격 증명을 받는다. 정책 견본은 `deploy/aws/iam/` 에 있다 — 붙여 넣기 전에 `ACCOUNT_ID` 를
계정 ID(콘솔 오른쪽 위 계정 메뉴의 12자리)로 바꾼다.

### 3-1. AI 인스턴스 역할 `artifact-ai-instance-role`

1. IAM → **역할** → **역할 생성** → 신뢰할 수 있는 엔터티 **AWS 서비스** → 사용 사례 **EC2**
2. 권한: `AmazonSSMManagedInstanceCore` 체크(Session Manager 로 SSH 없이 접속하려고)
3. 이름 `artifact-ai-instance-role` → 생성
4. 만든 역할 → **권한 추가 → 인라인 정책 생성 → JSON** → `deploy/aws/iam/ai-instance-policy.json` 내용
   (ECR 에서 이미지 받기 + SSM 파라미터 하나 읽기) → 이름 `artifact-ai-pull-and-secret`

### 3-2. (S3 를 쓴다면) 백엔드 역할

이미지를 지금처럼 서버 디스크(`IMAGE_STORAGE_TYPE=local`)에 둔다면 건너뛴다.
S3 를 쓰면 역할 `artifact-backend-instance-role` 을 같은 방법(EC2, `AmazonSSMManagedInstanceCore`)으로 만들고 인라인 정책에
`deploy/aws/iam/backend-instance-s3-policy.json` (`BUCKET_NAME` 교체)을 넣는다. 지금 서버 → **작업 → 보안 → IAM 역할 수정**
으로 붙이고, 8 단계에서 `.env` 의 `AWS_ACCESS_KEY`·`AWS_SECRET_KEY` 를 비운다 — 키가 비어 있으면 백엔드가 역할로 S3 에
접근한다(`AwsConfig`).

### 3-3. GitHub Actions OIDC 역할 `artifact-github-deploy`

1. IAM → **자격 증명 공급자** → **공급자 추가** → **OpenID Connect**
   - 공급자 URL `https://token.actions.githubusercontent.com`, 대상 `sts.amazonaws.com` → 추가
2. IAM → **역할 생성** → **사용자 지정 신뢰 정책** → `deploy/aws/iam/github-actions-trust.json`
   - 이 저장소의 **main 브랜치**에서 돈 워크플로만 이 역할을 빌릴 수 있다. 포크·다른 브랜치의 PR 은 못 빌린다
3. 권한은 건너뛰고 이름 `artifact-github-deploy` 로 생성 → 만든 역할 → 인라인 정책 → `deploy/aws/iam/github-actions-policy.json`
   (ECR 푸시 + `artifact-ai-asg` 새로 고침)
4. 역할의 **ARN**(`arn:aws:iam::...:role/artifact-github-deploy`)을 적어 둔다

---

## 4. SSM 파라미터 — 내부 시크릿

백엔드와 AI 서버는 `INTERNAL_API_SECRET` 이 같아야 한다(다르면 모든 분석이 401). AI 인스턴스는 자동으로 생기고
사라지므로 값을 손으로 넣을 수 없다 — 부팅할 때 Parameter Store 에서 읽게 한다.

1. 지금 서버에 SSH 로 접속해 값을 확인한다(화면에 띄우기만 하고 채팅·문서에 붙이지 않는다):
   `grep ^INTERNAL_API_SECRET= ~/artifact/.env`
2. Systems Manager → **Parameter Store** → **파라미터 생성**
   - 이름 `/artifact/INTERNAL_API_SECRET`, 유형 **SecureString**(KMS 키는 기본 `alias/aws/ssm`), 값 = 위 값
3. 값을 바꿀 때는 이 파라미터와 백엔드 `.env` 를 함께 바꾸고, AI 그룹을 새로 고침한다(5-3 의 수동 실행)

---

## 5. ECR + 첫 AI 이미지

### 5-1. 저장소

ECR → **리포지토리 생성** → 프라이빗, 이름 `artifact-ai`. 만든 뒤 **수명 주기 정책** → 규칙 하나:
"이미지 개수 10개 초과 시 오래된 것 만료"(태그 상태: 모두). 배포마다 이미지가 쌓이므로 상한을 둔다.

### 5-2. 저장소 변수

GitHub 저장소 → **Settings → Secrets and variables → Actions → Variables** → **New repository variable**

| 이름 | 값 |
|---|---|
| `AWS_DEPLOY_ROLE_ARN` | 3-3 의 역할 ARN |
| `AI_ASG_NAME` | **아직 만들지 않는다**(6 단계 뒤) |

(`AWS_REGION` 기본 `ap-northeast-2`, `AI_ECR_REPOSITORY` 기본 `artifact-ai` — 이름을 바꿨을 때만 넣는다)

### 5-3. 첫 푸시

Actions → **Deploy AI** → **Run workflow** (Branch `main`). 하는 일:

1. arm64 러너(`ubuntu-24.04-arm`, 공개 저장소 무료)에서 `fastapi/` 이미지 빌드
2. 러너에서 컨테이너를 띄워 **`/health/ready` 가 200 인지**(병변·안면부 모델이 전부 로드됐는지) 확인 — 깨진 이미지는 올리지 않는다
3. ECR 에 `:<커밋>` 과 `:latest` 로 푸시
4. `AI_ASG_NAME` 이 비어 있으므로 새로 고침은 건너뛴다

ECR → `artifact-ai` 에 `latest` 가 보이면 된다(10~15분, 대부분 torch 설치).

> 이 워크플로는 `AWS_DEPLOY_ROLE_ARN` 이 없으면 아무것도 하지 않는다. 이후에는 `fastapi/` 가 바뀐 커밋이 main 에
> 들어올 때마다 저절로 돈다.

---

## 6. AI — 내부 ALB + 오토스케일링 그룹

### 6-1. 대상 그룹 `artifact-ai-tg`

EC2 → **대상 그룹** → **대상 그룹 생성**

- 대상 유형 **인스턴스**, 이름 `artifact-ai-tg`, 프로토콜 **HTTP**, 포트 **8000**, VPC 기본
- 상태 검사: 경로 **`/health/ready`**
  - 고급: 정상 임계값 2, 비정상 임계값 3, 제한 시간 5초, 간격 15초, 성공 코드 200
  - `/health` 가 아니라 `/health/ready` 인 이유: `/health` 는 기본 모델만 뜨면 200 이다. 안면부 가중치가 깨진 이미지도
    "정상"이 되어 새로 고침 때 모든 인스턴스로 퍼진다. `/health/ready` 는 모델이 전부 떠야 200 이다(`fastapi/main.py`)
- 대상 등록은 건너뛴다(오토스케일링 그룹이 넣고 뺀다) → 생성
- 만든 뒤 **속성 편집** → 등록 취소 지연(deregistration delay) **30초**. 기본 300초면 인스턴스를 뺄 때마다 5분을 기다린다.
  분석 요청은 30초 안에 끝난다

### 6-2. 내부 ALB `artifact-ai-alb`

EC2 → **로드 밸런서** → **Application Load Balancer** 생성

- 이름 `artifact-ai-alb`, 체계 **내부(Internal)**, IPv4
- VPC 기본, 매핑: 가용 영역 **2개 이상**(예: 2a, 2c)
- 보안 그룹: `artifact-alb-ai-sg` 만(default 는 빼기)
- 리스너: **HTTP 80** → 기본 작업 `artifact-ai-tg`
- 생성 뒤 **DNS 이름**(`internal-artifact-ai-alb-....ap-northeast-2.elb.amazonaws.com`)을 적어 둔다 — 8 단계의 `FASTAPI_URL`

### 6-3. 시작 템플릿 `artifact-ai-lt`

EC2 → **시작 템플릿** → **시작 템플릿 생성**

- 이름 `artifact-ai-lt`, "Auto Scaling 지침" 체크
- AMI: **Amazon Linux 2023**, 아키텍처 **64비트(Arm)**
- 인스턴스 유형 `t4g.small` (모델 두 개 + 스왑 2GB. 메모리가 모자라면 `t4g.medium`)
- 키 페어: 없음(접속은 Session Manager). 꼭 SSH 를 쓰려면 지금 키를 고른다
- 네트워크: 서브넷은 고르지 않는다(그룹이 고른다), 보안 그룹 `artifact-ai-sg`
  - 기본 VPC 의 서브넷은 공인 IP 를 자동으로 붙인다. 그래야 NAT 게이트웨이(월 $40 이상) 없이 ECR 에서 이미지를 받는다.
    인바운드는 보안 그룹이 내부 ALB 에서 온 8000 만 받으므로, 공인 IP 가 있어도 밖에서 들어올 수 없다
- 스토리지: **16 GiB gp3** (기본 8GB 는 도커 이미지 + 스왑에 모자라다)
- 고급 세부 정보
  - IAM 인스턴스 프로파일: `artifact-ai-instance-role`
  - 메타데이터 버전: **V2 전용(토큰 필수)** — 시작 스크립트가 IMDSv2 로 리전·계정을 읽는다
  - 사용자 데이터: `deploy/aws/ai-user-data.sh` 내용 전체를 붙여 넣는다(이름을 바꿨으면 맨 위 세 줄만 고친다)

### 6-4. 오토스케일링 그룹 `artifact-ai-asg`

EC2 → **Auto Scaling 그룹** → **생성**

1. 시작 템플릿 `artifact-ai-lt`, 버전 **Latest**
2. VPC 기본, 가용 영역 = 6-2 의 ALB 와 같은 영역들
3. 로드 밸런싱: **기존 로드 밸런서에 연결** → 대상 그룹 `artifact-ai-tg`
   - 상태 확인: **Elastic Load Balancing 상태 확인 켜기**, 유예 기간 **300초**
     (부팅 → 도커 설치 → 이미지 받기 → 모델 로드에 2~4분 걸린다. 그 전에 비정상으로 판정하면 끝없이 갈아 끼운다)
4. 그룹 크기: 원하는 용량 **2**, 최소 **1**, 최대 **4**
5. 크기 조정: **대상 추적 크기 조정 정책** → 지표 **평균 CPU 사용률**, 대상 값 **60**, 인스턴스 워밍업 300초
6. 생성 → 2~5분 뒤 `artifact-ai-tg` → **대상** 탭에 인스턴스 2개가 **healthy**

> 안 될 때: 대상이 unhealthy 로 반복 교체되면 인스턴스 하나를 골라 **연결 → Session Manager** →
> `sudo tail -50 /var/log/artifact-ai-user-data.log`. 흔한 원인은 역할 권한(ECR·SSM), 파라미터 이름 오타,
> 5 단계 이미지 없음이다. 컨테이너 로그는 `sudo docker logs --tail 50 artifact-ai`.

### 6-5. 백엔드 서버에서 닿는지 확인

지금 서버에 SSH 로 접속해서:

```bash
curl -s http://<6-2 의 DNS 이름>/health/ready
# {"status":"ready","tasks":["facial","skin_lesion"]} 이면 된다
```

`artifact-alb-ai-sg` 가 지금 서버의 보안 그룹만 받으므로, 이 서버에서만 닿고 인터넷에서는 닿지 않는다.

### 6-6. 자동 롤링 배포 켜기

저장소 변수 `AI_ASG_NAME` = `artifact-ai-asg` 추가. 이제 `fastapi/` 가 바뀌면 Deploy AI 가 이미지를 올린 뒤
**인스턴스 새로 고침**을 시작한다. 2대면 한 대씩(최소 정상 50%) 새 인스턴스로 바꾸고, 새 인스턴스가 `/health/ready` 를
통과하지 못하면 새로 고침이 실패로 끝나 옛 인스턴스가 그대로 일한다.

---

## 7. 백엔드 앞에 공개 ALB — DNS 레코드는 Vercel 에서

도메인을 Vercel 에서 샀으므로 DNS 도 Vercel 이 관리한다. AWS 쪽에 필요한 레코드 두 개(인증서 확인용, `api` 주소)를
**Vercel → Domains → `<도메인>` → DNS Records → Add** 로 넣는다. Route 53 은 쓰지 않는다.

### 7-1. 인증서 (ACM)

1. Certificate Manager(**서울 리전** — ALB 와 같은 리전이어야 한다) → **인증서 요청** → 퍼블릭 →
   도메인 `api.<도메인>` → **DNS 검증** → 요청
2. 만든 인증서를 열면 **CNAME 이름**과 **CNAME 값**이 보인다
   - 이름 예: `_3f1c2a….api.artifact-emr.com.` / 값 예: `_9b7d….xxxx.acm-validations.aws.`
3. Vercel → Domains → `<도메인>` → **Add Record**
   - Type **CNAME**
   - Name: CNAME 이름에서 **뒤의 `.<도메인>.` 을 뺀 부분** — 예) `_3f1c2a….api`
   - Value: CNAME 값 그대로(끝의 `.` 은 빼도 된다)
   - TTL 기본값 → 저장
4. 5~30분 뒤 ACM 상태가 **발급됨(Issued)**. 이 레코드는 지우지 않는다 — 인증서 자동 갱신에 쓰인다

### 7-2. 대상 그룹 `artifact-backend-tg`

- 인스턴스, HTTP **8080**, VPC 기본
- 상태 검사 경로 **`/api/health`**(로그인 없이 `{"status":"UP"}` — `HealthController`), 성공 코드 200, 간격 15초
- 대상 등록: **지금 서버**를 포트 8080 으로 포함 → 생성 → 등록 취소 지연 30초
- 지금은 **unhealthy 가 정상**이다. 한 서버 구성에서는 백엔드 8080 을 호스트에 열지 않는다. 8 단계 뒤 healthy 가 된다

### 7-3. 공개 ALB `artifact-api-alb`

- 이름 `artifact-api-alb`, 체계 **인터넷 경계(Internet-facing)**, IPv4
- 가용 영역 2개 이상 — **지금 서버가 있는 영역을 반드시 포함**(인스턴스 세부 정보의 가용 영역)
- 보안 그룹 `artifact-alb-public-sg`
- 리스너
  - **HTTPS 443** → `artifact-backend-tg`, 인증서 7-1, 보안 정책은 기본값(TLS 1.3 포함)
  - **HTTP 80** → 작업 **URL로 리디렉션** → HTTPS 443, 301
- 유휴 제한 시간은 기본 60초 그대로(LLM 대체 사슬이 30초 안에 끝난다 — README "AI 처방 코멘트")
- 생성 뒤 **DNS 이름**(`artifact-api-alb-….ap-northeast-2.elb.amazonaws.com`)을 적어 둔다

### 7-4. `api.<도메인>` 연결

Vercel → Domains → `<도메인>` → **Add Record**

- Type **CNAME**, Name `api`, Value = 7-3 의 ALB DNS 이름 → 저장

1~5분 뒤 `https://api.<도메인>/api/health` 는 아직 **503** 이다(대상이 unhealthy — 8 단계 전이라 정상). 인증서 오류 없이
503 이 보이면 DNS·인증서·ALB 까지는 맞다.

> ALB 의 IP 는 바뀐다. A 레코드에 IP 를 적지 말고 반드시 CNAME 으로 DNS 이름을 가리킨다.

---

## 8. 넘어가기 (cut-over)

**접수가 없는 시간에 한다.** 8-4 의 백엔드 배포부터 8-5 의 Vercel 재배포가 끝날 때까지 2~4분 동안 로그인이 안 된다
(그 사이 화면은 아직 옛 API 주소를 부르는데, 옛 서버 구성은 내려간다).

### 8-1. 백엔드 `.env`

지금 서버에 SSH 로 접속해 `~/artifact/.env` 를 고친다(`.env.production.example` 맨 아래 절 참고):

```bash
FASTAPI_URL=http://<6-2 의 DNS 이름>
CORS_ALLOWED_ORIGINS=https://<도메인>
PRINT_KIOSK_ALLOWED_BASE_URLS=https://<도메인>
# 텔레그램 알림을 켜 두었다면
TELEGRAM_PUBLIC_BASE_URL=https://<도메인>
# 3-2 의 역할을 붙였다면 두 키를 비운다
AWS_ACCESS_KEY=
AWS_SECRET_KEY=
```

미리보기(`*.vercel.app`)에서도 로그인하려면 `CORS_ALLOWED_ORIGINS` 에 쉼표로 더한다.

### 8-2. Vercel 의 API 주소 바꿔 두기 (아직 재배포하지 않는다)

Vercel → 프로젝트 → **Settings → Environment Variables** → `VITE_API_BASE_URL` 편집 → `https://api.<도메인>` → Save.
저장만 한다 — 지금 재배포하면 백엔드가 아직 ALB 뒤에 없어 화면이 먼저 깨진다.

### 8-3. 저장소 변수

| 이름 | 값 |
|---|---|
| `DEPLOY_TARGET` | `elb` |
| `API_URL` | `https://api.<도메인>` |

### 8-4. 백엔드 배포

Actions → **Deploy** → **Run workflow** (main). 로그에서 확인할 것:

- "ELB 구성으로 배포 (DEPLOY_TARGET=elb) — 이 서버는 MySQL + 백엔드만"
- 화면·AI·Caddy 컨테이너(`artifact-frontend`·`artifact-fastapi`·`artifact-caddy`)가 지워지고 `artifact-mysql`·`artifact-backend` 만 남는다
- 헬스체크가 `https://api.<도메인>/api/health` 와 로그인 요청(4xx)을 **ALB 를 거쳐** 받는다 → ✅

`.env` 의 세 값 중 하나라도 비면 compose 가 기동 전에 멈추고 무엇이 빠졌는지 적는다.

### 8-5. Vercel 재배포

Vercel → **Deployments** → 가장 위 Production 배포의 `⋯` → **Redeploy** → 1~2분. 끝나면 화면이 `https://api.<도메인>` 을 부른다.

### 8-6. 확인

- `artifact-backend-tg` → 대상 **healthy**
- `https://<도메인>` 로그인 → 접수 → 접수증 QR → 키오스크(휴대폰) 분석 → 진료실 AI 분석 → 처방 → 증명서 → 대기 현황판
- 개발자 도구 → 네트워크: API 요청이 `https://api.<도메인>/api/...` 로 가는지
- AI 요청이 두 인스턴스로 나뉘는지: EC2 → 대상 그룹 `artifact-ai-tg` → **모니터링**의 요청 수, 또는 각 인스턴스의
  `sudo docker logs --tail 5 artifact-ai`

### 8-7. 정리

- 지금 서버 보안 그룹(`artifact-sg`)에서 **80·443 인바운드 삭제**(Caddy 가 없다). 22 는 내 IP 로만 두거나,
  Session Manager 를 쓰려면 서버에 `AmazonSSMManagedInstanceCore` 가 든 역할을 붙이고 지운다
- DuckDNS 주소는 더 이상 열리지 않는다. 남겨 둔 인쇄물 QR(옛 주소)도 열리지 않는다
- 부스 때 접수 데스크 맥에서 돌던 print-agent 를 다시 쓴다면 그 `.env` 의 `BACKEND_URL` 을 `https://api.<도메인>`,
  `KIOSK_BASE_URL` 을 `https://<도메인>` 으로 바꾼다(`print-agent/config.py`)

---

## 9. 오토스케일링 실습 — 부하를 걸어 늘고 줄어드는 것 보기

`scripts/booth/kiosk_load_check.py` 는 키오스크 여러 대가 동시에 분석하는 상황을 만든다. API 주소만 있으면 된다.
접수를 만들었다가 끝나면 취소한다(접수증 출력 작업이 생기지만 print-agent 가 없으면 2분 뒤 버려진다).

```bash
export BOOTH_LOGIN_ID=<관리자 아이디> BOOTH_PASSWORD='<비밀번호>'   # 셸 기록에 남지 않게 환경변수로
python3 scripts/booth/kiosk_load_check.py --base-url https://api.<도메인> \
    --images <사진 폴더(서로 다른 사진 6장 이상)> --devices 6 --rounds 40 --task skin_lesion
```

보는 곳:

1. EC2 → Auto Scaling 그룹 → `artifact-ai-asg` → **모니터링**: 평균 CPU 가 60% 를 몇 분 넘으면
2. **활동** 탭에 "Launching a new EC2 instance" — 원하는 용량이 3, 4 로 늘어난다(최대 4)
3. 새 인스턴스는 대상 그룹에서 initial → healthy 가 된 뒤에야 요청을 받는다(2~4분 — 시작 스크립트 + 모델 로드)
4. 부하를 멈추고 15분쯤 지나면 축소 알람으로 다시 2 로 줄어든다(최소 1, 원하는 용량은 정책이 정한다)
5. CloudWatch → **경보**에서 대상 추적 정책이 만든 `TargetTracking-artifact-ai-asg-AlarmHigh/Low` 를 볼 수 있다

해 볼 만한 것:

- 인스턴스 하나를 콘솔에서 **종료** → 그룹이 알아서 새로 띄워 원하는 용량을 맞춘다(자가 치유)
- `fastapi/` 를 조금 고쳐 머지 → Deploy AI 의 인스턴스 새로 고침이 한 대씩 갈아 끼우는 동안 분석이 끊기지 않는지
- 원하는 용량을 0 으로 → 진료실 AI 분석이 "AI 서버" 오류를 내는지(백엔드는 503 을 받는다) → 다시 2 로

> `t4g` 는 버스트형이다. 계속 100% 로 돌리면 CPU 크레딧을 다 쓰고, 기본 "무제한" 모드에서는 초과분이 조금 과금된다.
> 실습은 10~20분 단위로 끊는다.

---

## 10. 되돌리기

### 한 서버 구성으로 (화면은 Vercel 그대로)

1. 지금 서버 보안 그룹에 80·443 인바운드를 다시 연다, DuckDNS 의 IP 가 이 서버인지 확인한다
2. 서버 `.env` 의 `CORS_ALLOWED_ORIGINS`·`PRINT_KIOSK_ALLOWED_BASE_URLS` 를 1-4 의 값(duckdns + `<도메인>`)으로 되돌린다
3. 저장소 변수 `DEPLOY_TARGET` 을 지운다(또는 빈 값) → Actions → **Deploy** → Run workflow — 화면·AI·Caddy 가 이 서버에 다시 뜬다
4. Vercel 의 `VITE_API_BASE_URL` 을 `https://artifact-prod.duckdns.org` 로 바꾸고 **Redeploy**

### AI 이미지만 이전 것으로

ECR 에는 커밋별 태그가 남아 있다. 되돌릴 커밋 `<sha>` 의 이미지를 `latest` 로 다시 붙이고 새로 고침한다:

```bash
MANIFEST=$(aws ecr batch-get-image --repository-name artifact-ai --image-ids imageTag=<sha> \
             --query 'images[0].imageManifest' --output text)
aws ecr put-image --repository-name artifact-ai --image-tag latest --image-manifest "$MANIFEST"
aws autoscaling start-instance-refresh --auto-scaling-group-name artifact-ai-asg \
  --preferences '{"MinHealthyPercentage":50,"InstanceWarmup":120}'
```

### 화면만 이전 배포로

Vercel → Deployments → Production 배포의 `⋯` → **Instant Rollback**. Hobby 플랜은 바로 이전 Production 배포로만 되돌릴 수 있다
(더 예전 배포를 고르는 롤백은 Pro 이상). 더 뒤로 가야 하면 그 커밋을 되돌리는 커밋을 main 에 머지한다.

---

## 11. 정리 — AWS 실습이 끝나면 (요금 멈추기)

지우는 순서가 있다(앞의 것이 뒤의 것을 붙잡고 있다):

1. 10 단계로 한 서버 구성에 돌아간다(서비스를 계속 쓸 거라면)
2. Auto Scaling 그룹 `artifact-ai-asg` 삭제 — 인스턴스가 함께 종료된다
3. 로드 밸런서 `artifact-ai-alb`, `artifact-api-alb` 삭제 → 대상 그룹 두 개 삭제
4. 시작 템플릿 `artifact-ai-lt` 삭제(요금은 없지만 정리)
5. 보안 그룹 `artifact-ai-sg`·`artifact-alb-ai-sg`·`artifact-alb-public-sg` 삭제, `artifact-sg` 에서 8080 규칙 삭제
6. ECR `artifact-ai` 이미지(또는 저장소) 삭제 — 저장 용량만큼 과금
7. ACM 인증서 삭제, Vercel DNS 에서 `api` 와 인증서 확인용(`_…api`) 레코드 삭제
8. 저장소 변수 `AI_ASG_NAME` 삭제(남겨 두면 Deploy AI 가 없는 그룹을 새로 고치려다 실패한다).
   `AWS_DEPLOY_ROLE_ARN` 까지 지우면 Deploy AI 가 아예 돌지 않는다
9. Vercel 프로젝트와 도메인은 그대로 둬도 된다(Hobby 무료, 도메인은 연 단위)
10. 며칠 뒤 결제 및 비용 관리 → **청구서**에서 ELB·EC2 항목이 더 늘지 않는지 본다

---

## 12. 자주 막히는 곳

| 증상 | 원인 | 할 일 |
|---|---|---|
| Vercel Import 목록에 저장소가 없다 | Vercel GitHub 앱이 CSID-DGU 조직에 설치·승인되지 않음 | 1-1 의 Adjust GitHub App Permissions. 조직 소유자가 승인해야 한다 |
| Vercel 빌드가 `vite` 를 못 찾거나 `package.json` 이 없다 | Root Directory 가 저장소 루트 | Settings → Build and Deployment → Root Directory 를 `frontend` 로 → Redeploy |
| 화면을 고쳐 머지했는데 Vercel 이 빌드를 건너뜀 | `ignoreCommand` 가 `frontend/` 변경을 못 봄(드묾) | Deployments → 그 배포 `⋯` → Redeploy(건너뛰기 없이 빌드). 계속되면 `frontend/vercel.json` 의 `ignoreCommand` 줄을 지운다 — 이 값이 프로젝트 설정의 Ignored Build Step 보다 우선한다 |
| 환경변수를 바꿨는데 화면이 옛 API 를 부름 | `VITE_API_BASE_URL` 은 빌드할 때 굳는다 | Redeploy |
| Vercel 화면은 뜨는데 로그인하면 "서버에 연결할 수 없습니다" | API 주소가 틀렸거나 API 쪽(서버·ALB) 문제 | 개발자 도구 → 네트워크에서 로그인 요청 주소 확인. 주소가 맞으면 아래 CORS·503 행 |
| 콘솔에 `CORS policy` 오류, 응답 403 `Invalid CORS request` | 백엔드 `CORS_ALLOWED_ORIGINS` 에 화면 주소가 정확히 없음(스킴·호스트, 끝 `/` 없이) | `.env` 를 고치고 Deploy 를 다시 돌린다(컨테이너를 새 값으로 다시 만든다) |
| ACM 이 계속 "검증 대기 중" | Vercel DNS 레코드 이름에 도메인을 한 번 더 붙임(`_x.api.<도메인>.<도메인>`) | Name 에는 `_x.api` 까지만. Vercel 이 뒤에 `<도메인>` 을 붙인다 |
| `api.<도메인>` 이 503 | 백엔드 대상 그룹에 healthy 대상이 없다 | 서버에서 `curl localhost:8080/api/health`. 200 이면 보안 그룹(8080 ← `artifact-alb-public-sg`)·대상 등록 포트, 아니면 `docker logs artifact-backend` |
| AI 분석만 실패(502/503, "AI 서버") | 내부 ALB 뒤에 healthy AI 가 없다 | 6-4 "안 될 때". 백엔드 로그에 401 이면 SSM 파라미터와 `.env` 의 `INTERNAL_API_SECRET` 이 다르다 |
| 인스턴스가 계속 새로 생겼다 사라진다 | 유예 기간 안에 `/health/ready` 를 통과하지 못함 | 시작 스크립트 로그. 메모리 부족(OOM)이면 `t4g.medium` 으로 시작 템플릿 새 버전 → 새로 고침 |
| Deploy AI 의 새로 고침이 실패 | 새 이미지의 모델이 전부 뜨지 않음(`/health/ready` 503) | 실패해도 옛 인스턴스가 남아 서비스는 그대로다. 대상 그룹의 비정상 사유와 인스턴스 로그로 원인을 고쳐 다시 머지 |
| Deploy AI 가 `Not authorized to perform sts:AssumeRoleWithWebIdentity` | 신뢰 정책의 저장소·브랜치가 다름, 또는 OIDC 공급자 없음 | 3-3. main 이 아닌 브랜치에서 수동 실행해도 이 오류다 |
| 접수증 QR 이 옛 주소 | `PRINT_KIOSK_ALLOWED_BASE_URLS`, 또는 접수 화면에 저장해 둔 키오스크 주소 | `.env` 확인. 접수 화면의 키오스크 주소 칸을 비우면 화면 주소(`<도메인>`)를 쓴다 |
