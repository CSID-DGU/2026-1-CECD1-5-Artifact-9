# AWS 아키텍처 재설계 및 콘솔 단계별 마이그레이션 가이드

> 현재: EC2 1대에 도커 컴포즈 전부 (`docs/ec2-deployment-guide.md`)
> 목표: 정적 호스팅 분리 + AI 추론 계층 수평 확장 + 로드밸런서 + Auto Scaling Group
> 리전: **ap-northeast-2 (서울)** 기준 · 작성일 2026-09-17

---

# 파트 1 — 아키텍처 설계

## 1-1. 지금 구조와 그 한계

```
                    인터넷
                       │
              ┌────────▼────────┐
              │  EC2 t4g.small  │  퍼블릭 IP 1개
              │  (2 vCPU / 2GB) │  artifact-prod.duckdns.org
              │                 │
              │  caddy   :443   │ ← TLS 종료
              │  frontend:80    │ ← nginx, SPA + /api 프록시
              │  backend :8080  │ ← Spring Boot
              │  fastapi :8000  │ ← PyTorch 추론
              │  mysql   :3306  │
              └─────────────────┘
                   swap 4GB
```

**뭐가 문제인가.**

| 증상 | 원인 |
|---|---|
| 추론 1건이 돌면 화면 전체가 느려짐 | PyTorch가 같은 2 vCPU를 놓고 Spring·MySQL과 경쟁 |
| 부스에서 키오스크 3대가 동시에 촬영하면 대기 | `_model_lock`으로 추론이 직렬화됨. 워커가 1개뿐 |
| 인스턴스가 죽으면 서비스 전체 정지 | 단일 장애점. 자동 복구 없음 |
| 정적 파일(JS/CSS/이미지)까지 EC2가 서빙 | CPU·대역폭 낭비. CDN 캐시 없음 |
| 배포 중 다운타임 | `docker compose up -d --build` 하는 동안 서비스 중단 |
| 부스 워커 3개를 못 켬 | 2GB에 PyTorch 프로세스 3개가 안 들어감 |

**핵심은 이것이다** — 이 시스템에서 무거운 건 AI 추론 하나뿐인데,
그게 다른 전부와 자원을 공유하고 있다. **추론만 떼어내서 옆으로 늘리면 된다.**

---

## 1-2. 추천 아키텍처

```
                          사용자 브라우저
                                │
                    ┌───────────▼───────────┐
                    │      CloudFront       │  ← 단일 진입점 (HTTPS)
                    │   d1234.cloudfront.net│
                    └───────┬───────┬───────┘
                            │       │
         ┌──────────────────┘       └──────────────────┐
         │ 기본 동작(*)                    /api/*        │
         │                                              │
    ┌────▼─────┐                              ┌─────────▼────────┐
    │    S3    │                              │   ALB (public)   │
    │ 정적 버킷 │                              │  artifact-alb    │
    │ SPA 빌드  │                              └─────────┬────────┘
    │ (OAC 전용)│                                        │
    └──────────┘                              ┌─────────▼────────┐
     퍼블릭 접근 차단                           │ TG: backend-tg   │
                                              │  :8080 /actuator │
                                              └─────────┬────────┘
                                                        │
  ═══════════════ VPC 10.0.0.0/16 ══════════════════════│═══════════
                                                        │
   [ 퍼블릭 서브넷 2a / 2c ]  ALB, NAT                   │
   ───────────────────────────────────────────────────  │
   [ 프라이빗 앱 서브넷 2a / 2c ]                        │
                                              ┌─────────▼────────┐
                                              │  ASG: backend    │
                                              │  min=1 max=1     │ ← 자가복구 전용
                                              │  t4g.small       │   (§1-4 참고)
                                              │  Spring Boot     │
                                              └────┬────────┬────┘
                                                   │        │
                             ┌─────────────────────┘        │
                             │ FASTAPI_URL                  │ JDBC
                  ┌──────────▼──────────┐                   │
                  │  ALB (internal)     │                   │
                  │  artifact-ai-alb    │ ← 여기가 ai-lb 대체 │
                  └──────────┬──────────┘                   │
                             │                              │
                  ┌──────────▼──────────┐                   │
                  │  TG: ai-tg :8000    │                   │
                  │      /health        │                   │
                  └──────────┬──────────┘                   │
                             │                              │
                  ┌──────────▼──────────┐                   │
                  │  ASG: ai-workers    │                   │
                  │  min=1 desired=1    │ ← 실제 수평 확장   │
                  │  max=4  t4g.medium  │                   │
                  │  ┌────┐ ┌────┐ ┌────┐                   │
                  │  │ AI │ │ AI │ │ AI │ ... 부하 따라 증감  │
                  │  └────┘ └────┘ └────┘                   │
                  └─────────────────────┘                   │
   ───────────────────────────────────────────────────      │
   [ 프라이빗 DB 서브넷 2a / 2c ]                            │
                  ┌─────────────────────────────────────────▼─┐
                  │  RDS MySQL 8.0  db.t4g.micro  Multi-AZ off│
                  └───────────────────────────────────────────┘
  ═══════════════════════════════════════════════════════════════

   부가: ECR(이미지) · SSM Session Manager(접속, 22번 포트 없음)
        CloudWatch(지표·알람) · S3(진단 이미지 저장, 선택)
```

### 이 그림의 설계 판단 6가지

**① CloudFront가 `/api/*`까지 받는다 — CORS 작업이 0이 되기 때문이다.**

프론트는 이미 상대경로로 API를 부른다 — 환경변수가 아니라 **코드에 박혀 있다**:

```ts
// frontend/src/api/analysis.ts 등, src/api/*.ts 전부 같은 형태
apiRequest<AnalysisResponse>(`/api/v1/visits/${visitId}/analysis`)
```

`.env.production` 에 `VITE_API_BASE_URL=/api` 가 적혀 있지만 **코드가 읽지 않는
죽은 값**이다(`src/vite-env.d.ts` 에 선언조차 없다). 실제로 살아 있는 변수는
`VITE_KIOSK_BASE_URL` 하나뿐이고, 비워두면 현재 호스트를 쓴다.

CloudFront 하나가 `/`(→S3)와 `/api/*`(→ALB)를 **같은 도메인**으로 내보내면
브라우저 입장에서는 여전히 same-origin이다. 프론트 코드도, `CORS_ALLOWED_ORIGINS`도,
QR에 찍히는 `PRINT_KIOSK_ALLOWED_BASE_URLS`도 도메인만 바꾸면 끝이다.

> S3를 따로 도메인으로 띄우고 ALB를 다른 도메인으로 두면 CORS 설정 + 프리플라이트 +
> 쿠키 SameSite까지 전부 손봐야 한다. **그 작업을 안 하려고 CloudFront를 앞에 세운다.**

**② AI만 ASG로 늘린다.**

병목이 거기 하나다. `fastapi/main.py`의 `_model_lock`이 한 프로세스당 추론 1건을
직렬화하므로, 동시 처리량을 늘리는 방법은 **프로세스(=인스턴스)를 늘리는 것뿐**이다.
로컬에서 워커 3개를 띄워본 `docker-compose.booth.yml`이 정확히 그 발상이었고,
ASG는 그걸 **부하에 따라 자동으로** 하는 것이다.

**③ AI 앞은 내부(internal) ALB다.**

AI 서버는 인터넷에서 보이면 안 된다. `INTERNAL_API_SECRET`으로 보호되긴 하지만,
애초에 VPC 밖에서 닿지 않는 게 맞다. 백엔드만 `FASTAPI_URL=http://artifact-ai-alb-…:8000`으로
부른다.

**④ `docker/ai-lb/nginx.conf`는 버린다.** — §1-5 참고

**⑤ 백엔드 ASG는 min=max=1이다.** — §1-4 참고. **여기가 이 문서에서 제일 중요한 절이다.**

**⑥ RDS로 DB를 뺀다.**

EC2 안의 MySQL 컨테이너는 인스턴스가 교체되면 데이터가 사라진다. ASG는 인스턴스를
**언제든 버리고 새로 만드는 것**을 전제하므로, 상태가 있는 것은 전부 밖으로 나가야 한다.
RDS로 빼면 자동 백업(7일)과 스냅샷도 따라온다 — 현재 백업이 아예 없는 상태를 같이 해결한다.

---

## 1-3. 요청이 흐르는 경로 (3가지 시나리오)

**A. 의사가 화면을 연다**
```
브라우저 → CloudFront → (캐시 히트) → index.html/JS/CSS 반환
                         캐시 미스 → S3 → 캐시 저장 후 반환
```
EC2는 관여하지 않는다.

**B. 의사가 진단 이미지를 업로드해 분석한다**
```
브라우저 POST /api/visits/12/analysis
  → CloudFront (캐시 안 함, 전체 전달)
  → 퍼블릭 ALB → backend-tg → 백엔드 EC2 :8080
  → 백엔드가 내부 ALB 호출 → ai-tg → AI EC2 중 1대 :8000
  → 추론 + Grad-CAM → 백엔드 → RDS 저장 → 응답
```
AI 인스턴스가 3대면 세 요청이 **동시에** 처리된다. 이게 이 마이그레이션의 목적이다.

**C. 부스에서 키오스크 3대가 동시에 촬영한다**
```
태블릿 3대 → /api/kiosk/session/{token}/analyze  (각각)
  → ... → 내부 ALB 라운드로빈 → AI-1, AI-2, AI-3
```
`docker-compose.booth.yml`이 하려던 것과 **결과가 같고**, 수동 설정이 없다.

---

## 1-4. ⚠️ 백엔드를 늘릴 수 없는 이유 3가지

> **이 절을 건너뛰고 백엔드 ASG의 desired를 2로 올리면 서비스가 조용히 깨진다.**
> 에러가 안 나고 "가끔 인쇄가 안 되고 가끔 QR이 안 열리는" 형태로 나타나서 원인을 찾기 어렵다.

### 블로커 ①: 인쇄 작업 큐가 메모리에 있다

`backend/.../print/PrintJobQueue.java` — JVM 힙 안의 큐다.

```
[접수 데스크 맥북 print-agent]
      │ GET /api/print/jobs/next  (롱폴링 25초)
      ▼
백엔드가 2대라면?
  → 에이전트는 ALB가 붙여준 한쪽에만 물려 있다
  → 인쇄 버튼을 반대쪽 인스턴스가 받으면 작업은 그 인스턴스 큐에 쌓이고
  → 아무도 가져가지 않은 채 120초 뒤 TTL로 조용히 버려진다
  → 사용자에게는 "프린터가 연결되어 있지 않습니다"만 뜬다
```

**해결하려면**: 큐를 SQS 또는 Redis(ElastiCache)로 옮긴다. `PrintTransport` 인터페이스가
이미 구현 교체를 전제하고 있어 구조 변경은 크지 않지만, **이 문서 범위 밖이다.**

### 블로커 ②: QR 문서 열람 잠금 카운터가 메모리에 있다

`backend/.../docshare/ShareAccessGuard.java` — `MAX_FAILURES=5 / WINDOW=10분`을
인스턴스별 `ConcurrentHashMap`으로 센다. 소스 주석에도 명시돼 있다.

```
백엔드 2대 → 공격자가 얻는 시도 횟수: 5회가 아니라 10회
백엔드 N대 → 5N회
```

