# print-agent — 감열지 영수증 프린터 에이전트

병원 접수 데스크의 **SEWOO SLK-TS100**(80mm 감열지, 203dpi)으로 접수증·진료요약서·
발급확인증을 뽑는 로컬 HTTP 에이전트다. FastAPI 한 프로세스이고, 백엔드 컨테이너가
HTTP 로 호출하면 USB 로 프린터에 ESC/POS 바이트를 흘려보낸다.

```
[백엔드 컨테이너] --HTTP--> [print-agent (맥 호스트)] --USB--> [SLK-TS100]
```

## 왜 도커에 안 넣나

Docker Desktop for Mac 은 USB 패스스루를 지원하지 않는다. 컨테이너 안에서는
`/dev/bus/usb` 자체가 없어서 pyusb 가 장치를 찾지 못한다. 그래서 **print-agent 만
맥 호스트에서 직접 실행**하고, 백엔드는 `host.docker.internal:5051` 로 부른다.

## 왜 CUPS 가 아니라 pyusb 인가

macOS 14 부터 CUPS 의 raw 대기열(`lpadmin -m raw`)이 폐기됐다. 등록해도
"원본 대기열이 macOS에서 더 이상 지원되지 않습니다" 로 거부된다. 그래서 CUPS 를
거치지 않고 python-escpos 의 `Usb(VID, PID)` 로 직접 쓴다. **sudo 없이 동작한다.**

---

## 1. 설치 (macOS)

Python 3.11 이상이면 된다. **검증 환경은 Python 3.14.6** 이다.

```bash
# 1) 시스템 라이브러리
brew install libusb    # pyusb 가 USB 장치를 열 때 쓴다
brew install cairo     # cairosvg 가 도장 SVG 를 PNG 로 굽는 데 쓴다

# 2) 파이썬 가상환경
cd print-agent
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# 3) 설정 파일
cp .env.example .env
# .env 를 열어 KIOSK_BASE_URL / QR_SIZE 를 환경에 맞게 조정
```

### 설치가 깨질 때

**`error: the configured Python interpreter version (3.14) is newer than PyO3's
maximum supported version (3.13)`**

pydantic 을 정확한 버전(`==`)으로 고정하면 생기는 문제다. 해당 버전의
pydantic-core(Rust) 휠이 이 파이썬용으로 없으면 pip 가 소스 빌드로 넘어가고,
그 안의 PyO3 가 새 파이썬을 모른다며 죽는다. `requirements.txt` 는 이래서
하한(`>=`)만 두고 있으니, 파일을 최신 상태로 되돌린 뒤 다시 설치한다.

```bash
pip install -r requirements.txt
```

그래도 안 되면 파이썬을 한 단계 낮춰 가상환경을 다시 만든다.

```bash
rm -rf .venv && python3.12 -m venv .venv
source .venv/bin/activate && pip install -r requirements.txt
```

**`usb.core.NoBackendError: No backend available`**

Apple Silicon 에서 `libusb` 를 못 찾는 경우다. Homebrew 경로를 알려준다.

```bash
export DYLD_LIBRARY_PATH=/opt/homebrew/lib:$DYLD_LIBRARY_PATH
```

## 2. 실행

```bash
cd print-agent
source .venv/bin/activate
python main.py
# → http://0.0.0.0:5051
```

프린터가 아직 없거나 레이아웃만 확인하고 싶으면 `.env` 에 `DRY_RUN=true` 를 주면
된다. 실제 USB 로 보내지 않고 바이트만 만들어 보며, 엔드포인트는 정상 응답한다.

```bash
DRY_RUN=true python main.py
```

### 포트를 왜 5051 로 쓰나

`5000` 은 macOS 의 AirPlay 수신(ControlCenter)이 항상 잡고 있고, `5001` 은 이
맥북에서 도는 다른 프로젝트 컨테이너(`lootmap-osrm`)가 쓰고 있다. 둘 다 피해서
`5051` 을 기본값으로 둔다. 아래처럼 나오면 포트가 이미 쓰이는 중이다.

```
ERROR: [Errno 48] error while attempting to bind on address ('0.0.0.0', 5051):
       address already in use
```

누가 잡고 있는지는 이렇게 확인한다.

```bash
lsof -nP -iTCP:5051 -sTCP:LISTEN
```

포트를 옮기려면 **두 곳을 같이** 바꿔야 한다.

