# AWS EC2 배포 가이드 (처음 하는 사람용)

> 이 문서는 **AWS를 한 번도 써 본 적 없는 사람**을 기준으로 썼다.
> 화면에서 어떤 버튼을 누르는지, 터미널에 무엇을 치는지까지 전부 적었다.
> 명령어는 앞에서부터 순서대로 그대로 복사해 붙여 넣으면 된다.
>
> 소요 시간: **처음이면 2~3시간** (도커 빌드 대기 시간이 대부분이다)

---

## 0. 그래서 뭘 하는 건가

지금 우리 프로젝트는 각자 노트북에서 이렇게 돌아간다.

```
[내 노트북]
  브라우저 localhost:3000 → frontend(nginx) → backend:8080 → mysql:3306
                                                 ↘ fastapi:8000
```

이걸 **인터넷 어딘가에 항상 켜져 있는 남의 컴퓨터**로 옮기는 게 배포다.
그 "남의 컴퓨터"를 빌려주는 게 AWS EC2다.

```
[EC2 = 인터넷에 있는 리눅스 컴퓨터 한 대]
  누구든 브라우저 http://13.x.x.x → frontend(nginx) → backend:8080 → mysql:3306
                                                        ↘ fastapi:8000
```

**중요한 사실 하나**: 옮기는 것 자체는 어렵지 않다. 어려운 건 *옮긴 뒤에 아무나 못 들어오게 하는 것*이다.
내 노트북에서는 `localhost`라서 나만 접근할 수 있었지만, EC2에서는 **열어둔 포트가 곧 인터넷 전체에 열린 문**이다.
그래서 이 가이드는 중간중간 "이건 왜 이렇게 하는지"를 같이 적었다. 그 부분을 건너뛰면 배포는 되지만 안전하지는 않다.

### 우리가 만들 최종 모습

| 바깥에서 보이는 것 | 안에서만 도는 것 |
|---|---|
| 80번 포트 (웹사이트) — 나중에 443(HTTPS) | backend 8080, mysql 3306, fastapi 8000 |
| 22번 포트 (SSH, 우리 팀 IP만) | |

`docker-compose.prod.yml`이 이걸 자동으로 만들어 준다. 나중에 7단계에서 쓴다.

---

## 1단계 — 준비물 확인 (10분)

### 1-1. 필요한 것

| 준비물 | 설명 |
|---|---|
| AWS 계정 | 신용카드 등록 필요 (프리티어라도 카드는 있어야 한다) |
| 터미널 | Mac: 기본 `터미널` 앱 / Windows: `PowerShell` (Win+X → 터미널) |
| 팀원들의 공인 IP | https://ifconfig.me 접속하면 나오는 숫자. SSH를 열어줄 대상 |

### 1-2. 비용 — 캡스톤 기간은 사실상 무료로 할 수 있다

먼저 **흔한 오해 하나**를 짚고 간다.

> ❌ "프리티어 t3.micro 750시간 무료니까 그걸로 하면 되겠네"
>
> **안 된다.** t3.micro는 메모리가 1GB인데, 우리 프로젝트는 한 서버 안에서
> MySQL + Spring(JVM) + PyTorch + nginx를 **동시에** 돌린다. 빌드 중에 죽거나,
> 떠도 AI 분석 한 번에 메모리가 터진다. 최소 **2GB**가 필요하다.

그래서 "무료"는 **다른 방법으로** 만든다. 세 가지 길이 있고, 셋 다 실제로 쓸 수 있다.

| 길 | 방법 | 실질 비용 | 추천 |
|---|---|---|---|
| **A** | AWS 신규 계정 **크레딧**으로 결제 | 캡스톤 기간 0원 | ◎ 가장 간단 |
| **B** | **필요할 때만 켜기** (안 쓰면 중지) | 월 5천~1만원 | ◎ A와 같이 쓴다 |
| **C** | AWS 말고 **평생 무료** 클라우드 | 0원 | ○ EC2 경험은 못 쌓음 |

#### 길 A — AWS 크레딧

AWS 계정을 새로 만들면 **가입 크레딧이 지급되고**, 계정 설정·튜토리얼 같은 간단한 활동을 하면
**추가 크레딧**이 더 붙는다 (합쳐서 대략 100~200 USD 규모, 유효기간 6개월).

우리 구성은 뒤에서 정할 `t4g.small` 기준 **월 20~25 USD** 정도다.
→ **크레딧만으로 4~8개월**, 캡스톤 한 학기가 통째로 덮인다.

> 크레딧 정책과 금액은 AWS가 종종 바꾼다. 가입 직후 **결제 대시보드 → 크레딧** 메뉴에서
> 실제로 얼마가 들어왔는지 눈으로 확인하고 시작할 것.

#### 길 B — 필요할 때만 켠다 (이게 제일 크게 아낀다)

EC2는 **켜 둔 시간만큼만** 컴퓨팅 요금을 받는다. 24시간 내내 켜 둘 이유가 전혀 없다.

| 사용 패턴 | 컴퓨팅 | 디스크(항상) | 합계(대략) |
|---|---|---|---|
| 24시간 내내 | 약 15,000원 | 약 3,000원 | **월 1.8만원** |
| 하루 4시간 × 주 5일 | 약 2,000원 | 약 3,000원 | **월 5천원** |
| 시연 있는 날만 (월 20시간) | 약 600원 | 약 3,000원 | **월 3.6천원** |

작업할 때 켜고, 끝나면 끄면 된다. 켜고 끄는 데 각 1~2분이고 **데이터는 그대로 남는다**
(9단계 "인스턴스 중지" 참고). 크레딧과 합치면 캡스톤 내내 0원으로 끝난다.

> ⚠️ **고정 IP(탄력적 IP)를 쓰면 인스턴스를 꺼도 시간당 요금이 붙는다** (월 4~5천원).
> 돈을 최대한 아끼려면 **탄력적 IP를 붙이지 않고** IP가 바뀌는 걸 감수하는 방법도 있다.
> 판단 기준은 2-9에 적어 뒀다.

#### 길 C — 진짜 0원 (AWS가 아님)

"EC2를 써 보는 것"보다 "돌아가는 서버"가 목적이라면 이쪽이 더 싸다.

| 서비스 | 사양 | 조건 |
|---|---|---|
| **Oracle Cloud Always Free** | ARM 4코어 / **24GB** RAM / 200GB | 기간 제한 없이 무료. 카드 인증만 필요. 단 인기 리전은 *"Out of host capacity"* 로 생성이 며칠씩 막히기도 한다 |
| **GitHub Student Pack** | DigitalOcean 등 크레딧 | `@dgu.ac.kr` 학교 메일로 학생 인증. 서비스별로 100~200 USD 크레딧 |