증명서 열람의 두 번째 요소가 **생년월일(6자리)**이므로, 이 잠금이 사실상 유일한 방어선이다.
여기가 배수로 약해지는 건 받아들일 수 없다.

**해결하려면**: ElastiCache Redis로 카운터를 옮긴다 (`INCR` + `EXPIRE`).

### 블로커 ③: 업로드 이미지가 로컬 디스크에 있다

`IMAGE_STORAGE_TYPE=local` (기본값) — EC2 파일시스템에 저장한다.

```
업로드를 인스턴스 A가 받음 → A의 디스크에 저장
조회를 인스턴스 B가 받음   → 파일 없음 → 404
그리고 ASG가 A를 교체하면 → 이미지 영구 소실
```

**해결하려면**: `IMAGE_STORAGE_TYPE=s3`. 이건 셋 중 가장 쉽고, **백엔드를 1대로 두더라도
반드시 해야 한다** — ASG는 인스턴스를 언제든 교체하기 때문이다.

### 그래서 이번 마이그레이션의 범위

| 계층 | ASG 설정 | 얻는 것 |
|---|---|---|
| **AI 추론** | min=1 / desired=1 / **max=4** | 진짜 수평 확장. 이번 목표 |
| **백엔드** | **min=1 / desired=1 / max=1** | 자가 복구 + 무중단 인스턴스 교체 (확장은 아님) |

백엔드 ASG가 1대여도 얻는 게 있다:

- 인스턴스가 죽거나 헬스체크에 실패하면 **자동으로 새 인스턴스로 교체**된다
- Launch Template 버전을 올리고 Instance Refresh를 돌리면 **배포가 인스턴스 교체**가 된다
- 가용 영역 하나가 통째로 죽어도 다른 AZ에서 다시 뜬다

> 이것도 실무 패턴이다. **"수평 확장 불가"와 "ASG 불필요"는 다른 얘기다.**

---

## 1-5. 로컬에 있는 `booth.yml` / `ai-lb` 변경분을 어떻게 할 것인가

현재 스테이징된 파일:

```
A  docker-compose.booth.yml      AI 워커 3개 + nginx 로드밸런서
A  docker/ai-lb/nginx.conf       least_conn 업스트림 3개
M  docker-compose.prod.yml       booth.yml 사용법 주석 추가
M  docker-compose.yml
```

### 판정: **두 상태가 공존해야 한다. 지우지 않는다.**

| 파일 | AWS 이행 후 | 이유 |
|---|---|---|
| `docker-compose.booth.yml` | **유지** | 부스 시연은 EC2 1대 + 도커로 계속 돌릴 수 있다. AWS 마이그레이션이 완료될 때까지의 유일한 다중 워커 수단이고, 마이그레이션이 늦어지거나 비용 때문에 중단돼도 부스는 돌아가야 한다 |
| `docker/ai-lb/nginx.conf` | **유지(도커 경로 전용)** | 위와 한 세트 |
| `docker-compose.prod.yml` 주석 | **유지 + 한 줄 추가** | "AWS ASG 경로에서는 이 파일 대신 내부 ALB를 쓴다"는 안내 |

**AWS 경로에서는 둘 다 로드되지 않는다.** ASG 인스턴스는 `docker-compose.yml`을 통째로
쓰는 게 아니라 **FastAPI 컨테이너 하나만** 띄우기 때문이다 (§2-6 user-data 참고).
그러니 지울 이유가 없다 — 서로 다른 배포 경로에 속한 파일일 뿐이다.

### 다만 한 가지는 정리한다

`docker-compose.booth.yml`의 백엔드 오버라이드:

```yaml
backend:
  environment:
    FASTAPI_URL: http://ai-lb:8000
```

AWS 경로에서 `FASTAPI_URL`은 내부 ALB DNS가 되어야 하므로, 이 값이 **환경변수로
주입 가능한 형태**인지만 확인하면 된다. `docker-compose.yml` 기본값이 이미
`FASTAPI_URL: http://fastapi:8000` 고정이므로, ASG user-data에서는 컴포즈를 쓰지 않고
`docker run -e FASTAPI_URL=...`로 직접 넣는다 — 충돌 없음.

> **지금 코드를 고칠 필요는 없다.** §2-6·§2-9의 user-data 스크립트가 환경변수를
> 주입하는 방식으로 처리한다.

---

## 1-6. 비용 — 먼저 읽을 것 ⚠️

이 아키텍처는 **실습·학습 가치는 높지만 캡스톤 예산으로는 비싸다.** 솔직한 추정:

### 정석 구성 (위 그림 그대로)

| 항목 | 사양 | 월 비용(USD, 서울) |
|---|---|---:|
| ALB (퍼블릭) | 1개 | ~$18 |
| ALB (내부) | 1개 | ~$18 |
| **NAT Gateway** | 1개 + 데이터 처리 | **~$35+** |
| EC2 백엔드 | t4g.small × 1 | ~$13 |
| EC2 AI | t4g.medium × 1 (평시) | ~$26 |
| RDS MySQL | db.t4g.micro + 20GB | ~$16 |
| S3 + CloudFront | 소규모 | ~$2 |
| ECR | 1GB | ~$0.1 |
| **합계 (평시)** | | **≈ $128 / 월** |
| 부하 시 AI 4대 | +3 × $26 | +$78 |

**약 17만원/월.** 프리티어가 남아 있어도 ALB와 NAT Gateway는 프리티어가 없다.

### 절감 구성 (실습 목적이면 이쪽을 권한다)

| 바꾸는 것 | 절감 | 잃는 것 |
|---|---:|---|
| NAT Gateway → **NAT 인스턴스 (t4g.nano)** | −$31 | 관리 부담, 단일 장애점 |
| NAT 생략 → **AI/백엔드를 퍼블릭 서브넷 + SG 차단** | −$35 | "프라이빗 서브넷" 실습 경험 |
| 내부 ALB 생략 → **AI를 백엔드와 같은 인스턴스 그룹** | −$18 | 수평 확장 자체가 사라짐 (권장 안 함) |
| AI 인스턴스 t4g.medium → **t4g.small** | −$13/대 | 추론 지연 증가 (모델 16MB라 가능은 함) |
| RDS → EC2 MySQL 유지 | −$16 | 자동 백업, ASG 안전성 |
| **실습 후 즉시 삭제** | — | 가장 확실한 절감 |

> **강력 권고**: 구성 → 검증 → 스크린샷/기록 → **삭제**. 상시 운영이 목적이 아니라면
> 하루 이틀 안에 끝내고 지우면 $5~10 수준이다. §3에 삭제 순서를 정리해 뒀다.

**시작 전에 예산 알림부터 만든다 (§2-0).**

---

## 1-7. 이행 순서 요약

```
0. 사전 준비 (리전·예산 알림·키페어)
1. VPC / 서브넷 / IGW / NAT / 라우팅
2. 보안 그룹 5개 (먼저 다 만들어 둔다)
3. RDS MySQL + 데이터 이관
4. ECR 리포지토리 3개 + 이미지 푸시
5. IAM 역할 (EC2 인스턴스 프로파일)
6. AI 계층: Launch Template → 내부 ALB → TG → ASG → 스케일링 정책
7. 백엔드 계층: Launch Template → 퍼블릭 ALB → TG → ASG(1대)
8. 프론트: S3 버킷 + 빌드 업로드
9. CloudFront: S3 오리진(OAC) + ALB 오리진 + /api/* 동작
10. 도메인 + ACM 인증서
11. CloudWatch 알람 + 스케일링 검증 (부하 테스트)
12. 정리 / 삭제
```

각 단계가 앞 단계의 출력값(ID, DNS 이름)을 쓴다. **아래 표를 복사해서 채워가며 진행한다.**

```
□ VPC ID                 vpc-
□ 퍼블릭 서브넷 2a       subnet-
□ 퍼블릭 서브넷 2c       subnet-
□ 앱 서브넷 2a           subnet-
□ 앱 서브넷 2c           subnet-
□ DB 서브넷 2a           subnet-
□ DB 서브넷 2c           subnet-
□ SG: alb-public         sg-
□ SG: backend            sg-
□ SG: alb-internal       sg-
□ SG: ai                 sg-
□ SG: rds                sg-
□ RDS 엔드포인트         artifact-db.xxxxx.ap-northeast-2.rds.amazonaws.com
□ ECR backend URI        xxxxx.dkr.ecr.ap-northeast-2.amazonaws.com/artifact-backend
□ ECR fastapi URI        xxxxx.dkr.ecr.ap-northeast-2.amazonaws.com/artifact-fastapi
□ 내부 ALB DNS           internal-artifact-ai-alb-xxxx.ap-northeast-2.elb.amazonaws.com
□ 퍼블릭 ALB DNS         artifact-alb-xxxx.ap-northeast-2.elb.amazonaws.com
□ S3 버킷명              artifact-frontend-xxxx
□ CloudFront 배포 도메인 dxxxxxxxx.cloudfront.net
```

---

# 파트 2 — 콘솔 단계별 가이드

> **표기 규칙**
> - `[버튼]` = 클릭할 버튼/링크
> - `필드: 값` = 입력할 내용
> - 언급 없는 옵션은 **전부 기본값 그대로 둔다**
> - 콘솔 UI는 수시로 바뀐다. 버튼 이름이 다르면 같은 뜻의 것을 찾는다

---

## 2-0. 사전 준비

### ① 리전 고정

콘솔 우측 상단 리전 드롭다운 → **아시아 태평양(서울) ap-northeast-2**

> **이후 모든 작업에서 이 리전이 선택돼 있는지 매번 확인한다.** 리전이 다르면 리소스가
> 서로 안 보이고, 나중에 "분명 만들었는데 목록에 없다"로 30분을 쓴다.

### ② 예산 알림 (이걸 제일 먼저 한다)

1. 콘솔 검색창 → `Billing and Cost Management` → 좌측 **[예산]**
2. **[예산 생성]**
3. 예산 설정 방법: **템플릿 사용(단순)** → **월별 비용 예산**
4. 예산 이름: `artifact-aws-budget`
5. 예산 금액: `50` (USD)
6. 이메일 수신자: 본인 이메일
7. **[예산 생성]**

> 50달러로 잡으면 실수했을 때 늦기 전에 알람이 온다. 실제 알림은 85%/100%/예측 100%에서 온다.

### ③ 결제 알림 활성화 (선택)

`Billing` → **[결제 기본 설정]** → **[프리 티어 사용량 알림 수신]** 체크 → 저장

### ④ EC2 키 페어 — **만들지 않는다**

이 가이드는 **SSM Session Manager**로 인스턴스에 접속한다. 22번 포트를 열지 않고,
키 파일 관리도 없다. 기존 CI/CD 문서(`docs/cicd-guide.md`)가 이미 같은 이유로
self-hosted 러너를 쓰고 있으니 방향이 일관된다.

---

## 2-1. VPC와 네트워크

### ① VPC 마법사로 한 번에 만들기

콘솔 검색 → `VPC` → 좌측 **[VPC 대시보드]** → **[VPC 생성]**

**생성할 리소스: `VPC 등` 선택** ← 중요. `VPC만`이 아니다.