| 어디 | 무엇 |
|---|---|
| `print-agent/.env` | `AGENT_PORT=…` |
| 백엔드 환경변수 | `PRINT_AGENT_URL=http://host.docker.internal:…` |

`docker-compose.yml` 이 `PRINT_AGENT_URL` 을 그대로 넘겨주므로, 셸이나 `.env` 에
그 변수만 정의하면 컴포즈 파일을 고칠 필요는 없다.

## 3. VID / PID 확인하는 법

프린터를 바꾸거나 다른 장비를 물렸을 때 쓴다. **현재 장비의 실측값은
`0x1fc9` / `0x2016`** 이고, USB 문자열은 manufacturer `POS`,
product `POS Receipt Printer` 로 잡힌다.

```bash
source .venv/bin/activate
python - <<'PY'
import usb.core, usb.util
for d in usb.core.find(find_all=True):
    try:
        maker = usb.util.get_string(d, d.iManufacturer) or "?"
        product = usb.util.get_string(d, d.iProduct) or "?"
    except Exception:
        maker = product = "(문자열 읽기 실패 — 권한/드라이버)"
    print(f"VID=0x{d.idVendor:04x} PID=0x{d.idProduct:04x}  {maker} / {product}")
PY
```

`POS Receipt Printer` 로 보이는 줄의 VID/PID 를 `.env` 의 `PRINTER_VID`,
`PRINTER_PID` 에 적는다. GUI 로 확인하려면
시스템 정보 → 하드웨어 → USB 에서도 같은 값을 볼 수 있다.

## 4. 환경변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `KIOSK_BASE_URL` | `https://artifact-prod.duckdns.org` | 접수증 QR 주소의 앞부분. QR 내용은 `{base}/kiosk/{token}`. **요청 본문의 `kioskBaseUrl` 이 오면 그쪽이 우선**이고 이 값은 폴백이다 |
| `QR_SIZE` | `8` | QR 모듈 배율. **아이패드 실측 전 잠정값** — 아래 캘리브레이션 참고 |
| `QR_NATIVE` | `true` | 프린터 펌웨어로 QR 을 그린다. 실패하면 비트맵으로 자동 폴백 |
| `QR_EC_LEVEL` | `0` | 오류정정 L(0)/M(1)/Q(2)/H(3). 올리면 잘 읽히지만 QR 이 커진다 |
| `SEAL_SVG_PATH` | `../frontend/public/hospital-seal.svg` | 도장 원본. A4 증명서와 같은 파일을 공유한다 |
| `SEAL_SIZE_PX` | `200` | 도장 크기(px). 200px ≈ 25mm. 200/160 모두 판독 확인 |
| `PRINTER_VID` | `0x1fc9` | USB 벤더 ID (16진수 표기 그대로 인식) |
| `PRINTER_PID` | `0x2016` | USB 제품 ID |
| `PRINTER_TIMEOUT` | `0` | pyusb 쓰기 타임아웃(ms). 0 = 무제한 |
| `LINE_WIDTH` | `42` | Font A 한 줄 칸 수. 한글은 2칸이므로 한글만이면 21자 |
| `PAPER_WIDTH_DOTS` | `576` | 인쇄 가능 폭(도트). QR·도장 가운데 정렬에 쓴다 |
| `BACKEND_URL` | `https://artifact-prod.duckdns.org` | 인쇄 작업을 가지러 갈 백엔드. **여기서 가져오지, 저쪽이 부르지 않는다** — 9장 참고 |
| `BACKEND_LOGIN_ID` | (빈 값) | 프린터 전용 직원 계정. 비어 있으면 폴링을 시작하지 않는다 |
| `BACKEND_PASSWORD` | (빈 값) | 위 계정의 비밀번호. `.env` 에만 두고 저장소에 올리지 않는다 |
| `POLL_ENABLED` | `true` | `false` 면 폴링을 끄고 HTTP 요청을 받아서만 출력한다(레이아웃 작업용) |
| `POLL_TIMEOUT_SECONDS` | `40` | 롱 폴링 한 번의 대기 상한. 백엔드의 `poll-wait-seconds`(25초)보다 넉넉해야 한다 |
| `AGENT_TOKEN` | (빈 값) | 이 에이전트가 여는 HTTP 창구를 지키는 토큰. 폴링 구조에서는 백엔드가 이쪽을 부르지 않으므로 평소에는 비워 둬도 된다 |
| `AGENT_HOST` | `0.0.0.0` | 도커 컨테이너가 접근해야 하므로 루프백만 열면 안 된다 |
| `AGENT_PORT` | `5051` | 백엔드의 `PRINT_AGENT_URL` 과 맞춰야 한다 |
| `HOSPITAL_NAME` | `아티팩트 피부과의원` | 문서 머리글 |
| `PORTAL_BASE_URL` | `KIOSK_BASE_URL` 과 동일 | 조회/진위확인 링크의 기준 주소 |
| `VISIT_SUMMARY_URL_TEMPLATE` | `{base}/main/lookup?visitId={visitId}` | 진료요약서 QR 목적지 |
| `CERTIFICATE_VERIFY_URL_TEMPLATE` | `{base}/main/certificate?serialNo={serialNo}` | 발급확인증 QR 목적지 |
| `DRY_RUN` | `false` | true 면 실제 출력 없이 바이트만 생성 |