다만 **"AWS EC2에 배포해 봤다"는 것 자체가 캡스톤 발표와 취업에서 쓸모가 있으므로**,
이 가이드는 **길 A + B**를 기준으로 진행한다. 크레딧이 없다면 그때 길 C를 보면 된다.

### 1-3. 어떤 인스턴스를 고를까 — ARM(t4g)을 쓴다

| 인스턴스 | CPU 종류 | 메모리 | 시간당 | 판정 |
|---|---|---|---|---|
| t3.micro | Intel | 1GB | 가장 쌈 | ❌ 메모리 부족 |
| **t4g.small** | **ARM (Graviton)** | **2GB** | t3.small보다 **약 20% 저렴** | ◎ **이걸 쓴다** |
| t3.small | Intel | 2GB | — | ○ ARM에서 문제 생기면 대안 |
| t4g.medium | ARM | 4GB | t4g.small의 2배 | ○ 여유 있으면 |

> **왜 ARM인가 — 싸서만은 아니다**
>
> 이 프로젝트를 지금 **맥북에서 도커로 돌리고 있다면 그게 이미 ARM(arm64)이다.**
> 즉 t4g는 **여러분이 지금까지 테스트해 온 것과 똑같은 아키텍처**다.
>
> 실제로 더 중요한 차이가 하나 있다. FastAPI 컨테이너의 PyTorch 때문인데:
>
> | 아키텍처 | pip가 받아오는 torch | 이미지 크기 |
> |---|---|---|
> | ARM (t4g) | CPU 전용 빌드 | **1.23GB** (실측) |
> | x86 (t3) | **GPU(CUDA) 라이브러리까지 딸려 옴** | 훨씬 큼 |
>
> 우리는 GPU가 없는데 x86에서는 GPU용 라이브러리를 수 GB 받아서 그냥 쌓아 둔다.
> ARM을 쓰면 **빌드가 빠르고, 디스크를 덜 먹고, 요금도 싸다.** 안 쓸 이유가 없다.
>
> (맥이 아니라 윈도우/인텔 PC에서 개발했다면 로컬은 x86이었겠지만, 컨테이너 안의 코드는
> 아키텍처를 타지 않으므로 그대로 ARM에서 빌드된다. 문제 생기면 t3.small로 바꾸면 그만이다)

**이 가이드는 `t4g.small` + 스왑 4GB 기준으로 진행한다.**

---

## 2단계 — EC2 인스턴스 만들기 (20분)

### 2-1. 리전(Region) 먼저 확인

1. https://console.aws.amazon.com 로그인
2. **화면 오른쪽 위**를 본다. 지역 이름이 적혀 있다 (예: `버지니아 북부`, `N. Virginia`)
3. 클릭해서 **`아시아 태평양(서울) ap-northeast-2`** 로 바꾼다

> **왜 서울인가**: 물리적으로 가까워야 빠르다. 미국 리전에 만들면 클릭 한 번마다 눈에 띄게 느려진다.
> 그리고 환자 정보를 다루는 서비스라 **국내 리전**이 맞다 (EMR사 미팅에서 나올 수 있는 질문이다).
>
> **주의**: AWS는 리전마다 완전히 다른 세상이다. 서울에서 만든 서버는 버지니아 화면에서 안 보인다.
> "만들었는데 목록에 없어요"의 90%는 리전을 잘못 본 것이다.

### 2-2. 인스턴스 시작

1. 검색창에 **`EC2`** 입력 → EC2 클릭
2. 왼쪽 메뉴 **인스턴스** → 주황색 **인스턴스 시작** 버튼

이제 긴 폼이 나온다. 위에서부터 채운다.

### 2-3. 이름과 OS

| 항목 | 입력값 |
|---|---|
| **이름** | `artifact-prod` |
| **애플리케이션 및 OS 이미지** | **Ubuntu** 탭 클릭 → `Ubuntu Server 24.04 LTS` 선택 |
| **아키텍처** | ⚠️ **`64비트(Arm)`** 으로 바꾼다 (기본값은 x86) |

> **아키텍처 드롭다운을 꼭 바꾼다.** 여기가 x86인 채로 두면 다음 단계에서 `t4g`가 목록에 안 나온다.
> "인스턴스 유형을 검색했는데 t4g가 없어요"의 원인은 100% 이것이다.

> **왜 Ubuntu인가**: 우리가 쓰는 `docker-compose.prod.yml`에는 `!reset` / `!override`라는 문법이 있는데,
> 이건 **Docker Compose v2.24 이상**에서만 동작한다. Ubuntu에 도커 공식 저장소를 붙이면 최신 버전이 깔려서 이 조건을 만족한다.
> (아마존 리눅스도 되지만 컴포즈 버전을 따로 챙겨야 해서 초보자에게는 함정이 하나 더 생긴다)

### 2-4. 인스턴스 유형

- **`t4g.small`** 선택 (검색창에 `t4g.small` 입력)

목록에 안 보이면 2-3의 **아키텍처가 `64비트(Arm)`인지** 다시 확인한다.

> ARM에서 문제가 생기면 언제든 인스턴스를 끄고 유형만 `t3.small`로 바꿀 수 있다.
> 다만 아키텍처가 바뀌면 도커 이미지를 처음부터 다시 빌드해야 한다.

### 2-5. 키 페어 — ⚠️ 이 단계가 제일 중요하다

키 페어는 **서버에 들어가는 열쇠 파일**이다. 지금 딱 한 번만 받을 수 있고, 잃어버리면 서버에 영영 못 들어간다.

1. **새 키 페어 생성** 클릭
2. 이름: `artifact-key`
3. 유형: `RSA`, 형식: **`.pem`** (Windows에서 PuTTY를 쓸 게 아니라면 pem)
4. **키 페어 생성** → `artifact-key.pem` 파일이 자동으로 다운로드된다

**다운로드된 파일을 안전한 곳으로 옮기고 권한을 잠근다.**

Mac / Linux:
```bash
mkdir -p ~/.ssh
mv ~/Downloads/artifact-key.pem ~/.ssh/
chmod 400 ~/.ssh/artifact-key.pem
```

Windows (PowerShell):
```powershell
mkdir "$env:USERPROFILE\.ssh" -Force
move "$env:USERPROFILE\Downloads\artifact-key.pem" "$env:USERPROFILE\.ssh\"
icacls "$env:USERPROFILE\.ssh\artifact-key.pem" /inheritance:r /grant:r "$($env:USERNAME):(R)"
```