| 항목 | 입력값 |
|---|---|
| 이름 태그 자동 생성 | 체크, `artifact` |
| IPv4 CIDR 블록 | `10.0.0.0/16` |
| IPv6 CIDR 블록 | 없음 |
| 테넌시 | 기본값 |
| **가용 영역(AZ) 수** | **2** |
| AZ 사용자 지정 | `ap-northeast-2a`, `ap-northeast-2c` |
| **퍼블릭 서브넷 수** | **2** |
| **프라이빗 서브넷 수** | **4** ← 앱용 2 + DB용 2 |
| **NAT 게이트웨이** | **AZ 1개 안에** (비용 절감) 또는 **없음**(§1-6 절감안) |
| VPC 엔드포인트 | **S3 게이트웨이** ← 무료. 켠다 |
| DNS 호스트 이름 활성화 | 체크 |
| DNS 확인 활성화 | 체크 |

> **NAT 게이트웨이 선택**:
> - `AZ 1개 안에` → 월 ~$35. 정석에 가깝고 프라이빗 서브넷이 제대로 동작한다
> - `없음` → 프라이빗 서브넷 인스턴스가 인터넷(ECR, apt, pip)에 못 나간다.
>   이 경우 §2-1-④의 "NAT 없이 가기" 대안을 따른다

**[VPC 생성]** → 1~3분 대기 → **[View VPC]**

### ② 만들어진 것 확인

좌측 **[서브넷]** → `artifact` 필터. 6개가 보여야 한다:

```
artifact-subnet-public1-ap-northeast-2a    10.0.0.0/20
artifact-subnet-public2-ap-northeast-2c    10.0.16.0/20
artifact-subnet-private1-ap-northeast-2a   10.0.128.0/20   ← 앱용으로 쓴다
artifact-subnet-private2-ap-northeast-2c   10.0.144.0/20   ← 앱용
artifact-subnet-private3-ap-northeast-2a   10.0.160.0/20   ← DB용
artifact-subnet-private4-ap-northeast-2c   10.0.176.0/20   ← DB용
```

**각 서브넷 ID를 §1-7 체크리스트에 적어둔다.** private1/2 = 앱, private3/4 = DB로 쓴다.

### ③ 이름 태그 정리 (선택이지만 권장)

각 서브넷 선택 → **[태그]** 탭 → **[태그 관리]** → `Name` 값을 알아보기 쉽게:

```
artifact-public-2a     artifact-public-2c
artifact-app-2a        artifact-app-2c
artifact-db-2a         artifact-db-2c
```

> 나중에 ASG/ALB/RDS를 만들 때 드롭다운에서 서브넷을 고르는데, 이름이 명확하지 않으면
> 잘못 고른다. 여기서 5분 쓰는 게 낫다.

### ④ NAT 없이 가기 (절감 선택 시에만)

NAT를 `없음`으로 만들었다면, 앱 인스턴스가 ECR에서 이미지를 못 받는다. 두 가지 길:

**방법 A — VPC 엔드포인트로 ECR만 통하게 (권장)**

VPC → **[엔드포인트]** → **[엔드포인트 생성]**, 아래를 **3번 반복**:

| # | 서비스 이름 | 유형 |
|---|---|---|
| 1 | `com.amazonaws.ap-northeast-2.ecr.api` | Interface |
| 2 | `com.amazonaws.ap-northeast-2.ecr.dkr` | Interface |
| 3 | `com.amazonaws.ap-northeast-2.s3` | Gateway (이미 있으면 생략) |

각각:
- VPC: `artifact-vpc`
- 서브넷: 앱 서브넷 2개 (`artifact-app-2a`, `artifact-app-2c`)
- 보안 그룹: 나중에 만들 `artifact-sg-vpce` (443 인바운드 from 앱 SG)
- 정책: 전체 액세스

SSM 접속까지 하려면 엔드포인트 3개 더 필요하다: `ssm`, `ssmmessages`, `ec2messages`.

> Interface 엔드포인트는 개당 월 ~$8이다. 6개면 $48 — **NAT($35)보다 비싸진다.**
> 엔드포인트를 3개(ecr.api, ecr.dkr, s3)만 쓰고 SSM 대신 다른 방법을 쓸 때만 이득이다.

**방법 B — 앱 인스턴스를 퍼블릭 서브넷에 두기 (가장 싸다)**

ASG의 서브넷을 퍼블릭 서브넷으로 지정하고, **보안 그룹으로 인바운드를 전부 막는다**
(ALB SG에서 오는 것만 허용). 퍼블릭 IP는 붙지만 아무도 접속할 수 없다.

> 실무 정석은 아니다. 하지만 **"프라이빗 서브넷 + NAT"의 학습 가치 대비 월 $35**를
> 놓고 판단하면, 학습이 목적일 때는 한 번 정석으로 만들어 보고 확인한 뒤
> 이쪽으로 바꾸는 것도 방법이다. 이 문서는 NAT 있는 정석을 기준으로 이어간다.

---

## 2-2. 보안 그룹 5개

> **먼저 전부 만들고 나중에 규칙을 채운다.** 서로를 참조하기 때문에 순환이 생긴다
> (ALB SG가 백엔드 SG를 참조하고, 백엔드 SG가 ALB SG를 참조). 껍데기부터 만든다.

EC2 콘솔 → 좌측 **[보안 그룹]** → **[보안 그룹 생성]** × 5회.
**모두 VPC를 `artifact-vpc`로 지정한다** (기본 VPC가 선택돼 있으니 반드시 바꾼다).

| 이름 | 설명 |
|---|---|
| `artifact-sg-alb-public` | 인터넷 → 퍼블릭 ALB |
| `artifact-sg-backend` | ALB → 백엔드 EC2 |
| `artifact-sg-alb-internal` | 백엔드 → 내부 ALB |
| `artifact-sg-ai` | 내부 ALB → AI EC2 |
| `artifact-sg-rds` | 백엔드 → RDS |

만들 때 인바운드 규칙은 비워둔다. 아웃바운드는 기본값(전체 허용) 유지.

### 규칙 채우기

각 SG 선택 → **[인바운드 규칙]** 탭 → **[인바운드 규칙 편집]** → **[규칙 추가]**

**① `artifact-sg-alb-public`**

| 유형 | 프로토콜 | 포트 | 소스 | 설명 |
|---|---|---|---|---|
| HTTPS | TCP | 443 | `0.0.0.0/0` | CloudFront 및 직접 접근 |
| HTTP | TCP | 80 | `0.0.0.0/0` | 443 리디렉션용 |

> CloudFront 매니지드 프리픽스 리스트(`com.amazonaws.global.cloudfront.origin-facing`)로
> 좁히는 게 더 정확하지만, 검증 단계에서 ALB에 직접 접근해볼 일이 많으니 일단 열어둔다.
> 검증이 끝나면 좁힌다 (§2-11-④).

**② `artifact-sg-backend`**

| 유형 | 포트 | 소스 |
|---|---|---|
| 사용자 지정 TCP | 8080 | **`artifact-sg-alb-public`** ← 소스 칸에 sg- 를 입력하면 자동완성된다 |

> **CIDR가 아니라 보안 그룹 ID를 소스로 넣는 것**이 핵심이다. 이러면 "ALB에서 온 것만"이
> 되고, IP가 바뀌어도 규칙을 고칠 필요가 없다.

**③ `artifact-sg-alb-internal`**

| 유형 | 포트 | 소스 |
|---|---|---|
| 사용자 지정 TCP | 8000 | **`artifact-sg-backend`** |

**④ `artifact-sg-ai`**

| 유형 | 포트 | 소스 |
|---|---|---|
| 사용자 지정 TCP | 8000 | **`artifact-sg-alb-internal`** |

**⑤ `artifact-sg-rds`**

| 유형 | 포트 | 소스 |
|---|---|---|
| MYSQL/Aurora | 3306 | **`artifact-sg-backend`** |

### 확인

```
인터넷 ──443──▶ [alb-public] ──8080──▶ [backend] ──8000──▶ [alb-internal] ──8000──▶ [ai]
                                           │
                                           └──3306──▶ [rds]
```

**22번 포트가 어디에도 없다.** 의도된 것이다 — 접속은 SSM으로 한다.

---

## 2-3. RDS MySQL

### ① 서브넷 그룹 만들기

RDS 콘솔 → 좌측 **[서브넷 그룹]** → **[DB 서브넷 그룹 생성]**

| 항목 | 값 |
|---|---|
| 이름 | `artifact-db-subnet-group` |
| 설명 | `artifact private db subnets` |
| VPC | `artifact-vpc` |
| 가용 영역 | `ap-northeast-2a`, `ap-northeast-2c` |
| 서브넷 | `artifact-db-2a`, `artifact-db-2c` ← **DB용 서브넷만** |

**[생성]**

### ② 데이터베이스 생성

RDS → **[데이터베이스]** → **[데이터베이스 생성]**

| 항목 | 값 | 비고 |
|---|---|---|
| 데이터베이스 생성 방식 | **표준 생성** | `손쉬운 생성`은 옵션을 못 고른다 |
| 엔진 유형 | **MySQL** | |
| 엔진 버전 | **MySQL 8.0.x** (최신 8.0) | 8.4/9.x 아님 — 현재 컨테이너가 8.0 |
| 템플릿 | **개발/테스트** | `프로덕션`을 고르면 Multi-AZ가 켜져 비용 2배 |
| 가용성 및 내구성 | **단일 DB 인스턴스** | |
| DB 인스턴스 식별자 | `artifact-db` | |
| 마스터 사용자 이름 | `artifact` | `root` 쓰지 않는다 |
| 자격 증명 관리 | **자체 관리** | Secrets Manager는 월 $0.4 추가 |
| 마스터 암호 | 직접 입력 (20자 이상) | **어딘가에 안전하게 적어둔다** |
| DB 인스턴스 클래스 | **버스터블 · db.t4g.micro** | |
| 스토리지 유형 | **범용 SSD (gp3)** | |
| 할당된 스토리지 | `20` GB | |
| **스토리지 자동 조정** | **체크 해제** | 켜두면 모르는 새 비용이 는다 |
| 컴퓨팅 리소스 | **EC2 컴퓨팅 리소스에 연결 안 함** | 우리가 직접 SG를 지정한다 |
| 네트워크 유형 | IPv4 | |
| VPC | `artifact-vpc` | |
| DB 서브넷 그룹 | `artifact-db-subnet-group` | |
| **퍼블릭 액세스** | **아니요** | ⚠️ 절대 `예`로 두지 않는다 |
| VPC 보안 그룹 | **기존 항목 선택** → `artifact-sg-rds` | 기본 SG는 체크 해제 |
| 가용 영역 | `ap-northeast-2a` | |
| 데이터베이스 포트 | `3306` | |
| 데이터베이스 인증 | 암호 인증 | |
| **추가 구성 → 초기 데이터베이스 이름** | **`artifact_db`** | ⚠️ 비워두면 DB가 안 만들어진다 |
| 파라미터 그룹 | 기본값 | |
| 백업 → 자동 백업 활성화 | **체크** | |
| 백업 보존 기간 | `7`일 | |
| 암호화 | 체크 (기본 KMS 키) | |
| **성능 개선 도우미** | **체크 해제** | 무료 티어 밖이면 과금 |
| 향상된 모니터링 | 체크 해제 | |
| 삭제 방지 | 체크 해제 | 실습이라 나중에 지워야 한다 |