## 5. 엔드포인트

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET | `/ping` | 살아있는지만 본다. **인증 없이 열려 있다**(터널 헬스체크용, 설정값을 노출하지 않는다) |
| GET | `/health` | 에이전트 상태 + **실제 프린터 연결 확인**(`printerReady`) + 현재 설정값 |
| POST | `/print/ticket` | 접수증(대기번호 + 키오스크 QR) |
| POST | `/print/visit-summary` | 진료 요약서 |
| POST | `/print/certificate-slip` | 증명서 발급 확인증(도장 포함) |
| POST | `/debug/qr-calibration` | 같은 URL 을 여러 QR 크기로 연속 출력 |

응답은 모두 `{"ok": bool, "docType": str, "detail": str, "qrSize": int, "qrNative": bool}` 형태다.
HTTP 코드는 본문 스키마 오류 422, 잘못된 문서 타입 400, **프린터 접근 실패 503**,
그 밖의 오류 500. 프린터가 꺼져 있거나 케이블이 빠졌을 때는 항상 503 이고
`detail` 에 VID/PID 와 원인이 담긴다.

`/health` 는 DRY_RUN 이 아닐 때 실제로 USB 장치를 열어보고 `printerReady` 를
판정한다. 프린터를 뽑아 두면 `printerReady: false` 로 바뀐다.

`AGENT_TOKEN` 이 설정돼 있으면 `/ping` 을 뺀 **모든 경로가 401** 을 낸다.
호출 쪽은 `Authorization: Bearer <토큰>` 헤더를 실어야 한다. 토큰이 비어 있으면
검사 자체를 하지 않으므로, 맥북 안에서만 쓰는 지금 구성은 아무것도 바뀌지 않는다.

### 스모크 테스트

여러 줄 `curl` 을 터미널에 붙여넣으면 줄이 잘려 `URL string malformed` 가 나기
쉽다. 동봉한 스크립트를 쓰면 그 문제가 없다.

```bash
./test-endpoints.sh              # 전부 (localhost:5051)
./test-endpoints.sh ticket       # 접수증만  (health|ticket|summary|slip|qr)
AGENT=http://localhost:5099 ./test-endpoints.sh     # 다른 포트로
QR_SIZES='[5, 6, 7]' ./test-endpoints.sh qr         # 캘리브레이션 크기 바꿔서
AGENT_TOKEN=xxxxx ./test-endpoints.sh               # 인증을 켠 에이전트에
KIOSK_BASE_URL=http://192.168.0.12:5173 ./test-endpoints.sh ticket   # QR 주소 바꿔서
```

프린터가 물려 있으면 실제로 종이가 나온다. 종이 없이 확인만 하려면 에이전트를
`DRY_RUN=true` 로 띄운 상태에서 실행한다.

## 6. QR 크기 캘리브레이션

키오스크가 유선 안드로이드에서 **아이패드**로 바뀌면서 QR 내용이 토큰(8자)에서
전체 URL(48자 안팎)로 늘었다. 아이패드 기본 카메라는 QR 내용이 완전한 URL 일 때만
사파리로 넘겨주기 때문이다. 모듈 수가 늘었으니 예전에 검증한 `size=6` 은 더 이상
맞지 않을 수 있다. **실물로 재보고 정해야 한다.**