> `chmod 400` = "나만 읽을 수 있음". 이걸 안 하면 SSH가 **접속을 거부한다**
> (`UNPROTECTED PRIVATE KEY FILE!` 에러). 남이 읽을 수 있는 열쇠는 열쇠가 아니라는 뜻이다.
>
> **이 .pem 파일은 절대 깃허브에 올리지 않는다.** 카톡으로 돌리는 것도 좋지 않다.
> 팀원이 접속해야 하면 6단계 아래 "팀원 추가 접속" 참고.

### 2-6. 네트워크 설정 — 방화벽(보안 그룹)

**편집** 버튼을 눌러 직접 설정한다. 여기가 "인터넷에 어떤 문을 열어둘지" 정하는 곳이다.

보안 그룹 이름: `artifact-sg`

규칙을 **3개** 만든다:

| # | 유형 | 포트 | 소스 | 설명 |
|---|---|---|---|---|
| 1 | SSH | 22 | **내 IP** (드롭다운에서 선택) | 서버 접속용. 나만 |
| 2 | HTTP | 80 | **내 IP** ← 일단 이렇게 | 웹사이트. **처음엔 좁게** |
| 3 | HTTPS | 443 | **내 IP** ← 일단 이렇게 | 8단계에서 쓴다 |

> **왜 80번을 처음부터 전체 공개(0.0.0.0/0)로 열지 않는가**
>
> 서버를 처음 띄우면 반드시 뭔가 하나는 잘못돼 있다. 그 상태를 인터넷 전체가 보고 있을 이유가 없다.
> 게다가 지금은 **HTTPS가 아직 없다** — 로그인 비밀번호와 JWT 토큰이 암호화 없이 오간다.
> 그래서 순서는 이렇게 간다:
>
> 1. **내 IP만** 열고 → 동작 확인 (7단계)
> 2. 팀원 IP 추가 → 팀 내부 테스트
> 3. HTTPS 붙이고 (8단계) → 그때 `0.0.0.0/0`으로 전체 공개
>
> 규칙은 나중에 언제든 바꿀 수 있다 (9단계에 방법 있음). 좁게 시작하는 게 손해 볼 일이 없다.

**주의**: 집·학교 인터넷의 IP는 며칠 지나면 바뀐다. 어느 날 갑자기 SSH가 안 되면
IP가 바뀐 것이니 9단계를 보고 규칙을 갱신하면 된다.

### 2-7. 스토리지(디스크)

기본값이 `8 GiB`로 되어 있다. **`30` GiB로 바꾼다.** 볼륨 유형은 `gp3`.

> **왜 30GB인가**: 도커 이미지 4개(FastAPI 1.23GB + MySQL 약 1GB + 백엔드 + 프론트)에
> **빌드 캐시**가 더 붙는다. 빌드 캐시는 눈에 안 보이는데 실제로는 이미지만큼 자리를 먹는다.
> 8GB로는 빌드 중간에 `no space left on device`로 멈춘다.
> 30GB는 AWS 프리티어 EBS 한도와 같아서, 딱 맞춰 쓰는 셈이다 (월 3천원 정도).
>
> x86(t3)을 골랐다면 torch가 GPU 라이브러리까지 받아 와 더 커진다. 30GB는 그 경우에도 버티는 값이다.

### 2-8. 시작

오른쪽 **요약** 패널에서 인스턴스 개수 `1` 확인 → **인스턴스 시작** 클릭.

1~2분 뒤 인스턴스 목록에서 상태가 **`실행 중`**, 상태 검사가 **`2/2 통과`** 가 되면 성공이다.

### 2-9. 고정 IP(탄력적 IP) — 붙일지 말지 먼저 정한다

기본 상태에서는 **인스턴스를 껐다 켜면 IP 주소가 바뀐다.** 그러면 팀 공지도 도메인 설정도 다시 해야 한다.
고정 IP(탄력적 IP)를 붙이면 이게 해결되는데, **돈이 든다.**

| 선택 | 비용 | 이럴 때 |
|---|---|---|
| **붙인다** | 월 4~5천원 (인스턴스를 꺼 둬도 계속 나감) | 8단계 HTTPS/도메인을 붙일 계획이다 / 팀원이 자주 접속한다 |
| **안 붙인다** | 0원 | 최대한 아끼고 싶다. 켤 때마다 EC2 콘솔에서 새 IP를 확인해 팀에 공유하면 된다 |

> **길 B(필요할 때만 켜기)로 최대한 아끼는 중이라면 처음엔 붙이지 않는 쪽을 권한다.**
> IP를 확인하는 건 EC2 인스턴스 목록에서 `퍼블릭 IPv4 주소` 칸을 보면 되는 일이라 번거롭지 않다.
> 나중에 도메인을 붙일 때(8단계) 그때 할당해도 순서에 문제가 없다.
>
> **안 붙이기로 했다면 아래 2-9는 건너뛰고 3단계로 간다.**

붙이기로 했다면: 고정 IP를 하나 만들어 연결한다.

1. EC2 왼쪽 메뉴 → **네트워크 및 보안** → **탄력적 IP**
2. **탄력적 IP 주소 할당** → 그냥 **할당** 클릭
3. 만들어진 IP를 체크 → **작업** → **탄력적 IP 주소 연결**
4. 인스턴스: `artifact-prod` 선택 → **연결**

> **⚠️ 요금 함정**: 탄력적 IP는 **인스턴스에 붙어 있으면 무료, 붙어 있지 않으면 시간당 요금**이 나간다.
> 나중에 프로젝트를 정리할 때 인스턴스만 삭제하고 IP를 그대로 두면 계속 돈이 빠져나간다.
> **인스턴스를 지울 때는 탄력적 IP도 반드시 "릴리스"** 한다.

이제 이 IP 주소를 메모해 둔다. 앞으로 `<서버IP>` 라고 쓰면 이 값이다.

(탄력적 IP를 안 붙이기로 했다면 EC2 인스턴스 목록의 **퍼블릭 IPv4 주소** 값이 `<서버IP>`다.
켰다 끌 때마다 바뀌니 그때그때 확인한다)

### 2-10. ⚠️ 예산 알림 설정 — 이건 반드시 한다 (5분)

클라우드 요금 사고는 대부분 **"뭔가 켜 둔 걸 잊어버려서"** 생긴다.
방학 두 달 동안 켜 둔 걸 몰랐다가 청구서를 받는 식이다. 알림을 걸어두면 그럴 일이 없다.

1. 화면 오른쪽 위 **계정 이름** 클릭 → **결제 및 비용 관리**
2. 왼쪽 메뉴 **예산(Budgets)** → **예산 생성**
3. **템플릿 사용(단순)** → **월별 비용 예산** 선택
4. 입력:
   - 예산 이름: `artifact-budget`
   - 예산 금액: **`10`** (USD) ← 우리 구성이면 이걸 넘길 일이 거의 없다
   - 이메일 수신자: 본인 메일