**[데이터베이스 생성]** → 5~10분 대기

### ③ 문자셋 확인 (한글 깨짐 방지)

> 기존 `docker-compose.yml`의 MySQL은 `--character-set-server=utf8mb4` 옵션으로 떴다.
> RDS 기본 파라미터 그룹은 `latin1`일 수 있어, **그대로 두면 한글이 깨진다.**
> (`disease.name_ko` 모지바케 문제를 다시 만들 수 있다)

RDS → **[파라미터 그룹]** → **[파라미터 그룹 생성]**

| 항목 | 값 |
|---|---|
| 파라미터 그룹 패밀리 | `mysql8.0` |
| 유형 | DB Parameter Group |
| 그룹 이름 | `artifact-mysql80-utf8mb4` |

생성 후 그룹 선택 → **[편집]** → 검색해서 다음 2개를 바꾼다:

| 파라미터 | 값 |
|---|---|
| `character_set_server` | `utf8mb4` |
| `collation_server` | `utf8mb4_unicode_ci` |

**[변경 사항 저장]**

그 다음 `artifact-db` 선택 → **[수정]** → 추가 구성 → DB 파라미터 그룹을
`artifact-mysql80-utf8mb4`로 변경 → **[계속]** → **즉시 적용** → **[DB 인스턴스 수정]**
→ 상태가 `pending-reboot`가 되면 **[작업] → [재부팅]**

### ④ 엔드포인트 기록

`artifact-db` 클릭 → **[연결 및 보안]** 탭 → **엔드포인트** 복사:

```
artifact-db.cxxxxxxxxx.ap-northeast-2.rds.amazonaws.com
```

체크리스트에 적는다. 나중에 `DB_HOST`로 쓴다.

### ⑤ 데이터 이관

기존 EC2에서 덤프를 떠서 넣는다. **기존 EC2에 SSH로 들어가서:**

```bash
# 1) 덤프
docker exec artifact-mysql mysqldump \
  -u root -p"$MYSQL_ROOT_PASSWORD" \
  --default-character-set=utf8mb4 \
  --single-transaction --routines --triggers \
  artifact_db > /tmp/artifact_db.sql

# 2) 크기 확인
ls -lh /tmp/artifact_db.sql

# 3) RDS로 밀어넣기 (같은 VPC가 아니면 이 단계는 새 VPC 안의 인스턴스에서 해야 한다)
mysql -h artifact-db.cxxxxxxxxx.ap-northeast-2.rds.amazonaws.com \
      -u artifact -p \
      --default-character-set=utf8mb4 \
      artifact_db < /tmp/artifact_db.sql
```

> **네트워크 주의**: RDS는 `artifact-vpc` 안에 있고 퍼블릭 액세스가 꺼져 있다.
> 기존 EC2가 다른 VPC(기본 VPC)에 있으면 직접 못 붙는다. 방법:
> 1. 덤프 파일을 S3에 올린다
> 2. §2-9에서 백엔드 인스턴스를 띄운 뒤, 거기서 S3에서 받아 RDS로 넣는다
>
> 데이터가 적고 시연용이면 **이관을 건너뛰고 빈 DB로 시작**해도 된다. JPA가
> 스키마를 만들고 `backend/src/main/resources/data/`의 KCD·약품 시드가 들어간다.

---

## 2-4. ECR 리포지토리와 이미지 푸시

### ① 리포지토리 2개 생성

ECR 콘솔 → **[리포지토리]** → **[리포지토리 생성]**

| 항목 | 값 |
|---|---|
| 표시 여부 설정 | **프라이빗** |
| 리포지토리 이름 | `artifact-backend` |
| 태그 변경 불가능 | 비활성 |
| 푸시할 때 스캔 | **활성** (무료) |
| 암호화 | AES-256 |

**[리포지토리 생성]** → 같은 방법으로 `artifact-fastapi` 하나 더.

> **프론트엔드 이미지는 만들지 않는다.** S3로 가기 때문이다.

### ② URI 확인

리포지토리 목록에 URI가 나온다:

```
123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/artifact-backend
123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/artifact-fastapi
```

앞의 12자리가 **AWS 계정 ID**다. 체크리스트에 적는다.

### ③ 로컬에서 빌드·푸시

> ⚠️ **아키텍처 주의**: `t4g` 계열은 **ARM64(Graviton)**다. 맥북(Apple Silicon)도 ARM64라
> 그냥 빌드하면 맞지만, Intel 맥이나 GitHub Actions x86 러너에서 빌드하면 안 뜬다.
> `--platform linux/arm64`를 명시한다.

**프로젝트 루트에서:**

```bash
export AWS_ACCOUNT_ID=123456789012
export AWS_REGION=ap-northeast-2
export ECR=$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com

# 1) ECR 로그인
aws ecr get-login-password --region $AWS_REGION \
  | docker login --username AWS --password-stdin $ECR

# 2) 백엔드
docker build --platform linux/arm64 \
  -t $ECR/artifact-backend:latest ./backend
docker push $ECR/artifact-backend:latest

# 3) FastAPI (model.pth 16MB가 이미지에 포함된다)
docker build --platform linux/arm64 \
  -t $ECR/artifact-fastapi:latest ./fastapi
docker push $ECR/artifact-fastapi:latest
```

> `aws` CLI가 없으면 `brew install awscli` 후 `aws configure`로 액세스 키를 넣는다.
> IAM에서 프로그래밍 방식 액세스 사용자를 만들고 `AmazonEC2ContainerRegistryPowerUser`
> 정책을 붙이면 된다.

**PyTorch ARM64 빌드가 오래 걸린다 (10~20분).** 한 번만 하면 된다.

---

## 2-5. IAM 역할

ASG 인스턴스가 ECR에서 이미지를 받고, SSM으로 접속을 받고, CloudWatch에 로그를 보내려면
**인스턴스 프로파일**이 필요하다.

IAM 콘솔 → **[역할]** → **[역할 생성]**

| 단계 | 입력 |
|---|---|
| 신뢰할 수 있는 엔터티 유형 | **AWS 서비스** |
| 사용 사례 | **EC2** |
| **[다음]** | |
| 권한 정책 (검색해서 체크) | `AmazonEC2ContainerRegistryReadOnly` |
| | `AmazonSSMManagedInstanceCore` |
| | `CloudWatchAgentServerPolicy` |
| **[다음]** | |
| 역할 이름 | `artifact-ec2-role` |

**[역할 생성]**

### S3 이미지 저장을 쓸 거라면 (권장)