```bash
# 1) 여러 크기를 한 장에 뽑는다
curl -s -X POST http://localhost:5051/debug/qr-calibration \
  -H 'Content-Type: application/json' \
  -d '{"sizes": [6, 7, 8, 10]}' | jq

# 2) 종이에 size=6 / size=7 … 라벨이 붙은 QR 이 순서대로 나온다.
# 3) 실제 사용할 아이패드로, 실제 조명·거리(팔 길이 30~40cm)에서 하나씩 찍어본다.
# 4) 안정적으로 인식되는 것 중 "가장 작은" 값을 고른다 — 크면 종이만 낭비한다.
# 5) .env 의 QR_SIZE 에 그 값을 적고 print-agent 를 재시작한다.
```

너무 커서 용지 폭(576도트)을 넘으면 QR 이 잘린다. 반대로 인식이 계속 실패하면
`QR_SIZE` 를 키우기 전에 `QR_EC_LEVEL=1`(M) 을 먼저 시도해 보는 편이 낫다.

## 7. 로그인 시 자동 실행 (launchd)

접수 데스크 맥이 재부팅되거나 프로세스가 죽어도 알아서 다시 뜨게 한다.
plist 를 손으로 쓰지 않고 스크립트가 만들어 준다 — 경로를 잘못 적으면 조용히
안 뜨는데, 그 사실을 종이가 안 나올 때 알게 되기 때문이다.

```bash
cd print-agent
./install-launchd.sh install    # 등록 + 즉시 기동
./install-launchd.sh status     # 떠 있나 / 최근 로그
./install-launchd.sh uninstall  # 해제
```

`install` 은 등록 전에 `.venv/bin/python`, `.env`, `BACKEND_LOGIN_ID` 를 확인하고
하나라도 없으면 멈춘다. 등록되는 것은
`~/Library/LaunchAgents/com.artifact.print-agent.plist` 이고, `RunAtLoad` 로
로그인할 때 뜨고 `KeepAlive` 로 죽으면 다시 뜬다(`ThrottleInterval` 10초).

로그는 `~/Library/Logs/artifact-print-agent.log` 에 쌓인다.

```bash
tail -f ~/Library/Logs/artifact-print-agent.log
```

> **처음 등록한 뒤에는 로그를 한 번 확인한다.** 이 프로젝트가 `~/Downloads`
> 아래 있으면, macOS 가 launchd 로 뜬 프로세스의 Downloads 접근을 막아
> `.env` 를 못 읽는 경우가 있다. 로그에 설정이 안 읽힌 흔적이 있으면
> 시스템 설정 → 개인정보 보호 및 보안 → 파일 및 폴더 에서 허용하거나,
> 프로젝트를 `~/Downloads` 밖으로 옮긴다.

## 8. 백엔드 연동

백엔드는 `PrintAgentClient` 로 이 에이전트를 부른다. 주소는
`application.properties` 의 `print.agent.url` (기본 `http://host.docker.internal:5051`).

| 시점 | 호출 |
|---|---|
| 접수 완료 (`POST /api/v1/visits`) | `/print/ticket` |
| 진료 완료 (`PATCH /api/v1/visits/{id}/complete`) | `/print/visit-summary` |
| 증명서 발급 (`POST /api/v1/certificates/...`) | `/print/certificate-slip` |

자동 출력은 **fire-and-forget** 이다. 프린터가 꺼져 있어도 접수·발급은 이미
커밋된 뒤이고, 실패는 백엔드 로그에 `log.warn` 으로만 남는다. **죽은 프린터가
접수를 막는 일은 없다.** 프론트의 수동 인쇄 버튼(접수 화면 "티켓 인쇄",
진료 화면 "진료요약 인쇄", 증명서 화면 "발급확인증 인쇄")은 동기 호출이라
실패 사유가 화면에 뜬다.

### 접수증 QR 주소는 누가 정하나

화면에 뜬 QR 과 종이에 찍힌 QR 이 서로 다른 주소를 가리키면 환자가 엉뚱한 곳으로
간다. 그래서 **접수 화면이 보고 있는 주소를 그대로 종이까지 흘려보낸다.**

```
접수 화면(getKioskBaseUrl)
  → X-Kiosk-Base-Url 헤더
  → KioskBaseUrlPolicy.sanitize()   ← 여기서 검증·정규화
  → 요청 본문의 kioskBaseUrl
  → QR 내용 "{base}/kiosk/{token}"
```