5. **예산 생성**

이러면 실제 사용액이 예산의 85%·100%에 닿을 때, 그리고 **이번 달 예상액이 100%를 넘을 것 같을 때**
메일이 온다. 예상 기준 알림이 있어서 사고가 나면 **월말이 아니라 며칠 안에** 알게 된다.

> 크레딧으로 결제되는 동안에도 "사용액"은 집계되므로 알림은 정상 작동한다.
> 크레딧을 얼마나 썼는지 확인하는 셈이 된다.

추가로 **결제 대시보드 → 크레딧** 메뉴에서 남은 크레딧과 만료일을 한 번 확인해 둔다.

---

## 3단계 — 서버에 접속하기 (5분)

터미널을 열고:

```bash
ssh -i ~/.ssh/artifact-key.pem ubuntu@<서버IP>
```

(Windows PowerShell이면 `~/.ssh/` 대신 `$env:USERPROFILE\.ssh\`)

처음 접속하면 이렇게 물어본다:

```
The authenticity of host '...' can't be established.
Are you sure you want to continue connecting (yes/no/[fingerprint])?
```

**`yes`** 를 치고 엔터. 그러면 이런 화면이 나온다:

```
Welcome to Ubuntu 24.04.1 LTS (GNU/Linux ...)
ubuntu@ip-172-31-x-x:~$
```

**접속 성공.** 이제부터 치는 명령어는 전부 **내 노트북이 아니라 EC2 안에서** 실행된다.
(프롬프트가 `ubuntu@ip-...` 로 시작하면 서버 안이다. 헷갈릴 때 이걸 보면 된다)

### 안 될 때

| 증상 | 원인과 해결 |
|---|---|
| 한참 멈췄다가 `Connection timed out` | 보안 그룹 22번 소스가 내 IP가 아님. 2-6 규칙 확인 (IP가 바뀌었을 수도) |
| `Permission denied (publickey)` | 사용자명이 틀림. Ubuntu는 `ubuntu@`, 아마존리눅스는 `ec2-user@` |
| `UNPROTECTED PRIVATE KEY FILE!` | `chmod 400 ~/.ssh/artifact-key.pem` 안 함 |
| `No such file or directory` | .pem 경로가 틀림. `ls ~/.ssh/` 로 파일이 거기 있는지 확인 |

---

## 4단계 — 서버 준비: 스왑 + 도커 (20분)

**여기부터는 전부 EC2 안에서 실행한다.**

### 4-1. 패키지 최신화

```bash
sudo apt-get update && sudo apt-get upgrade -y
```

중간에 파란 화면으로 뭔가 물어보면 그냥 엔터(기본값)로 넘어간다.

### 4-2. 스왑 4GB 만들기 (메모리 2GB 인스턴스면 필수)

스왑은 **메모리가 모자랄 때 디스크를 메모리처럼 빌려 쓰는 것**이다.
느리지만, 없으면 도커 빌드 중에 프로세스가 그냥 `Killed` 되면서 죽는다.

```bash
sudo fallocate -l 4G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

확인:
```bash
free -h
```

`Swap:` 줄에 `4.0Gi`가 보이면 된다.

> 마지막 `/etc/fstab` 줄은 "서버를 재부팅해도 스왑을 다시 켜라"는 뜻이다. 이걸 빼면
> 재부팅 후 조용히 사라져서, 몇 주 뒤 재배포할 때 원인 모를 빌드 실패를 만난다.

### 4-3. 도커 설치

도커 **공식 저장소**에서 설치한다 (`apt install docker.io`가 아니다 — 그건 컴포즈 버전이 낮다).

```bash
# 1) 저장소 등록에 필요한 도구
sudo apt-get install -y ca-certificates curl gnupg

# 2) 도커 GPG 키 등록
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

# 3) 저장소 추가
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# 4) 설치
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

### 4-4. sudo 없이 도커 쓰기

```bash
sudo usermod -aG docker $USER
```

이 설정은 **다시 로그인해야** 적용된다. 접속을 끊고 다시 들어온다:

```bash
exit
```
```bash
ssh -i ~/.ssh/artifact-key.pem ubuntu@<서버IP>
```

### 4-5. 설치 확인 — ⚠️ 버전 체크

```bash
docker --version
docker compose version
docker run --rm hello-world
```

**`docker compose version`이 `v2.24` 이상**인지 반드시 확인한다.

```
Docker Compose version v2.3x.x   ← 이렇게 나오면 OK
```

> 2.24 미만이면 `docker-compose.prod.yml`의 `!reset` / `!override`가 동작하지 않고,
> **MySQL 3306번 포트가 인터넷에 그대로 열린 채로 뜬다.** 조용히 실패하는 종류라 더 위험하다.
> 버전이 낮으면 4-3을 다시 확인한다 (`docker.io` 패키지가 이미 깔려 있으면 `sudo apt-get remove docker.io` 후 재설치).

---

## 5단계 — 코드 가져오기 (5분)

```bash
cd ~
git clone https://github.com/CSID-DGU/2026-1-CECD1-5-Artifact-9.git artifact
cd artifact
```

우리 저장소는 공개(public)라 로그인 없이 받아진다.

받아진 파일 확인:
```bash
ls -a
```

`docker-compose.yml`, `docker-compose.prod.yml`, `.env.production.example` 이 세 개가 보이면 정상이다.

> **비공개 저장소로 바뀌었다면**: GitHub → Settings → Developer settings → Personal access tokens
> 에서 `repo` 권한 토큰을 만들어 `git clone https://<토큰>@github.com/...` 형태로 받는다.
> 이때 토큰이 서버의 `~/.bash_history`에 남으니, 클론 후 `history -c` 로 지운다.

---

## 6단계 — 비밀값(.env) 만들기 (15분)

여기가 **보안상 가장 중요한 단계**다. 천천히 한다.

### 6-1. 템플릿 복사

```bash
cd ~/artifact
cp .env.production.example .env
```

### 6-2. 비밀값 3개 생성

아래 명령을 **그대로** 실행하면 무작위 값 3개가 출력된다.

```bash
echo "JWT_SECRET=$(openssl rand -base64 48)"
echo "INTERNAL_API_SECRET=$(openssl rand -base64 32)"
echo "DB_PASSWORD=$(openssl rand -base64 24)"
```

출력 예시 (여러분의 값은 다르다):
```
JWT_SECRET=Kx9mPq...
INTERNAL_API_SECRET=7Hn2Vb...
DB_PASSWORD=Qw8Lm4...
```

**이 세 줄을 마우스로 복사해 둔다.**

> **각각이 뭐냐면**
>
> | 값 | 없거나 유출되면 |
> |---|---|
> | `JWT_SECRET` | 로그인 토큰의 도장. 이걸 아는 사람은 **로그인 없이 "나는 관리자다"라는 토큰을 직접 만든다.** 예전에 이 값의 기본값이 공개 저장소 코드에 적혀 있었고, 그게 이슈 #1로 잡아낸 문제다 |
> | `INTERNAL_API_SECRET` | FastAPI에는 로그인이 없다. 백엔드가 이 값을 헤더로 보내서 "내가 백엔드다"를 증명한다 |
> | `DB_PASSWORD` | MySQL root 비밀번호. 환자 데이터 전체 |
>
> **셋 다 로컬 `.env`의 값을 가져오면 안 된다.** 로컬 값은 이미 팀원 노트북 여러 대와 채팅방을 거쳤다고 봐야 한다.
> 운영 서버의 값은 **EC2 안에서 만들어 EC2 밖으로 안 나가는 것**이 원칙이다.
> (그래서 위 명령도 EC2 안에서 실행하게 되어 있다)

### 6-3. .env 편집

```bash
nano .env
```

`nano`는 터미널 안의 메모장이다. 방향키로 이동하고, 그냥 타이핑하면 입력된다.

채워야 할 곳:

```bash
JWT_SECRET=            ← 위에서 만든 값 붙여넣기
INTERNAL_API_SECRET=   ← 위에서 만든 값 붙여넣기
DB_PASSWORD=           ← 위에서 만든 값 붙여넣기

ADMIN_LOGIN_ID=admin
ADMIN_PASSWORD=        ← 직접 정한다. 최소 12자 이상, 로컬에서 쓰던 짧은 값 재사용 금지

GEMINI_API_KEY=        ← 처방 코멘트 기능을 쓸 거면 입력. 비워도 나머지는 정상 동작
```

나머지(`IMAGE_STORAGE_TYPE=local`, `KIOSK_AUTO_PENDING=false` 등)는 **그대로 둔다.**

**저장하고 나가기**: `Ctrl + O` → 엔터 → `Ctrl + X`

> 붙여넣기가 안 되면: Mac 터미널은 `Cmd+V`, Windows PowerShell은 **마우스 오른쪽 클릭**이 붙여넣기다.

### 6-4. 파일 권한 잠그기

```bash
chmod 600 .env
```

"이 파일은 나만 읽을 수 있음". 서버에 다른 계정이 생기더라도 비밀값을 못 읽는다.

### 6-5. 확인 (값은 보지 않고 구조만)

```bash
grep -c '=' .env          # 항목 개수
grep -E '^(JWT_SECRET|INTERNAL_API_SECRET|DB_PASSWORD|ADMIN_PASSWORD)=$' .env
```

두 번째 명령이 **아무것도 출력하지 않으면** 4개 필수값이 다 채워진 것이다.
뭔가 출력되면 그 줄이 비어 있다는 뜻이니 6-3으로 돌아간다.

> **⚠️ `DB_PASSWORD`는 지금 정한 값이 끝이다.** MySQL은 **데이터 볼륨을 처음 만들 때 딱 한 번**
> 이 비밀번호를 적용한다. 나중에 `.env`만 고쳐도 이미 만들어진 DB의 비밀번호는 안 바뀐다
> (바꾸려면 볼륨을 지워야 하고, 그러면 데이터가 다 날아간다). 지금 제대로 정하고 넘어간다.

---

## 7단계 — 실행하고 확인하기 (30~50분)

### 7-1. 빌드 + 실행

```bash
cd ~/artifact
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

**이 명령을 뜯어보면**

| 조각 | 의미 |
|---|---|
| `-f docker-compose.yml` | 기본 설정 (컨테이너 4개 정의) |
| `-f docker-compose.prod.yml` | 그 위에 덮어쓰는 운영 설정 — **MySQL·backend 포트를 닫고, 프론트를 80번으로, Swagger를 끈다** |
| `up -d` | 백그라운드로 띄운다 (`-d` 없으면 터미널을 닫는 순간 서버도 꺼진다) |
| `--build` | 소스에서 이미지를 새로 만든다 |

> **`-f`를 두 개 다 쓰는 게 핵심이다.** 하나만 쓰면(`docker compose up -d`) 로컬 개발 설정으로 떠서
> **MySQL 3306번과 백엔드 8080번이 인터넷에 열린다.** 앞으로도 항상 두 개를 같이 쓴다.

### 7-2. 기다린다

**첫 빌드는 15~30분 걸린다** (t4g.small 기준. x86이면 torch가 커서 더 오래 걸린다).
PyTorch 다운로드와 Gradle 빌드가 대부분이다.
화면이 멈춘 것처럼 보여도 정상이니 기다린다. 두 번째부터는 캐시가 있어 훨씬 빠르다.

> 여기서 SSH 연결이 끊기면 빌드도 같이 죽는다. 불안하면 `tmux` 안에서 돌린다:
> `sudo apt-get install -y tmux && tmux` 로 들어가서 빌드 명령을 실행하면,
> 연결이 끊겨도 계속 돌아간다. 재접속 후 `tmux attach` 로 다시 붙는다.

마지막에 이런 게 나오면 성공:
```
 ✔ Container artifact-mysql     Healthy
 ✔ Container artifact-fastapi   Started
 ✔ Container artifact-backend   Started
 ✔ Container artifact-frontend  Started
```

### 7-3. 컨테이너 상태 확인

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps
```

4개 모두 `Up` 이어야 한다. `Restarting`이면 그 컨테이너가 계속 죽고 있다는 뜻이다 (아래 문제해결 참고).

### 7-4. ⚠️ 포트가 제대로 닫혔는지 확인 — 이 검사를 꼭 한다

```bash
docker ps --format 'table {{.Names}}\t{{.Ports}}'
```

**이렇게 나와야 정상이다:**

```
NAMES               PORTS
artifact-frontend   0.0.0.0:80->80/tcp
artifact-backend    8080/tcp
artifact-fastapi    8000/tcp
artifact-mysql      3306/tcp, 33060/tcp
```

- `0.0.0.0:80->80` — **밖에서 들어올 수 있음** (프론트만 이래야 한다)
- `8080/tcp` (앞에 `0.0.0.0:` 없음) — **컨테이너 안에서만 열림.** 정상

만약 `0.0.0.0:3306->3306` 이나 `0.0.0.0:8080->8080` 이 보이면 **즉시 중단하고**
(`docker compose ... down`) 4-5의 컴포즈 버전과 7-1의 `-f` 두 개를 다시 확인한다.
인터넷에 DB를 열어둔 상태다.

### 7-5. 동작 확인

서버 안에서:
```bash
curl -I http://localhost/
```
`HTTP/1.1 200 OK` 가 나오면 nginx가 살아 있다.

```bash
curl -i http://localhost/api/v1/patients
```
아래처럼 나오면 **정상이다.** 로그인 안 한 요청을 백엔드가 막고 있다는 뜻이다
(이슈 #3에서 막고, `fix/#66`에서 응답 형태를 정리했다).

```
HTTP/1.1 401
Content-Type: application/json;charset=UTF-8

{"timestamp":"...","status":401,"message":"로그인이 필요합니다. 다시 로그인해 주세요."}
```

여기서 `200`에 환자 목록이 나오면 그게 사고다.

> 📌 **401과 403은 다른 뜻이다.**
> `401`은 "로그인이 안 됐다" — 다시 로그인하면 풀린다.
> `403`은 "로그인은 됐는데 그 직책으로는 못 한다" — 다시 로그인해도 그대로다.
> 프론트가 이 둘을 구분해서 처리하므로(만료면 로그인 화면으로, 권한이면 안내 문구),
> 위 확인에서 **본문 없는 403**이 나온다면 배포된 이미지가 옛날 것이다. 7-1을 다시 한다.

이제 **내 노트북 브라우저**에서:
```
http://<서버IP>
```

로그인 화면이 뜨면 배포 성공이다. 6-3에서 정한 `admin` 계정으로 로그인해 본다.

### 7-6. 로그인 후 최초 점검

| 확인 | 방법 |
|---|---|
| ADMIN 로그인 | `.env`에 넣은 `ADMIN_LOGIN_ID` / `ADMIN_PASSWORD` |
| Swagger가 꺼졌는지 | `http://<서버IP>/api/swagger-ui/index.html` → **안 열려야 정상** |
| 환자 등록 | 화면에서 환자 하나 등록해 본다 |
| AI 분석 | 이미지 업로드 → 분석 → Grad-CAM 히트맵이 나오는지 (FastAPI 시크릿 연동 확인) |
| 처방 저장 | 의사 계정으로 처방까지 저장 |

**AI 분석이 500 에러면** FastAPI 시크릿 불일치일 가능성이 높다:
```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs fastapi | tail -30
```
`401` 관련 로그가 보이면 `.env`의 `INTERNAL_API_SECRET`이 양쪽에 같은 값으로 들어갔는지 확인한다
(같은 `.env`를 쓰므로 보통 문제없지만, 값에 공백이 섞이면 어긋날 수 있다).

### 7-7. 관리자 계정 정리

ADMIN 계정이 만들어졌으면, `.env`에서 그 두 줄을 비워두는 편이 안전하다
(서버 파일에 관리자 비밀번호가 평문으로 남아 있을 이유가 없다).

```bash
nano .env
```
```bash
ADMIN_LOGIN_ID=
ADMIN_PASSWORD=
```
저장 후:
```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```
(이미 DB에 계정이 만들어져 있으므로 로그인은 계속 된다)

### 7-8. 팀원에게 열어주기

여기까지 확인됐으면 보안 그룹에 팀원 IP를 추가한다.

1. EC2 → 인스턴스 → `artifact-prod` 클릭 → **보안** 탭 → 보안 그룹 클릭
2. **인바운드 규칙 편집**
3. HTTP(80) 규칙에서 **규칙 추가**로 팀원 IP를 하나씩 추가 (`x.x.x.x/32` 형식)

**아직 `0.0.0.0/0`(전체 공개)로 열지 않는다.** HTTPS를 붙인 다음에 연다.

---

## 8단계 — 도메인 + HTTPS (1시간)

> **지금 왜 이게 필요한가**
>
> 지금 상태는 `http://`다. 이 말은 **로그인할 때 입력한 아이디·비밀번호와 이후의 모든 JWT 토큰이
> 암호화 없이 인터넷을 지나간다**는 뜻이다. 같은 카페 와이파이에 있는 사람이 그대로 읽을 수 있다.
> 이슈 #1에서 토큰 위조를 막아 놨는데, HTTP로 두면 **위조할 필요 없이 진짜 토큰을 주워 가면 된다.**
>
> 그래서 **전체 공개(0.0.0.0/0)는 HTTPS를 붙인 뒤에** 한다.

### 8-1. 도메인이 필요하다

HTTPS 인증서는 **IP 주소로는 발급받을 수 없다.** 도메인 이름이 있어야 한다.

| 방법 | 비용 | 비고 |
|---|---|---|
| DuckDNS (https://www.duckdns.org) | 무료 | `artifact-med.duckdns.org` 같은 주소. 구글 로그인만 하면 5분. **캡스톤에는 이걸 권장** |
| 가비아·Route 53 등에서 구매 | 연 1~2만원 | 발표용으로 그럴듯한 주소가 필요하면 |

**DuckDNS 기준 설정:**
1. duckdns.org 로그인 → 원하는 이름 입력 → `add domain`
2. `current ip` 칸에 서버 IP를 넣고 `update ip`

> ⚠️ **여기서부터는 탄력적 IP가 필요하다.** 도메인은 특정 IP를 가리키는데, 인스턴스를 껐다 켤 때마다
> IP가 바뀌면 도메인이 엉뚱한 곳(또는 없는 곳)을 가리키게 되고 HTTPS 인증서 갱신도 실패한다.
> 2-9를 건너뛰었다면 지금 돌아가서 탄력적 IP를 할당·연결한 뒤 그 값을 넣는다.
>
> (DuckDNS는 IP가 바뀔 때마다 갱신해 주는 스크립트를 제공하긴 하지만, 갱신 사이 몇 분의
> 공백 동안 서비스가 안 열린다. 시연 도중 그러면 곤란하므로 고정 IP를 쓰는 편이 낫다)

확인 (내 노트북에서):
```bash
ping artifact-med.duckdns.org
```
탄력적 IP가 나오면 연결된 것이다. (DNS 반영에 몇 분 걸릴 수 있다)

### 8-2. 보안 그룹에 80·443을 전체 공개로

인증서를 발급받으려면 Let's Encrypt 서버가 **바깥에서 우리 서버의 80번 포트로 접속**해야 한다.
그래서 이 시점에 80·443을 열어야 한다.

1. 보안 그룹 → 인바운드 규칙 편집
2. HTTP(80) 소스 → **`Anywhere-IPv4` (0.0.0.0/0)**
3. HTTPS(443) 소스 → **`Anywhere-IPv4` (0.0.0.0/0)**
4. **SSH(22)는 그대로 팀 IP만 둔다** — 이건 절대 전체 공개하지 않는다

### 8-3. Caddy로 HTTPS 붙이기

인증서 발급·갱신을 자동으로 해 주는 **Caddy**를 프론트 앞에 세운다.
(certbot으로 직접 하는 방법도 있지만, 갱신 크론까지 챙겨야 해서 초보자에게는 실수 지점이 많다.
Caddy는 발급·자동갱신·HTTP→HTTPS 리다이렉트를 전부 알아서 한다.)

**① Caddy 설정 파일 만들기**

```bash
mkdir -p ~/artifact/docker/caddy
nano ~/artifact/docker/caddy/Caddyfile
```

아래를 붙여 넣고 **도메인만 본인 것으로 바꾼다**:

```
artifact-med.duckdns.org {
    reverse_proxy frontend:80
    request_body {
        max_size 25MB
    }
}
```

> `max_size 25MB`는 의료 이미지 업로드 때문이다. nginx 쪽 `client_max_body_size 25m`과 맞췄다.

**② 컴포즈 오버라이드 파일 만들기**

```bash
nano ~/artifact/docker-compose.https.yml
```

```yaml
# HTTPS 오버라이드. prod 위에 한 겹 더 얹는다.
#   docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.https.yml up -d
services:

  frontend:
    # 80번을 Caddy가 가져가므로 프론트는 밖으로 열지 않는다.
    ports: !reset []

  caddy:
    image: caddy:2-alpine
    container_name: artifact-caddy
    restart: unless-stopped
    depends_on:
      - frontend
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./docker/caddy/Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy-data:/data      # 발급받은 인증서 보관. 지우면 재발급됨
      - caddy-config:/config
    networks:
      - artifact-network

volumes:
  caddy-data:
  caddy-config:
```

**③ 실행**

```bash
cd ~/artifact
docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.https.yml up -d
```

**④ 인증서 발급 확인**

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.https.yml logs caddy | tail -30
```

`certificate obtained successfully` 같은 줄이 보이면 성공이다. 보통 10~30초 걸린다.

브라우저에서 `https://artifact-med.duckdns.org` 접속 → **주소창에 자물쇠**가 보이면 끝이다.
`http://`로 들어가도 Caddy가 자동으로 `https://`로 보낸다.

> **발급이 실패하면**
> - 도메인이 정말 우리 IP를 가리키는지 (`ping`)
> - 보안 그룹 80번이 `0.0.0.0/0`인지
> - Let's Encrypt는 **같은 도메인에 대한 실패 시도 횟수 제한**이 있다. 여러 번 실패했으면 한 시간쯤 뒤에 다시 시도한다

**⑤ 앞으로의 실행 명령**

HTTPS를 붙인 뒤로는 `-f`가 **세 개**다. 9단계의 명령들도 전부 이 형태로 바꿔 쓴다.

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.https.yml <명령>
```

매번 치기 번거로우니 별칭을 만들어 둔다:

```bash
echo "alias dcp='docker compose -f ~/artifact/docker-compose.yml -f ~/artifact/docker-compose.prod.yml -f ~/artifact/docker-compose.https.yml'" >> ~/.bashrc
source ~/.bashrc
```

이제 `dcp ps`, `dcp logs -f backend` 처럼 쓸 수 있다.

**⑥ 만든 파일 커밋하기**

`docker/caddy/Caddyfile`과 `docker-compose.https.yml`은 비밀값이 없으므로 저장소에 넣어두는 게 좋다.
(다음에 서버를 다시 만들 때 이 작업을 반복하지 않아도 된다)

---

## 9단계 — 운영하기

이후 명령은 8단계 ⑤의 `dcp` 별칭을 만들었다고 가정한다.
아직 HTTPS 전이면 `dcp` 대신 `docker compose -f docker-compose.yml -f docker-compose.prod.yml` 을 쓴다.

### 코드를 고쳤을 때 재배포

```bash
cd ~/artifact
git pull
dcp up -d --build
```

바뀐 컨테이너만 다시 만들어진다. **DB 데이터는 볼륨에 있으므로 사라지지 않는다.**

### 로그 보기

```bash
dcp logs -f backend      # 실시간 (Ctrl+C로 나감)
dcp logs --tail 100 backend
dcp logs --tail 50 fastapi
```

### 상태 / 재시작 / 정지

```bash
dcp ps                   # 상태
dcp restart backend      # 하나만 재시작
dcp down                 # 전부 정지 (데이터는 남음)
dcp up -d                # 다시 시작
```

> **`down -v` 는 절대 치지 않는다.** `-v`는 볼륨까지 지우는 옵션이라 **DB와 업로드 이미지가 전부 사라진다.**

### DB 백업

```bash
mkdir -p ~/backup
docker exec artifact-mysql sh -c \
  'mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" artifact_db' \
  | gzip > ~/backup/artifact_$(date +%Y%m%d_%H%M).sql.gz
```

내 노트북으로 가져오기 (**내 노트북 터미널에서** 실행):
```bash
scp -i ~/.ssh/artifact-key.pem ubuntu@<서버IP>:~/backup/*.gz ./
```

> 시연이나 큰 변경 전에는 반드시 한 번 떠 둔다. EC2 인스턴스를 잘못 지우면 볼륨도 같이 사라진다.

### 디스크가 찼을 때

```bash
df -h                    # 사용량 확인
docker system prune -a   # 안 쓰는 이미지·캐시 정리 (실행 중 컨테이너는 안 건드림)
```

첫 빌드 후 `docker system prune` 한 번 돌리면 몇 GB가 빈다.

### 비용 절감 — 안 쓸 때는 끈다 (제일 큰 절약)

**작업이 끝나면 끄는 습관을 들인다.** 이것만으로 요금이 1/5 이하로 떨어진다 (1-2의 표 참고).

- **중지(Stop)**: EC2 → 인스턴스 선택 → **인스턴스 상태** → **인스턴스 중지**
  - 컴퓨팅 요금이 **멈춘다**. 디스크(EBS)·탄력적 IP 요금만 계속 나간다
  - **데이터는 전부 보존된다** — DB도, 업로드한 이미지도, `.env`도 그대로다

- **시작(Start)**: **인스턴스 상태** → **인스턴스 시작**
  - 도커 컨테이너는 `restart: unless-stopped` 설정이라 **자동으로 다시 뜬다** (1~2분)
  - 탄력적 IP를 붙였으면 IP는 그대로. 안 붙였으면 **IP가 바뀌었으니 목록에서 새로 확인**한다
  - 확인: `dcp ps` 로 4개 다 `Up` 인지

- **종료(Terminate)**: 인스턴스를 **영구 삭제**한다. 디스크와 DB가 전부 사라지고 되돌릴 수 없다.
  프로젝트를 정리할 때만 쓰고, 그 전에 반드시:
  1. **DB 백업**을 떠서 내 노트북으로 받는다 (위 "DB 백업")
  2. **탄력적 IP를 릴리스**한다 (안 하면 계속 과금된다)
     → EC2 → 탄력적 IP → 선택 → 작업 → **탄력적 IP 주소 릴리스**

> **습관으로 만드는 법**: 하루 작업을 끝낼 때 SSH에서 `exit` 치기 전에
> AWS 콘솔 탭을 열어 인스턴스를 중지한다. 예산 알림(2-10)이 있으면 잊어도 며칠 안에 알게 된다.

### 인스턴스 사양을 바꾸고 싶을 때

메모리가 부족하거나(빌드가 계속 죽음) 반대로 과하다 싶으면 유형을 바꿀 수 있다.

1. 인스턴스를 **중지**한다 (실행 중에는 못 바꾼다)
2. 작업 → **인스턴스 설정** → **인스턴스 유형 변경**
3. `t4g.small` ↔ `t4g.medium` 처럼 **같은 계열 안에서** 고른다
4. 다시 시작

> ⚠️ **ARM(t4g) ↔ x86(t3) 사이는 유형 변경으로 못 넘어간다.** 아키텍처가 다르면
> 인스턴스를 새로 만들어야 하고, 도커 이미지도 전부 다시 빌드된다.
> 그래서 2-4에서 t4g를 고른 뒤에는 웬만하면 t4g 계열 안에서 움직인다.

### 내 IP가 바뀌어 SSH가 안 될 때

집·학교 IP는 주기적으로 바뀐다.
1. https://ifconfig.me 에서 새 IP 확인
2. EC2 → 보안 그룹 → 인바운드 규칙 편집 → SSH 규칙의 소스를 `내 IP`로 다시 선택 → 저장

---

## 문제 해결 모음

| 증상 | 원인 | 해결 |
|---|---|---|
| 인스턴스 유형 검색에 `t4g`가 안 나옴 | 2-3 아키텍처가 x86으로 되어 있음 | `64비트(Arm)`으로 변경 |
| 빌드 중 `Killed` 또는 갑자기 멈춤 | 메모리 부족 | 4-2 스왑 설정. 그래도 안 되면 `t4g.medium`으로 유형 변경 (9단계) |
| `exec format error` / `no matching manifest for linux/arm64` | ARM에서 지원 안 되는 이미지 | 우리가 쓰는 이미지(mysql, python, gradle, temurin, node, nginx, caddy)는 전부 ARM을 지원한다. 새 이미지를 추가했다면 그게 원인 |
| `no space left on device` | 디스크 부족 | `docker system prune -a`. 근본적으로는 EBS를 30GB로 (2-7) |
| `permission denied ... docker.sock` | 도커 그룹 반영 안 됨 | 4-4 후 **재접속**했는지 확인 |
| `DB_PASSWORD가 설정되지 않았습니다` | `.env`가 없거나 값이 빔 | `docker compose` 를 **`~/artifact` 디렉터리 안에서** 실행했는지 확인. 파일명이 `.env`가 맞는지 (`.env.production` 아님) |
| `JWT_SECRET이 설정되지 않았습니다` | 위와 동일 | 6-5의 확인 명령 실행 |
| backend가 `Restarting` 반복 | DB 접속 실패가 대부분 | `dcp logs backend`. `Access denied for user 'root'` 면 아래 항목 |
| `Access denied for user 'root'` | `.env`의 DB_PASSWORD를 **나중에** 바꿈 | MySQL은 첫 볼륨 생성 때의 값을 유지한다. 데이터를 버려도 되면 `dcp down` → `docker volume rm artifact_mysql-data` → 다시 `up` |
| 브라우저 502 Bad Gateway | 백엔드가 아직 기동 중 | 1~2분 기다린다. 계속되면 `dcp logs backend` |
| 브라우저가 아예 응답 없음 | 보안 그룹 80번 미개방 / IP 바뀜 | 2-6, 7-8 확인. `curl -I http://localhost/` 를 **서버 안에서** 해보면 서버 문제인지 네트워크 문제인지 갈린다 |
| AI 분석만 500 | FastAPI 시크릿 불일치 | `dcp logs fastapi`. `.env`의 `INTERNAL_API_SECRET` 값에 줄바꿈·공백이 섞였는지 확인 |
| `docker ps`에 `0.0.0.0:3306` 이 보임 | `-f` 하나만 씀 / 컴포즈 버전 낮음 | 즉시 `dcp down`. 4-5와 7-1 확인 |
| Swagger가 열림 | prod 오버라이드 미적용 | 위와 같은 원인 |

---

## 배포 전 최종 점검표

배포를 끝냈다고 말하기 전에 이 목록을 위에서부터 확인한다.

- [ ] `docker ps` 결과에서 **`0.0.0.0:` 이 붙은 포트가 80(과 443) 뿐**이다
- [ ] `http://<서버IP>/api/v1/patients` 가 **401**을 반환한다 (환자 목록이 나오지 않는다)
- [ ] Swagger UI가 **열리지 않는다**
- [ ] `.env` 권한이 `600` 이다 (`ls -l .env` 로 `-rw-------` 확인)
- [ ] `JWT_SECRET` / `INTERNAL_API_SECRET` / `DB_PASSWORD` 가 **EC2에서 새로 만든 값**이다 (로컬 복사 아님)
- [ ] `ADMIN_PASSWORD` 가 12자 이상이고, 계정 생성 후 `.env`에서 비웠다
- [ ] 보안 그룹 **SSH(22)가 전체 공개가 아니다**
- [ ] DB 백업을 한 번 떠서 내 노트북에 받아 봤다
- [ ] (전체 공개 전) **HTTPS 자물쇠가 보인다**
- [ ] **예산 알림(2-10)을 설정했다**
- [ ] 탄력적 IP를 만들었다면 인스턴스에 **연결된 상태**다 (미연결 상태로 방치하면 요금만 나간다)
- [ ] 오늘 작업이 끝났다면 **인스턴스를 중지했다**

---

## 참고

- 남은 보안 과제와 우선순위: [security-remediation-plan.md](security-remediation-plan.md)
- 운영 오버라이드가 정확히 무엇을 바꾸는지: 저장소 루트의 `docker-compose.prod.yml` 주석
- 환경변수 각각의 의미: 저장소 루트의 `.env.production.example` 주석

> **이 문서는 배포하면서 같이 고쳐 나가는 문서다.** 여기 안 적힌 에러를 만나 해결했다면
> "문제 해결 모음" 표에 한 줄 추가해 두면 다음 사람이 같은 데서 막히지 않는다.