역할 생성 후 → **[권한 추가]** → **[인라인 정책 생성]** → JSON 탭:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::artifact-medical-images/*"
    }
  ]
}
```

정책 이름: `artifact-s3-images` → **[정책 생성]**

> 이 버킷은 §2-8의 프론트엔드 버킷과 **다른 버킷**이다. 진단 이미지는 절대 공개되면 안 된다.

---

## 2-6. AI 계층 구축

### ① Launch Template (시작 템플릿)

EC2 콘솔 → 좌측 **[시작 템플릿]** → **[시작 템플릿 생성]**

| 항목 | 값 |
|---|---|
| 시작 템플릿 이름 | `artifact-ai-lt` |
| 템플릿 버전 설명 | `fastapi inference worker v1` |
| Auto Scaling 지침 | **체크** (ASG용 최적화) |
| **애플리케이션 및 OS 이미지** | **[빠른 시작]** → **Amazon Linux 2023 AMI** → 아키텍처 **64비트(Arm)** |
| **인스턴스 유형** | `t4g.medium` (2 vCPU / 4GB) |
| **키 페어** | **키 페어 없음** ← SSM으로 접속한다 |
| 네트워크 설정 → 서브넷 | **시작 템플릿에 포함 안 함** ← ASG에서 지정한다 |
| **보안 그룹** | `artifact-sg-ai` |
| 스토리지 | 1x `20` GiB `gp3` |
| **고급 세부 정보 → IAM 인스턴스 프로파일** | `artifact-ec2-role` |
| 고급 세부 정보 → 종료 방지 | 비활성 |
| 고급 세부 정보 → 세부 CloudWatch 모니터링 | **활성화** ← 1분 단위 지표. 스케일링 반응이 빨라진다 |

**고급 세부 정보 → 사용자 데이터**에 아래를 붙여넣는다
(`<ACCOUNT_ID>`, `<INTERNAL_SECRET>`를 실제 값으로 바꾼다):

```bash
#!/bin/bash
set -euxo pipefail

AWS_REGION=ap-northeast-2
ECR=<ACCOUNT_ID>.dkr.ecr.ap-northeast-2.amazonaws.com

# 도커 설치
dnf install -y docker
systemctl enable --now docker

# ECR 로그인
aws ecr get-login-password --region $AWS_REGION \
  | docker login --username AWS --password-stdin $ECR

# 추론 컨테이너 기동
# 스레드 수를 vCPU 수에 맞춘다. t4g.medium 은 2 vCPU.
docker run -d \
  --name artifact-fastapi \
  --restart unless-stopped \
  -p 8000:8000 \
  -e INTERNAL_API_SECRET='<INTERNAL_SECRET>' \
  -e LOW_CONFIDENCE_THRESHOLD=0.45 \
  -e TORCH_NUM_THREADS=2 \
  -e OMP_NUM_THREADS=2 \
  $ECR/artifact-fastapi:latest

# 부팅 로그를 CloudWatch 로 보내려면 여기에 cloudwatch-agent 설정 추가
```

> **`INTERNAL_API_SECRET`을 user-data에 평문으로 넣는 것은 임시 방편이다.**
> user-data는 인스턴스 안에서 `curl 169.254.169.254/latest/user-data`로 읽힌다.
> 제대로 하려면 **SSM Parameter Store(SecureString)**에 넣고 스크립트에서
> `aws ssm get-parameter --with-decryption`으로 꺼내야 한다. 아래 §2-6-② 참고.

**[시작 템플릿 생성]**

### ② 시크릿을 Parameter Store로 옮기기 (권장)

Systems Manager 콘솔 → **[Parameter Store]** → **[파라미터 생성]**

| 항목 | 값 |
|---|---|
| 이름 | `/artifact/prod/INTERNAL_API_SECRET` |
| 계층 | 표준 (무료) |
| 유형 | **보안 문자열(SecureString)** |
| KMS 키 소스 | 현재 계정 → `alias/aws/ssm` |
| 값 | 실제 시크릿 |

같은 방법으로 백엔드용도 미리 만든다:

```
/artifact/prod/DB_PASSWORD
/artifact/prod/JWT_SECRET
/artifact/prod/GEMINI_API_KEY
/artifact/prod/INTERNAL_API_SECRET   (AI와 같은 값)
```

그 다음 IAM 역할에 인라인 정책 추가:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["ssm:GetParameter", "ssm:GetParameters"],
      "Resource": "arn:aws:ssm:ap-northeast-2:<ACCOUNT_ID>:parameter/artifact/prod/*"
    },
    {
      "Effect": "Allow",
      "Action": "kms:Decrypt",
      "Resource": "*"
    }
  ]
}
```

user-data를 이렇게 고친다:

```bash
INTERNAL_SECRET=$(aws ssm get-parameter \
  --name /artifact/prod/INTERNAL_API_SECRET \
  --with-decryption --region $AWS_REGION \
  --query Parameter.Value --output text)
```

### ③ 대상 그룹 (Target Group)

EC2 콘솔 → 좌측 **[대상 그룹]** → **[대상 그룹 생성]**

| 항목 | 값 |
|---|---|
| 대상 유형 선택 | **인스턴스** |
| 대상 그룹 이름 | `artifact-ai-tg` |
| 프로토콜 : 포트 | **HTTP** : `8000` |
| IP 주소 유형 | IPv4 |
| VPC | `artifact-vpc` |
| 프로토콜 버전 | HTTP1 |
| **상태 검사 프로토콜** | HTTP |
| **상태 검사 경로** | **`/health`** |

**[고급 상태 검사 설정]** 펼쳐서:

| 항목 | 값 | 이유 |
|---|---|---|
| 정상 임계값 | `2` | |
| 비정상 임계값 | `3` | |
| 제한 시간 | `10`초 | 추론 중이면 응답이 늦을 수 있다 |
| **간격** | `15`초 | |
| 성공 코드 | `200` | |
| 등록 취소 지연 | `60`초 | 진행 중인 추론이 끝날 시간 |

**[다음]** → 대상 등록 화면에서 **아무것도 등록하지 않고** **[대상 그룹 생성]**

> ASG가 자동으로 등록한다. 여기서 수동 등록하면 나중에 꼬인다.

### ④ 내부 ALB

EC2 콘솔 → 좌측 **[로드 밸런서]** → **[로드 밸런서 생성]** → **Application Load Balancer [생성]**

| 항목 | 값 |
|---|---|
| 로드 밸런서 이름 | `artifact-ai-alb` |
| **체계(Scheme)** | **내부(Internal)** ⚠️ 여기가 핵심 |
| 로드 밸런서 IP 주소 유형 | IPv4 |
| VPC | `artifact-vpc` |
| **매핑** | `ap-northeast-2a` → `artifact-app-2a` |
| | `ap-northeast-2c` → `artifact-app-2c` |
| 보안 그룹 | `artifact-sg-alb-internal` (기본 SG 체크 해제) |
| **리스너 및 라우팅** | 프로토콜 `HTTP` / 포트 `8000` / 기본 작업 `artifact-ai-tg` |

**[로드 밸런서 생성]** → 3~5분 → 상태가 `활성`이 되면
**DNS 이름**을 복사해 체크리스트에 적는다:

```
internal-artifact-ai-alb-1234567890.ap-northeast-2.elb.amazonaws.com
```

> `internal-`로 시작하면 제대로 만든 것이다. 이 주소는 VPC 안에서만 resolve 된다.

### ⑤ 업로드 크기 제한 확인

진단 이미지는 최대 25MB까지 올라온다 (`frontend/nginx.conf`의 `client_max_body_size 25m`).
**ALB는 본문 크기 제한이 없으므로 별도 설정이 필요 없다.** 다만 유휴 타임아웃은 확인한다:

로드 밸런서 선택 → **[속성]** 탭 → **[편집]**

| 속성 | 값 | 이유 |
|---|---|---|
| 유휴 제한 시간 | **`120`초** (기본 60) | Grad-CAM 포함 추론이 길어질 수 있다 |
| HTTP/2 | 활성 | |

### ⑥ Auto Scaling Group

EC2 콘솔 → 좌측 **[Auto Scaling 그룹]** → **[Auto Scaling 그룹 생성]**

**1단계 — 이름과 템플릿**

| 항목 | 값 |
|---|---|
| Auto Scaling 그룹 이름 | `artifact-ai-asg` |
| 시작 템플릿 | `artifact-ai-lt` |
| 버전 | `Latest` |

**[다음]**

**2단계 — 네트워크**

| 항목 | 값 |
|---|---|
| VPC | `artifact-vpc` |
| 가용 영역 및 서브넷 | `artifact-app-2a`, `artifact-app-2c` |
| 가용 영역 분산 | 균형 잡힌 최선의 노력 |

**[다음]**

**3단계 — 로드 밸런싱 ⭐**

| 항목 | 값 |
|---|---|
| 로드 밸런싱 | **기존 로드 밸런서에 연결** |
| 선택 | **로드 밸런서 대상 그룹에서 선택** |
| 기존 로드 밸런서 대상 그룹 | **`artifact-ai-tg`** |
| VPC Lattice | 서비스에 연결 안 함 |
| **상태 확인** | **EC2** 체크 + **Elastic Load Balancing** 체크 |
| **상태 확인 유예 기간** | **`180`초** ⚠️ |

> **유예 기간 180초가 중요하다.** `docker-compose.yml`의 FastAPI 헬스체크가
> `start_period: 60s`인 이유는 PyTorch 모델 로딩이 오래 걸리기 때문이다. 여기에
> 인스턴스 부팅 + 도커 설치 + ECR pull(PyTorch 이미지는 크다)이 더해진다.
> 유예 기간이 짧으면 **ASG가 "아직 뜨는 중"인 인스턴스를 비정상으로 판단해 죽이고
> 새로 만들기를 무한 반복한다.** 가장 흔한 함정이다. 넉넉하게 180초를 준다.

**[다음]**

**4단계 — 그룹 크기 및 크기 조정 ⭐**

| 항목 | 값 |
|---|---|
| 원하는 용량 | `1` |
| **최소 필요 용량** | `1` |
| **최대 필요 용량** | `4` |

**크기 조정 정책 → 대상 추적 크기 조정 정책** 선택:

| 항목 | 값 |
|---|---|
| 크기 조정 정책 이름 | `ai-cpu-target-60` |
| 지표 유형 | **평균 CPU 사용률** |
| 대상 값 | **`60`** |
| 인스턴스 요구 시간(웜업) | **`180`**초 |

> **왜 CPU인가.** `ALBRequestCountPerTarget`을 쓰는 게 일반적이지만, 우리 워크로드는
> **요청 1건의 CPU 비용이 압도적으로 크고 균일하다**(EfficientNet-B0 추론 + Grad-CAM).
> 요청 수보다 CPU가 실제 포화를 더 정확히 반영한다. `_model_lock` 때문에 한 인스턴스가
> 한 번에 1건만 처리하므로, CPU 60%는 곧 "거의 쉬지 않고 돌고 있다"는 뜻이다.
>
> 나중에 둘 다 붙여보고 비교하는 것도 좋은 실습이다 — ASG는 정책을 여러 개 가질 수 있고,
> **가장 큰 용량을 요구하는 정책이 이긴다.**

**[다음]**

**5단계 — 알림**: 건너뛴다 → **[다음]**

**6단계 — 태그**

| 키 | 값 |
|---|---|
| `Name` | `artifact-ai` ← 인스턴스 목록에서 알아보기 위해 |
| `Project` | `artifact` |

**[다음]** → 검토 → **[Auto Scaling 그룹 생성]**

### ⑦ 검증

1. EC2 → **[인스턴스]** → `artifact-ai` 인스턴스가 1대 뜨는지 확인
2. 3~5분 기다린 뒤 **[대상 그룹]** → `artifact-ai-tg` → **[대상]** 탭
   → 상태가 **`healthy`**가 되어야 한다

**`unhealthy`거나 계속 교체된다면:**

인스턴스 선택 → **[연결]** → **[Session Manager]** 탭 → **[연결]**

```bash
# user-data 가 실행됐는지
sudo cat /var/log/cloud-init-output.log | tail -50

# 컨테이너가 떴는지
sudo docker ps -a

# 컨테이너 로그
sudo docker logs artifact-fastapi

# 직접 헬스체크
curl -i localhost:8000/health
```

흔한 원인:
- `INTERNAL_API_SECRET`이 비어서 FastAPI가 부팅 시 `RuntimeError`로 죽음
  (`fastapi/main.py`가 의도적으로 그렇게 만들어져 있다)
- ECR 로그인 실패 → IAM 역할이 안 붙었거나 NAT가 없어 인터넷에 못 나감
- 이미지 아키텍처 불일치 → `exec format error` → x86 이미지를 ARM에 올린 것

---

## 2-7. 백엔드 계층 구축

### ① 시작 템플릿

EC2 → **[시작 템플릿]** → **[시작 템플릿 생성]**

| 항목 | 값 |
|---|---|
| 이름 | `artifact-backend-lt` |
| AMI | Amazon Linux 2023 (Arm) |
| 인스턴스 유형 | `t4g.small` (2 vCPU / 2GB) |
| 키 페어 | 없음 |
| 서브넷 | 포함 안 함 |
| 보안 그룹 | `artifact-sg-backend` |
| 스토리지 | `20` GiB gp3 |
| IAM 인스턴스 프로파일 | `artifact-ec2-role` |
| 세부 CloudWatch 모니터링 | 활성화 |

**사용자 데이터:**

```bash
#!/bin/bash
set -euxo pipefail

AWS_REGION=ap-northeast-2
ECR=<ACCOUNT_ID>.dkr.ecr.ap-northeast-2.amazonaws.com

dnf install -y docker
systemctl enable --now docker

aws ecr get-login-password --region $AWS_REGION \
  | docker login --username AWS --password-stdin $ECR

# --- Parameter Store 에서 시크릿 꺼내기 ---
get_param() {
  aws ssm get-parameter --name "$1" --with-decryption \
    --region $AWS_REGION --query Parameter.Value --output text
}
DB_PASSWORD=$(get_param /artifact/prod/DB_PASSWORD)
JWT_SECRET=$(get_param /artifact/prod/JWT_SECRET)
GEMINI_API_KEY=$(get_param /artifact/prod/GEMINI_API_KEY)
INTERNAL_SECRET=$(get_param /artifact/prod/INTERNAL_API_SECRET)

docker run -d \
  --name artifact-backend \
  --restart unless-stopped \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DB_HOST='artifact-db.cxxxxxxxxx.ap-northeast-2.rds.amazonaws.com' \
  -e DB_PORT=3306 \
  -e DB_NAME=artifact_db \
  -e DB_USERNAME=artifact \
  -e DB_PASSWORD="$DB_PASSWORD" \
  -e JWT_SECRET="$JWT_SECRET" \
  -e GEMINI_API_KEY="$GEMINI_API_KEY" \
  -e INTERNAL_API_SECRET="$INTERNAL_SECRET" \
  -e FASTAPI_URL='http://internal-artifact-ai-alb-1234567890.ap-northeast-2.elb.amazonaws.com:8000' \
  -e CORS_ALLOWED_ORIGINS='https://dxxxxxxxx.cloudfront.net' \
  -e PRINT_KIOSK_ALLOWED_BASE_URLS='https://dxxxxxxxx.cloudfront.net' \
  -e PRINT_MODE=queue \
  -e PRINT_AGENT_ENABLED=true \
  -e SWAGGER_ENABLED=false \
  -e LOG_LEVEL=INFO \
  -e KIOSK_AUTO_PENDING=false \
  -e IMAGE_STORAGE_TYPE=s3 \
  -e AWS_S3_BUCKET=artifact-medical-images \
  -e JAVA_OPTS='-Xms256m -Xmx1g' \
  $ECR/artifact-backend:latest
```

> **`FASTAPI_URL`과 CloudFront 도메인은 아직 모른다.**
> 내부 ALB DNS는 §2-6-④에서 얻었으니 지금 넣고, CloudFront 도메인은 §2-9에서
> 만든 뒤 **시작 템플릿 새 버전을 만들어** 채운다. 그때 Instance Refresh로 반영한다.
>
> 지금은 일단 ALB DNS(§2-8에서 얻음)를 임시로 넣어도 되고, 비워둔 채 진행해도 된다.

**[시작 템플릿 생성]**

### ② 대상 그룹

EC2 → **[대상 그룹]** → **[대상 그룹 생성]**

| 항목 | 값 |
|---|---|
| 대상 유형 | 인스턴스 |
| 이름 | `artifact-backend-tg` |
| 프로토콜 : 포트 | HTTP : `8080` |
| VPC | `artifact-vpc` |
| **상태 검사 경로** | **`/actuator/health`** |

**고급 상태 검사 설정:**

| 항목 | 값 |
|---|---|
| 정상 임계값 | `2` |
| 비정상 임계값 | `3` |
| 제한 시간 | `5`초 |
| 간격 | `15`초 |
| 성공 코드 | `200` |
| 등록 취소 지연 | **`30`**초 |

**[다음]** → 대상 등록 없이 **[대상 그룹 생성]**

> `/actuator/health`가 인증 없이 200을 주는지 먼저 확인한다. Spring Security 설정에서
> 막혀 있으면 ALB가 계속 401을 받아 `unhealthy`가 된다. 현재 `SecurityConfig`에
> actuator가 permitAll 되어 있는지 확인하고, 아니면 대신 `/swagger-ui/index.html`처럼
> 확실히 200이 나는 경로를 임시로 쓰거나 SecurityConfig를 고친다(코드 변경 필요).

### ③ 퍼블릭 ALB

EC2 → **[로드 밸런서]** → **[로드 밸런서 생성]** → Application Load Balancer

| 항목 | 값 |
|---|---|
| 이름 | `artifact-alb` |
| **체계** | **인터넷 경계(Internet-facing)** |
| IP 주소 유형 | IPv4 |
| VPC | `artifact-vpc` |
| 매핑 | `ap-northeast-2a` → **`artifact-public-2a`** |
| | `ap-northeast-2c` → **`artifact-public-2c`** |
| 보안 그룹 | `artifact-sg-alb-public` |
| 리스너 | HTTP : `80` → `artifact-backend-tg` |

**[로드 밸런서 생성]**

DNS 이름을 체크리스트에 적는다:
```
artifact-alb-1234567890.ap-northeast-2.elb.amazonaws.com
```

**속성 편집:**

| 속성 | 값 |
|---|---|
| 유휴 제한 시간 | **`120`**초 ← 인쇄 에이전트 롱폴링이 25초라 기본 60도 되지만, 추론 경유 때문에 늘린다 |

### ④ 백엔드 ASG

EC2 → **[Auto Scaling 그룹]** → **[Auto Scaling 그룹 생성]**

| 단계 | 값 |
|---|---|
| 이름 | `artifact-backend-asg` |
| 시작 템플릿 | `artifact-backend-lt` / Latest |
| VPC | `artifact-vpc` |
| 서브넷 | `artifact-app-2a`, `artifact-app-2c` |
| 로드 밸런싱 | 기존 로드 밸런서에 연결 → **`artifact-backend-tg`** |
| 상태 확인 | EC2 + **Elastic Load Balancing** |
| **상태 확인 유예 기간** | **`240`**초 ← Spring Boot 기동이 느리다 |
| **원하는 용량** | **`1`** |
| **최소** | **`1`** |
| **최대** | **`1`** ⚠️ §1-4 참고 |
| 크기 조정 정책 | **없음** |
| 태그 | `Name` = `artifact-backend` |

**[Auto Scaling 그룹 생성]**

> **최대를 1로 두는 것을 잊지 않는다.** 2 이상이면 §1-4의 세 가지 블로커가 즉시 발현한다.
> ASG 설명 칸에 이유를 적어두면 나중에 본인이나 팀원이 무심코 올리는 걸 막을 수 있다.

### ⑤ 검증

대상 그룹 `artifact-backend-tg`가 `healthy`가 되면:

```bash
curl -i http://artifact-alb-1234567890.ap-northeast-2.elb.amazonaws.com/actuator/health
# {"status":"UP"} 이 나와야 한다

curl -i http://artifact-alb-.../api/auth/login -X POST \
  -H 'Content-Type: application/json' \
  -d '{"loginId":"...","password":"..."}'
```

백엔드→AI 경로까지 확인하려면 로그인 후 분석 요청을 한 번 태워본다.
Session Manager로 백엔드 인스턴스에 들어가서:

```bash
sudo docker logs artifact-backend | grep -i fastapi
curl -i http://internal-artifact-ai-alb-....elb.amazonaws.com:8000/health
```

---

## 2-8. 프론트엔드 S3 정적 호스팅

### ① 버킷 생성

S3 콘솔 → **[버킷 만들기]**

| 항목 | 값 |
|---|---|
| AWS 리전 | 아시아 태평양(서울) |
| 버킷 유형 | 범용 |
| 버킷 이름 | `artifact-frontend-<임의문자열>` ← 전역 유일해야 한다 |
| 객체 소유권 | **ACL 비활성화됨** |
| **모든 퍼블릭 액세스 차단** | **체크 (전부 차단)** ⚠️ |
| 버킷 버전 관리 | 비활성화 |
| 기본 암호화 | SSE-S3 |

**[버킷 만들기]**

> **"정적 웹 사이트 호스팅"을 켜지 않는다.** 그건 버킷을 공개해야 동작하는 옛날 방식이다.
> 우리는 CloudFront + **OAC(Origin Access Control)**로 간다 — 버킷은 완전히 비공개이고
> CloudFront만 읽을 수 있다. 이게 현재 AWS 권장 방식이다.

### ② 진단 이미지용 버킷도 만든다 (`IMAGE_STORAGE_TYPE=s3`를 쓸 경우)

같은 방법으로 하나 더:

| 항목 | 값 |
|---|---|
| 버킷 이름 | `artifact-medical-images` |
| **모든 퍼블릭 액세스 차단** | **체크** ⚠️ 절대 풀지 않는다 |
| 기본 암호화 | SSE-S3 |

### ③ 프론트엔드 빌드

**로컬에서:**

```bash
cd frontend

# 살아 있는 변수는 VITE_KIOSK_BASE_URL 하나뿐이다.
# 비워두면 브라우저의 현재 호스트를 쓰므로, CloudFront 도메인에서 QR 이 그대로 맞는다.
cat .env.production

npm ci
npm run build
# dist/ 가 생긴다
```

> **API 주소를 어디에도 적지 않는다.** 호출 경로가 `/api/v1/...` 로 코드에 박혀 있어
> 빌드 산출물에 호스트가 들어가지 않는다. same-origin 으로 묶는 일은 §2-10 에서
> CloudFront 동작(`/api/*` → ALB)이 전부 한다. 이게 §1-2-①의 설계다.
>
> `.env.production` 의 `VITE_API_BASE_URL` 은 읽히지 않는 잔재다. ALB 주소로 바꿔도
> 아무 일도 일어나지 않으니, "안 먹는데?" 로 시간을 쓰지 않도록 미리 적어 둔다.

### ④ 업로드

S3 콘솔 → `artifact-frontend-xxxx` → **[업로드]** → **[폴더 추가]** → `dist` 폴더 선택

> 주의: `dist` **폴더 자체**를 올리면 `dist/index.html`이 된다. **`dist` 안의 내용**을
> 버킷 루트에 올려야 한다. 콘솔에서는 `dist` 폴더를 열고 안의 파일·폴더를 전부 선택해
> 드래그하는 게 확실하다.

CLI가 더 편하다:

```bash
aws s3 sync frontend/dist/ s3://artifact-frontend-xxxx/ --delete
```

업로드 후 버킷 루트에 이렇게 있어야 한다:

```
index.html
vite.svg
assets/
  index-xxxxx.js
  index-xxxxx.css
```

---

## 2-9. CloudFront

### ① 배포 생성

CloudFront 콘솔 → **[배포 생성]**

**원본(Origin) 설정 — S3**

| 항목 | 값 |
|---|---|
| Origin domain | 드롭다운에서 `artifact-frontend-xxxx.s3.ap-northeast-2.amazonaws.com` 선택 |
| Origin path | 비움 |
| 이름 | `s3-frontend` |
| **Origin access** | **Origin access control settings (recommended)** |
| Origin access control | **[Create new OAC]** → 이름 `artifact-oac` → **[Create]** |
| 원본 shield | No |

> ⚠️ **"이 정책을 S3 버킷에 복사하세요" 경고가 뜬다.** 배포 생성 후 §2-9-③에서 처리한다.

**기본 캐시 동작**

| 항목 | 값 |
|---|---|
| Path pattern | `Default (*)` |
| Compress objects automatically | **Yes** |
| Viewer protocol policy | **Redirect HTTP to HTTPS** |
| Allowed HTTP methods | `GET, HEAD` |
| Restrict viewer access | No |
| **Cache policy** | **CachingOptimized** |
| Origin request policy | 없음 |
| Response headers policy | `SecurityHeadersPolicy` (선택, 권장) |

**웹 애플리케이션 방화벽(WAF)**: **보안 보호 비활성화** ← 월 $5+ 절약

**설정**

| 항목 | 값 |
|---|---|
| Price class | **북미, 유럽, 아시아, 중동, 아프리카에서 사용** 또는 **북미·유럽만**(더 쌈) |
| 대체 도메인 이름(CNAME) | 비움 (나중에 §2-10) |
| Custom SSL certificate | 기본 CloudFront 인증서 |
| Supported HTTP versions | HTTP/2, HTTP/3 |
| **Default root object** | **`index.html`** ⚠️ 빠뜨리면 루트 접속이 AccessDenied |
| Standard logging | Off |
| IPv6 | On |

**[배포 생성]** → 5~15분 배포

배포 도메인 이름을 체크리스트에 적는다: `dxxxxxxxx.cloudfront.net`

### ② `/api/*` 오리진 추가 ⭐

배포 상세 → **[원본]** 탭 → **[원본 생성]**

| 항목 | 값 |
|---|---|
| **Origin domain** | `artifact-alb-1234567890.ap-northeast-2.elb.amazonaws.com` ← 직접 입력/선택 |
| Protocol | **HTTP only** ← ALB 리스너가 아직 80뿐이다. §2-10에서 HTTPS로 올린다 |
| HTTP port | `80` |
| 이름 | `alb-backend` |
| Add custom header | (선택) `X-Origin-Verify: <랜덤값>` — ALB 직접 접근 차단용 |
| Origin shield | No |

**[원본 생성]**

이어서 **[동작]** 탭 → **[동작 생성]**

| 항목 | 값 | 이유 |
|---|---|---|
| **Path pattern** | **`/api/*`** | |
| Origin and origin groups | **`alb-backend`** | |
| Compress objects automatically | Yes | |
| Viewer protocol policy | **Redirect HTTP to HTTPS** | |
| **Allowed HTTP methods** | **`GET, HEAD, OPTIONS, PUT, POST, PATCH, DELETE`** | ⚠️ API라서 전부 필요 |
| Restrict viewer access | No | |
| **Cache policy** | **`CachingDisabled`** | ⚠️ API 응답을 캐시하면 환자 A의 데이터가 환자 B에게 간다 |
| **Origin request policy** | **`AllViewer`** | ⚠️ Authorization 헤더·쿠키·쿼리를 전부 백엔드로 넘긴다. 이게 없으면 JWT가 사라져 전부 401 |
| Response headers policy | 없음 | |

**[동작 생성]**

> **이 두 정책이 이 문서에서 가장 자주 틀리는 지점이다.**
> - `CachingDisabled`가 아니면 → 인증된 응답이 캐시돼 **다른 사용자에게 노출**된다
> - `AllViewer`가 아니면 → `Authorization` 헤더가 잘려 **모든 API가 401**이 된다

**[동작]** 탭에서 순서를 확인한다. `/api/*`가 `Default (*)`보다 **위**에 있어야 한다
(우선순위 0). 아니면 선택 후 **[우선순위 이동]**.

### ③ S3 버킷 정책 적용 (OAC)

배포 상세 → **[원본]** 탭 → `s3-frontend` 선택 → **[편집]**
→ Origin access control 옆 **[정책 복사]** 버튼 클릭

S3 콘솔 → `artifact-frontend-xxxx` → **[권한]** 탭 → 버킷 정책 **[편집]**
→ 복사한 JSON 붙여넣기 → **[변경 사항 저장]**

붙여넣을 정책은 이런 모양이다:

```json
{
  "Version": "2008-10-17",
  "Id": "PolicyForCloudFrontPrivateContent",
  "Statement": [
    {
      "Sid": "AllowCloudFrontServicePrincipal",
      "Effect": "Allow",
      "Principal": { "Service": "cloudfront.amazonaws.com" },
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::artifact-frontend-xxxx/*",
      "Condition": {
        "StringEquals": {
          "AWS:SourceArn": "arn:aws:cloudfront::123456789012:distribution/EXXXXXXXXXX"
        }
      }
    }
  ]
}
```

### ④ SPA 라우팅 처리 ⭐

React Router를 쓰므로 `/kiosk/abc123`이나 `/visits/12` 같은 주소로 **직접 접속하면**
S3에 그런 파일이 없어 **403/404**가 난다. `frontend/nginx.conf`의
`try_files $uri /index.html`이 하던 일을 CloudFront에서 대신해야 한다.

배포 상세 → **[오류 페이지]** 탭 → **[사용자 정의 오류 응답 생성]**

| 항목 | 값 |
|---|---|
| HTTP 오류 코드 | **403: Forbidden** |
| 오류 캐싱 최소 TTL | `0` |
| 오류 응답 사용자 지정 | **예** |
| 응답 페이지 경로 | **`/index.html`** |
| HTTP 응답 코드 | **200: OK** |

**[생성]** → **같은 걸 `404: Not Found`로 한 번 더 만든다.**

> S3는 OAC 환경에서 없는 객체에 **403**을 준다(ListBucket 권한이 없어서). 그래서
> 403 처리가 필수다. 404도 같이 넣어두면 안전하다.

### ⑤ 검증

```
https://dxxxxxxxx.cloudfront.net/            → 로그인 화면이 떠야 한다
https://dxxxxxxxx.cloudfront.net/api/actuator/health  → {"status":"UP"}
```

브라우저 개발자 도구 → Network에서:
- `index.html`, `assets/*.js` → **`x-cache: Hit from cloudfront`** (재요청 시)
- `/api/*` → **`x-cache: Miss from cloudfront`** (항상. 캐시 안 하니 정상)

로그인까지 해본다. 401이 나면 §2-9-②의 Origin request policy가 `AllViewer`인지 확인.

### ⑥ 백엔드 환경변수 갱신 ⭐

이제 CloudFront 도메인을 알았으니 백엔드에 반영한다.

EC2 → **[시작 템플릿]** → `artifact-backend-lt` → **[작업] → [템플릿 수정(새 버전 생성)]**

사용자 데이터에서 두 줄을 실제 도메인으로 바꾼다:

```bash
  -e CORS_ALLOWED_ORIGINS='https://dxxxxxxxx.cloudfront.net' \
  -e PRINT_KIOSK_ALLOWED_BASE_URLS='https://dxxxxxxxx.cloudfront.net' \
```

**[시작 템플릿 버전 생성]**

그 다음 새 버전을 인스턴스에 반영한다:

EC2 → **[Auto Scaling 그룹]** → `artifact-backend-asg` → **[인스턴스 새로 고침]** 탭
→ **[인스턴스 새로 고침 시작]**

| 항목 | 값 |
|---|---|
| 최소 정상 비율 | `0` ← 인스턴스가 1대뿐이라 100%로는 진행이 안 된다 |
| 인스턴스 준비 시간 | `240`초 |
| 시작 템플릿 버전 | **Latest** |
| 건너뛰기 일치 | 체크 해제 |

**[인스턴스 새로 고침 시작]**

> **최소 정상 비율 0%는 짧은 다운타임을 뜻한다.** 백엔드가 1대라 어쩔 수 없다.
> 블로커가 해소돼 2대 이상이 되면 100%로 두어 무중단 배포가 된다.

---

## 2-10. 도메인과 HTTPS

> CloudFront 기본 도메인(`dxxxxxxxx.cloudfront.net`)도 HTTPS가 되므로 **이 단계는 선택**이다.
> 다만 QR 코드에 찍히는 주소가 바뀌면 인쇄된 종이가 무효가 되니, **도메인을 쓸 거면
> 지금 정하고 §2-9-⑥을 그 도메인으로 한다.**

### 선택지 A — 기존 DuckDNS 계속 쓰기

DuckDNS는 A 레코드(IP)만 지원하고 CNAME을 못 건다. CloudFront는 IP가 고정이 아니라
**DuckDNS로는 CloudFront를 가리킬 수 없다.** 도메인을 쓰려면 B로 간다.

### 선택지 B — Route 53 (월 $0.5 + 도메인 등록비)

**1) 호스팅 영역 생성**

Route 53 → **[호스팅 영역]** → **[호스팅 영역 생성]**
- 도메인 이름: 보유한 도메인 (없으면 **[도메인] → [등록]**에서 구매, `.com` 연 $14 내외)
- 유형: 퍼블릭 호스팅 영역

**2) ACM 인증서 발급 ⚠️ 리전 주의**

**CloudFront용 인증서는 반드시 `us-east-1`(버지니아 북부)에서 발급해야 한다.**

콘솔 우측 상단 리전을 **미국 동부(버지니아 북부)**로 바꾼 뒤:

ACM → **[인증서 요청]** → **퍼블릭 인증서 요청**

| 항목 | 값 |
|---|---|
| 완전히 정규화된 도메인 이름 | `artifact.example.com` |
| (선택) 다른 이름 추가 | `*.artifact.example.com` |
| 검증 방법 | **DNS 검증** |
| 키 알고리즘 | RSA 2048 |

**[요청]** → 인증서 상세 → **[Route 53에서 레코드 생성]** → **[레코드 생성]**
→ 5~30분 뒤 상태가 **[발급됨]**

**3) CloudFront에 연결**

리전을 다시 서울로 돌릴 필요는 없다(CloudFront는 글로벌).

CloudFront → 배포 → **[설정]** **[편집]**

| 항목 | 값 |
|---|---|
| 대체 도메인 이름(CNAME) | `artifact.example.com` |
| Custom SSL certificate | 방금 만든 ACM 인증서 선택 |

**[변경 사항 저장]** → 배포 대기

**4) Route 53 A 레코드(별칭)**

Route 53 → 호스팅 영역 → **[레코드 생성]**

| 항목 | 값 |
|---|---|
| 레코드 이름 | `artifact` |
| 레코드 유형 | **A** |
| **별칭** | **켜기** |
| 트래픽 라우팅 대상 | **CloudFront 배포에 대한 별칭** |
| 배포 선택 | `dxxxxxxxx.cloudfront.net` |

**[레코드 생성]**

**5) 백엔드 환경변수 재갱신** — §2-9-⑥을 새 도메인으로 다시 한다.

### ALB도 HTTPS로 (권장)

CloudFront→ALB 구간이 HTTP면 VPC 안이라 외부 노출은 없지만, 정석은 종단간 암호화다.

1. ACM에서 **서울 리전**에 같은 도메인 인증서를 하나 더 발급 (ALB용은 서울)
2. ALB → **[리스너]** 탭 → **[리스너 추가]**
   - 프로토콜 `HTTPS` 포트 `443`
   - 기본 작업: `artifact-backend-tg`로 전달
   - 보안 정책: `ELBSecurityPolicy-TLS13-1-2-2021-06`
   - 기본 SSL 인증서: 발급받은 것
3. CloudFront → 원본 `alb-backend` **[편집]** → Protocol **HTTPS only**, 포트 `443`
4. ALB의 기존 HTTP:80 리스너 → 기본 작업을 **HTTPS로 리디렉션**으로 변경

---

## 2-11. CloudWatch 알람과 스케일링 검증

### ① 필수 알람 4개

CloudWatch → **[모든 알람]** → **[알람 생성]** × 4

**알람 1 — AI 대상 그룹에 정상 인스턴스가 없음**

| 항목 | 값 |
|---|---|
| 지표 | `ApplicationELB` → `대상 그룹별, 로드밸런서별` → `artifact-ai-tg` / `HealthyHostCount` |
| 통계 | 최소 |
| 기간 | 1분 |
| 임계값 유형 | 정적 |
| 조건 | **보다 작음** `1` |
| 알람을 트리거할 데이터 포인트 | `2` / `2` |
| 알림 | 새 SNS 주제 생성 → `artifact-alerts` → 본인 이메일 |
| 알람 이름 | `ai-no-healthy-host` |

> SNS 주제를 처음 만들면 **이메일로 구독 확인 메일**이 온다. 링크를 눌러야 알림이 온다.

**알람 2 — 백엔드 대상 그룹에 정상 인스턴스가 없음**
같은 방법, 지표만 `artifact-backend-tg` / `HealthyHostCount`. 이름 `backend-no-healthy-host`

**알람 3 — ALB 5xx 급증**

| 항목 | 값 |
|---|---|
| 지표 | `ApplicationELB` → `로드밸런서별` → `artifact-alb` / `HTTPCode_ELB_5XX_Count` |
| 통계 | 합계 / 기간 5분 |
| 조건 | 보다 큼 `10` |
| 이름 | `alb-5xx-spike` |

**알람 4 — AI ASG가 최대치에 도달**

| 항목 | 값 |
|---|---|
| 지표 | `EC2 → Auto Scaling 그룹별` → `artifact-ai-asg` / `GroupInServiceInstances` |
| 통계 | 최대 / 기간 5분 |
| 조건 | 보다 크거나 같음 `4` |
| 이름 | `ai-asg-at-max` |

> 4번이 울리면 "용량이 모자라거나 비용이 새고 있다"는 뜻이다. 둘 다 알아야 할 일이다.

### ② 스케일링이 실제로 도는지 확인하기 ⭐

**이게 이번 실습의 하이라이트다.** 설정만 하고 안 돌려보면 의미가 없다.

**준비**: 백엔드 로그인 토큰과 테스트 이미지 1장

**부하 주기** — 로컬에서:

```bash
# hey 설치 (macOS)
brew install hey

# 1) AI 서버에 직접 부하 (내부 ALB는 VPC 밖에서 못 닿으므로,
#    백엔드 인스턴스에 Session Manager 로 들어가서 실행한다)
#    -n 총 요청수, -c 동시성
hey -n 300 -c 10 -m POST \
  -H "X-Internal-Secret: <SECRET>" \
  -D ./sample.jpg \
  http://internal-artifact-ai-alb-....elb.amazonaws.com:8000/predict
```

`hey`를 못 쓰면 셸 루프로도 된다:

```bash
for i in $(seq 1 200); do
  (curl -s -o /dev/null -X POST \
     -H "X-Internal-Secret: <SECRET>" \
     -F "file=@sample.jpg" \
     http://internal-artifact-ai-alb-....elb.amazonaws.com:8000/predict &)
done
wait
```

**관찰할 곳:**

1. EC2 → **[Auto Scaling 그룹]** → `artifact-ai-asg` → **[모니터링]** 탭
   → `GroupDesiredCapacity`가 1 → 2 → 3으로 오르는지
2. **[활동]** 탭 → "Launching a new EC2 instance..." 기록
3. CloudWatch → **[지표]** → `EC2 → Auto Scaling 그룹별` → `CPUUtilization`
4. 대상 그룹 `artifact-ai-tg` → **[대상]** 탭 → 인스턴스가 늘어나고 `healthy`가 되는지

**예상 타임라인:**

```
t=0     부하 시작
t=1~3분  CPU가 60% 초과 → CloudWatch 알람 ALARM
t=3~4분  ASG가 새 인스턴스 시작
t=4~7분  부팅 + ECR pull + 모델 로딩 (유예 180초)
t=7분    새 인스턴스 healthy → 트래픽 분산 시작
```

> **7분이 걸린다.** 이게 ASG의 현실이고, 이 수치 자체가 배울 점이다.
> 갑작스러운 트래픽에는 대응 못 한다. 그래서 실무에서는 예정된 부하(부스 시연 같은)에
> **예약된 작업(Scheduled Action)**을 쓴다:
>
> ASG → **[자동 크기 조정]** 탭 → **[예약된 작업 생성]**
> - 이름 `booth-warmup`, 원하는 용량 `3`, 최소 `3`, 되풀이 `0 8 * * *` (매일 08:00 UTC)
> - 종료용으로 하나 더: 원하는 용량 `1`, 최소 `1`
>
> **부스 시연에는 이쪽이 정답이다.** `docker-compose.booth.yml`이 하던 "미리 3개 띄우기"의
> 클라우드 버전이다.

**축소(scale-in) 확인**: 부하를 멈추고 **10~15분** 기다리면 인스턴스가 줄어든다.
축소는 의도적으로 느리다(플래핑 방지).

### ③ 접속 경로 정리

22번 포트가 없으므로 접속은 전부 SSM이다:

```
EC2 → [인스턴스] → 대상 선택 → [연결] → [Session Manager] 탭 → [연결]
```

CLI로도 된다:

```bash
aws ssm start-session --target i-0123456789abcdef0 --region ap-northeast-2
```

> SSM이 안 보이면: IAM 역할에 `AmazonSSMManagedInstanceCore`가 붙었는지,
> 인스턴스가 NAT/엔드포인트로 SSM 엔드포인트에 닿는지 확인한다.

### ④ 보안 마무리

검증이 끝나면 ALB 직접 접근을 막는다.

**방법 1 — CloudFront 프리픽스 리스트만 허용**

`artifact-sg-alb-public` → 인바운드 규칙 편집 → 기존 `0.0.0.0/0` 규칙 삭제 →
새 규칙:

| 유형 | 포트 | 소스 유형 | 소스 |
|---|---|---|---|
| HTTPS | 443 | **사용자 지정** | `com.amazonaws.global.cloudfront.origin-facing` (프리픽스 리스트 검색) |

**방법 2 — 커스텀 헤더 검증 (더 확실)**

§2-9-②에서 `X-Origin-Verify` 헤더를 넣었다면, ALB 리스너에 규칙을 추가한다:

ALB → **[리스너]** → HTTPS:443 → **[규칙 관리]** → **[규칙 추가]**

- 조건: **HTTP 헤더** → 헤더 이름 `X-Origin-Verify`, 값 `<랜덤값>`
- 작업: `artifact-backend-tg`로 전달
- 우선순위: 1

그 다음 **기본 규칙**을 `403 고정 응답`으로 바꾼다.
→ CloudFront를 거치지 않은 요청은 전부 403이 된다.

---

## 2-12. 정리 — 삭제 순서 ⚠️

**실습이 끝나면 반드시 지운다.** 순서가 틀리면 "종속성 때문에 삭제 불가"가 계속 뜬다.

```
 1. Auto Scaling 그룹 2개        ← 인스턴스가 자동으로 종료된다
      artifact-ai-asg, artifact-backend-asg
      (원하는/최소 용량을 0으로 먼저 내리면 깔끔하다)
 2. 남은 EC2 인스턴스 (있으면) 종료
 3. 로드 밸런서 2개              artifact-alb, artifact-ai-alb
 4. 대상 그룹 2개                artifact-backend-tg, artifact-ai-tg
 5. CloudFront 배포              [비활성화] → 15분 대기 → [삭제]
 6. S3 버킷 2개                  객체 전부 삭제 후 버킷 삭제
 7. RDS                          [삭제] → 최종 스냅샷 만들지 않음 체크
                                  (스냅샷도 과금된다)
 8. NAT 게이트웨이                ← 잊으면 계속 돈이 나간다 ⚠️
 9. 탄력적 IP 해제                NAT가 쓰던 EIP. 미연결 EIP도 과금된다 ⚠️
10. VPC 엔드포인트 (만들었다면)   ← Interface 엔드포인트는 개당 월 $8 ⚠️
11. VPC                          삭제하면 서브넷·라우팅·IGW가 같이 지워진다
12. ECR 리포지토리
13. 시작 템플릿, 보안 그룹, IAM 역할
14. CloudWatch 알람, SNS 주제
15. Route 53 호스팅 영역 (만들었다면) — 도메인 등록 자체는 환불 안 됨
16. ACM 인증서 (무료지만 정리)
```

**⚠️ 가장 많이 놓치는 3가지: NAT 게이트웨이, 미연결 탄력적 IP, Interface VPC 엔드포인트.**
셋 다 "아무것도 안 하는데 돈이 나가는" 리소스다.

삭제 후 **Billing → [비용 탐색기]**로 며칠 뒤 다시 확인한다.

---

# 파트 3 — 부록

## 3-1. 자주 막히는 지점

| 증상 | 원인 | 확인 |
|---|---|---|
| 대상이 계속 `unhealthy` ↔ 교체 반복 | **상태 확인 유예 기간이 짧다** | ASG → 상태 확인 유예 기간을 180~240초로 |
| `exec format error` | x86 이미지를 ARM 인스턴스에 올림 | `docker build --platform linux/arm64` |
| 인스턴스가 ECR pull 실패 | NAT/엔드포인트 없음, 또는 IAM 역할 미부착 | Session Manager로 들어가 `aws ecr get-login-password` 직접 실행 |
| CloudFront 루트가 `AccessDenied` | Default root object 미설정 | 배포 설정 → `index.html` |
| `/kiosk/xxx` 직접 접속이 403 | SPA 폴백 없음 | 오류 페이지 403/404 → `/index.html` 200 |
| 모든 API가 401 | Origin request policy가 `AllViewer`가 아님 | 동작 `/api/*` 편집 |
| 로그인 후 다른 사람 데이터가 보임 | **API 응답이 캐시됨** | 동작 `/api/*` Cache policy를 `CachingDisabled`로 ⚠️ 즉시 조치 |
| 한글이 `???`로 저장됨 | RDS 파라미터 그룹 `latin1` | §2-3-③ |
| 인쇄가 가끔 안 됨 | 백엔드가 2대 이상 | ASG 최대를 1로 (§1-4) |
| 업로드 이미지 404 | `IMAGE_STORAGE_TYPE=local` + 인스턴스 교체 | S3로 전환 |
| SSM 연결 버튼이 회색 | IAM 역할 또는 네트워크 | `AmazonSSMManagedInstanceCore` 확인 |
| ALB가 504 | 백엔드/AI 응답이 유휴 타임아웃 초과 | ALB 속성 → 유휴 제한 시간 120초 |

## 3-2. 이 마이그레이션이 바꾸는 기존 문서·설정

| 대상 | 변화 |
|---|---|
| `docs/ec2-deployment-guide.md` | **폐기하지 않는다.** 단일 EC2 경로로 계속 유효하고, 부스 시연은 그쪽이 더 간단하다. 상단에 "AWS ASG 경로는 이 문서 참조" 링크만 추가 |
| `docs/cicd-guide.md` | self-hosted 러너가 EC2에 붙어 있다. ASG로 가면 러너가 붙을 고정 인스턴스가 없어진다 → **CD를 "ECR push + Instance Refresh 트리거"로 바꿔야 한다.** 이 문서 범위 밖이지만 반드시 뒤따라야 할 작업 |
| `docker-compose.booth.yml` | 유지 (§1-5) |
| `docker-compose.https.yml` / Caddy | **AWS 경로에서는 안 쓴다.** TLS 종료가 CloudFront/ALB로 옮겨간다. 단일 EC2 경로에서는 계속 필요 |
| `frontend/nginx.conf` | **AWS 경로에서는 안 쓴다.** `/api` 프록시는 CloudFront 동작이, SPA 폴백은 오류 페이지가 대신한다 |
| `.env` / `PRINT_KIOSK_ALLOWED_BASE_URLS` | CloudFront 도메인으로 갱신. **이미 인쇄된 QR 종이는 무효가 된다** |
| print-agent | 접속 대상 URL을 CloudFront 도메인으로. 아웃바운드 폴링이라 그 외 변경 없음 |

## 3-3. 다음 단계 (이 문서 이후)

우선순위 순:

1. **`IMAGE_STORAGE_TYPE=s3` 구현 확인** — ASG 환경에서는 선택이 아니다
2. **`/actuator/health`를 인증 없이 200으로** — ALB 헬스체크 전제
3. **CD 파이프라인 재구성** — ECR push → Instance Refresh
4. `ShareAccessGuard` → ElastiCache Redis — 백엔드 2대의 전제조건
5. `PrintJobQueue` → SQS/Redis — 백엔드 2대의 전제조건
6. RDS Multi-AZ, ALB 액세스 로그, WAF — 실제 운영이라면

1~3번까지 하면 이 아키텍처가 온전히 동작한다. 4~5번은 백엔드 수평 확장을 실제로
원할 때 한다.

---

## 3-4. 관련 문서

- `docs/ec2-deployment-guide.md` — 단일 EC2 + 도커 컴포즈 배포 (현행 운영 경로)
- `docs/cicd-guide.md` — GitHub Actions self-hosted 러너 CD
- `docs/security-remediation-plan.md` — 보안 조치 이력과 남은 항목
- `docker-compose.prod.yml` — 운영 오버라이드 (포트 차단 근거)
- `docker-compose.booth.yml` — 부스 다중 워커 (도커 경로)