가운데의 `KioskBaseUrlPolicy` 가 핵심이다. QR 은 눈으로 내용을 읽을 수 없으므로,
검증 없이 통과시키면 계정이 탈취됐을 때 **병원이 발행한 종이**로 환자를 피싱
사이트에 보낼 수 있다. 정책은 http/https 스킴과 호스트를 요구하고, 길이를 200자로
자르고, 쿼리·프래그먼트를 버린다. `PRINT_KIOSK_ALLOWED_BASE_URLS` 에 주소를
콤마로 나열하면 **그 목록에 있는 것만** 통과한다(비워 두면 스킴 검사만 한다).

검증에 걸린 값은 예외가 아니라 `null` 이 되고, 에이전트는 자기 `KIOSK_BASE_URL`
기본값을 쓴다. **주소가 이상하다고 접수가 막히는 일은 없다.**

EC2 운영 환경에는 프린터가 없지만 `PRINT_AGENT_ENABLED` 는 기본이 `true` 다.
프린터가 없는 쪽은 EC2 가 아니라 **작업을 가지러 오지 않는 상태**이고, 그건
서버가 스스로 안다(`agentConnected=false`). 배포본과 이 맥북을 잇는 방법은
다음 장에 있다 — 서버에 넣을 설정은 없다.

## 9. 배포본과 이어붙이기

EC2에 배포된 화면에서 접수하면 이 맥북에 물린 프린터에서 종이가 나온다.
**그렇게 만들기 위해 서버에 넣어야 하는 설정은 없다.** 이 장은 왜 그런지와,
맥북 쪽에서 딱 한 번 해두는 일을 설명한다.

### 9-1. 왜 방향을 뒤집었나

처음에는 서버가 이 맥북을 부르게 만들었다. 그러려면 맥북에 인터넷에서 닿는
주소가 있어야 하는데, 접수 데스크는 공유기 뒤에 있다. 그래서 터널
(`cloudflared tunnel --url`)을 열었고, 다음 문제들이 줄줄이 따라왔다.

- 무료 터널 주소는 **띄울 때마다 바뀐다.** 바뀔 때마다 EC2의
  `PRINT_AGENT_URL` 을 고치고 백엔드를 재시작해야 했다.
- 공개하는 순간 **인터넷의 아무나 병원 프린터로 종이를 뽑을 수 있다.**
  막으려고 공유 토큰(`AGENT_TOKEN` ↔ `PRINT_AGENT_TOKEN`)을 양쪽에 같게
  넣어야 했고, 한쪽만 바꾸면 인쇄가 401 로 조용히 죽었다.
- 결국 "설정 없이 항상 되는" 상태가 되지 않는다. 시연 날 아침마다 사람이
  주소를 옮겨 적어야 한다.

그래서 방향을 뒤집었다. **맥북이 서버에 접속해 "뽑을 것 있나" 를 묻는다.**

```
before :  [EC2 백엔드]  --HTTP-->  [맥북]  --USB-->  [프린터]   ← 터널 필요
after  :  [EC2 백엔드]  <--HTTP--  [맥북]  --USB-->  [프린터]   ← 나가는 연결만
```

나가는 연결만 쓰므로 공유기·방화벽·주소와 무관하다. 열어 둘 포트도, 공개할
주소도, 서버에 넣을 환경변수도 없다. 맥북에서 print-agent 만 떠 있으면 된다.

### 9-2. 맥북 쪽에 한 번만 해두는 일

`.env` 에 프린터 전용 직원 계정을 적는다. 이 세 줄이 전부다.

```bash
BACKEND_URL=https://artifact-prod.duckdns.org
BACKEND_LOGIN_ID=printagent
BACKEND_PASSWORD=<그 계정의 비밀번호>
```

계정은 사람이 쓰는 것과 **섞지 않는다.** 비밀번호를 이 파일에 적어 두게 되고,
감사 로그에서 누가 무엇을 했는지 구분되지 않기 때문이다. 직책은 `STAFF` 면
충분하다(`/api/v1/auth/signup` 으로 만든다).

`.env` 는 `.gitignore` 대상이라 저장소에 올라가지 않는다. 올라간 적이 없는지는
`git log -p -- print-agent/.env` 가 비어 있는 것으로 확인한다.

### 9-3. 어떻게 도는가

```
접수(EC2)  →  큐에 넣음  ←  GET /api/v1/print/jobs/next   (맥북이 물어봄, 최대 25초 매달림)
                                  ↓
                              USB 출력
                                  ↓
                       POST /api/v1/print/jobs/{id}/result  (결과 회신)
```

1초마다 묻지 않고 **최대 25초 매달려 있는다**(롱 폴링). 그래야 접수와 동시에
종이가 나오면서도 요청 수가 분당 두어 건에 머문다. 서버는 기다리는 동안
스레드를 붙잡지 않는다(`DeferredResult`).

인증은 그 계정으로 로그인해 받은 평범한 JWT 다. 만료되면(24시간) 에이전트가
알아서 다시 로그인한다. **프린터 전용 인증 체계를 새로 만들지 않은 이유**는,
만들면 그 자체가 관리할 비밀 하나가 더 늘고 서버에도 넣을 설정이 생겨
"설정 없이 도는" 목표가 깨지기 때문이다.

### 9-4. 종이가 사라지지 않게 하는 두 겹

롱 폴링의 함정: 에이전트가 죽거나 와이파이가 끊겨도 **서버는 그 사실을 모른다.**
끊긴 연결에 작업을 건네도 성공한 것처럼 보이고, 그 접수증은 아무도 받지 못한 채
사라진다. 실제로 에이전트를 재시작한 직후의 접수 한 건이 이렇게 없어졌다.

- **이름표** — 에이전트는 프로세스마다 새 식별자를 만들어
  `X-Agent-Instance` 헤더로 보낸다. 새 이름표가 오면 서버는 이전 이름표로
  매달려 있던 연결을 그 자리에서 끊는다. 재시작 직후의 유령 연결이 작업을
  삼키지 못한다.
- **되돌리기** — 그래도 건네준 뒤 20초
  (`print.queue.visibility-timeout-seconds`) 동안 회신이 없으면 큐로 되돌려
  다시 건넨다. 출력은 길어야 몇 초라, 그보다 오래 조용하면 사실상 받지 못한
  것이다. 되돌린 뒤 실제로는 출력됐던 경우 한 장이 더 나올 수 있는데,
  **접수증이 안 나오는 쪽이 더 나쁘므로** 이 방향을 택했다.

작업이 `job-ttl-seconds`(기본 120초)보다 늙으면 조용히 버린다. 프린터가 꺼진
채로 열 건이 쌓였다가 저녁에 한꺼번에 나오면, 이미 진료가 끝난 사람의
대기번호표라 쓸모가 없고 오히려 혼란스럽다.

### 9-5. 확인

```bash
# 1) 서버가 이 맥북을 보고 있나
TOKEN=$(curl -s -X POST https://artifact-prod.duckdns.org/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"loginId":"printagent","password":"<비밀번호>"}' | jq -r .token)

curl -s -H "Authorization: Bearer $TOKEN" \
  https://artifact-prod.duckdns.org/api/v1/print/jobs/status | jq
# {"mode":"queue","enabled":true,"agentConnected":true,
#  "pending":0,"waitingAgents":1,"inFlight":0}
```

`agentConnected: false` 면 맥북에서 에이전트가 안 떠 있는 것이다.
`waitingAgents` 가 2 이상이면 **에이전트가 두 개 떠 있다** — 오래된 프로세스가
남아 작업을 가로챈다. `ps -Ao pid,command | grep main.py` 로 찾아 정리한다.

이 요청 자체가 **500** 으로 떨어지면 맥북 문제가 아니다. 배포된 백엔드에
`/api/v1/print/jobs/*` 매핑이 없다는 뜻이고(이 서버는 404 를 500 으로 감싼다),
원인은 둘 중 하나다.

- 큐 코드가 아직 `main` 에 없다 → 머지하고 Deploy 워크플로가 도는 것을 기다린다.
- `PRINT_MODE` 가 `direct` 로 떠 있다 → `PrintJobController` 가 아예 등록되지 않는다.

실제로 한 번은 이렇게 헷갈렸다. 프린트 기능이 `main` 에 머지돼 배포까지 끝났는데
`/api/v1/print/jobs/status` 가 500 이었다. 머지된 것은 그 **이전 단계인 direct 버전**
이었고 큐 코드는 아직 커밋 전이었다. 게다가 그때 `docker-compose.prod.yml` 의
운영 기본값이 `PRINT_AGENT_ENABLED=false` 여서, 수동 재출력은 200 과 함께
"감열지 출력이 꺼져 있습니다" 를 돌려줬다.

지금은 `docker-compose.prod.yml` 이 `PRINT_MODE` 와 `PRINT_AGENT_ENABLED` 를
**고정값으로 못박는다.** 배포는 self-hosted 러너가 정해진 단계만 실행하고 22번
포트도 닫혀 있어 EC2 안의 `.env` 를 확인할 방법이 없기 때문이다. 그 파일에 무엇이
남아 있든 운영은 항상 큐 모드로, 켜진 채로 뜬다.

구분법: `/api/v1/print/jobs/status` 는 500 인데 수동 재출력
(`POST /api/v1/visits/1/print/ticket`) 은 200 과 함께 "꺼져 있습니다" 를 주면
**코드는 배포됐고 설정만 어긋난 것**이다. 둘 다 500 이면 코드가 아직 안 올라갔다.

```bash
# 2) 맥북 쪽 로그
tail -f ~/Library/Logs/artifact-print-agent.log

# 3) 배포 화면에서 접수 1건 → 종이가 나오는지
```

### 9-6. 터널은 이제 안 쓴다

옛 방식(`PRINT_MODE=direct`)은 코드에 남아 있다. 백엔드가 `PRINT_AGENT_URL` 로
직접 부르는 구성이고, `PrintAgentClient` 가 그쪽 통로다.

되돌릴 일이 생기면 **EC2 의 `.env` 가 아니라 `docker-compose.prod.yml` 을 고친다.**
그 파일이 `PRINT_MODE` 를 고정값으로 들고 있어서 `.env` 로는 바뀌지 않는다(9-5 참고).
`PRINT_MODE: direct` 로 바꾸고 `PRINT_AGENT_URL`·`PRINT_AGENT_TOKEN` 을 더한 뒤
머지하면 배포에 실려 간다. 맥북에서는 `cloudflared tunnel --url http://localhost:5051`
를 띄운다.

**평소에는 쓰지 않는다.** 위에 적은 이유들이 그대로 돌아오기 때문이다.

## 10. 감열지에 대한 경고

감열지는 감열층의 발색 반응으로 글자를 만드는 종이다. 열·직사광선·가소제
(비닐 파일, 영수증 지갑)에 닿으면 **수개월 안에 글자가 사라진다.**

따라서 여기서 나오는 출력물은 전부 **안내·확인용 보조 출력물**이다.
법정 서식을 대체하지 않는다. 보존이 필요한 증명서 원본은 언제나 프론트엔드의
A4 인쇄 흐름(`Certificate.tsx` 의 `window.print()`)에서 나오는 쪽이다.

## 11. 한글 출력 주의

python-escpos 의 `p.text()` 는 한글을 전부 `?` 로 찍는다. 반드시 `printer.py` 의
`kr()` 헬퍼를 쓴다 — `FS &`(2바이트 문자 모드 ON) → EUC-KR 바이트 → `FS .`(OFF)
순서로 직접 흘려보낸다.

```python
def kr(p, s: str) -> None:
    p._raw(b"\x1c\x26")                # FS & : 2바이트 문자 모드 ON
    p._raw(s.encode("euc-kr"))
    p._raw(b"\x1c\x2e")                # FS . : 2바이트 문자 모드 OFF
```

또 하나: 문서마다 첫 바이트로 `ESC @`(`\x1b\x40`)를 보내 버퍼를 초기화해야 한다.
안 하면 직전 문서가 통째로 다시 찍힌다. `documents.print_document()` 가 이미
`pr.reset(p)` 로 처리한다.

## 12. 새 문서 타입 추가하기

1. `schemas.py` 에 payload 모델을 만든다.
2. `documents.py` 에 `build_xxx(p, data)` 를 쓰고 `BUILDERS` 딕셔너리에 등록한다.
3. `main.py` 에 엔드포인트 한 줄을 더한다.

`printer.py` 의 `kr_line`, `divider`, `label_value`, `kr_wrapped`,
`render_qr`, `render_seal` 을 조합하면 대부분의 레이아웃이 나온다.
`kr_wrapped` 는 21자(한글 기준)에서 자동 줄바꿈하고 둘째 줄부터 들여쓴다.
