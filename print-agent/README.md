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
| `AGENT_TOKEN` | (빈 값) | 비우면 인증 없음(맥북 안에서만 도는 기본 형태). **터널로 공개하면 반드시 채운다** — 아래 9장 참고 |
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

접수 데스크 맥이 재부팅돼도 에이전트가 알아서 뜨게 하려면 아래 plist 를
`~/Library/LaunchAgents/com.artifact.print-agent.plist` 로 저장한다.
경로는 실제 설치 위치에 맞게 바꿔야 한다.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>com.artifact.print-agent</string>

  <key>ProgramArguments</key>
  <array>
    <string>/Users/leekh/Downloads/CapstoneDesign/artifact-medical-ai/print-agent/.venv/bin/python</string>
    <string>/Users/leekh/Downloads/CapstoneDesign/artifact-medical-ai/print-agent/main.py</string>
  </array>

  <key>WorkingDirectory</key>
  <string>/Users/leekh/Downloads/CapstoneDesign/artifact-medical-ai/print-agent</string>

  <key>EnvironmentVariables</key>
  <dict>
    <!-- Homebrew 의 libusb / cairo 를 찾게 해준다 (Apple Silicon 기준) -->
    <key>DYLD_LIBRARY_PATH</key>
    <string>/opt/homebrew/lib</string>
  </dict>

  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>

  <key>StandardOutPath</key>
  <string>/tmp/artifact-print-agent.log</string>
  <key>StandardErrorPath</key>
  <string>/tmp/artifact-print-agent.err</string>
</dict>
</plist>
```

```bash
launchctl load  ~/Library/LaunchAgents/com.artifact.print-agent.plist   # 등록
launchctl list | grep print-agent                                       # 확인
launchctl unload ~/Library/LaunchAgents/com.artifact.print-agent.plist  # 해제
tail -f /tmp/artifact-print-agent.err                                   # 로그
```

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

EC2 운영 환경에는 프린터가 없으므로 `docker-compose.prod.yml` 의
`PRINT_AGENT_ENABLED` 는 기본이 `false` 다. 배포된 화면에서 병원 프린터를
쓰려면 다음 장의 터널 구성이 필요하다.

## 9. 터널로 공개하기

기본 구성은 **맥북 안에서만** 돈다. 백엔드 컨테이너가 `host.docker.internal:5051`
로 부르고, 그 트래픽은 맥북 밖으로 나가지 않는다. 노트북에서 `docker compose up`
으로 띄워 쓰는 한 이 장은 읽을 필요가 없다.

문제는 **EC2에 배포된 화면**에서 병원 프린터를 쓰고 싶을 때다. EC2의 백엔드
컨테이너에게 `host.docker.internal` 은 EC2 자기 자신이라 프린터가 없다. 그렇다고
프론트에서 직접 부를 수도 없다 — HTTPS 페이지가 `http://localhost:5051` 을 부르면
브라우저가 mixed content 로 막는다. 남는 길은 **맥북의 5051 포트를 공개 HTTPS
주소로 내보내고, EC2 백엔드가 그 주소를 부르게** 하는 것이다.

> 공개하는 순간 **인터넷의 아무나 병원 프린터로 종이를 뽑을 수 있게 된다.**
> `AGENT_TOKEN` 은 선택이 아니라 필수다. 아래 순서를 건너뛰지 말 것.

### 9-1. 토큰 만들기

```bash
python3 -c "import secrets; print(secrets.token_urlsafe(32))"
# → 예: 7Qx2_9mVtK... (43자)
```

이 값을 **두 군데**에 같게 넣는다.

```bash
# 맥북: print-agent/.env
AGENT_TOKEN=7Qx2_9mVtK...

# EC2: docker-compose 가 읽는 .env
PRINT_AGENT_TOKEN=7Qx2_9mVtK...
```

백엔드는 `PRINT_AGENT_TOKEN` 을 읽어 모든 인쇄 요청에 `Authorization: Bearer`
헤더로 실어 보낸다(`PrintAgentClient`). 두 값이 어긋나면 인쇄가 401 로 떨어지고,
화면에는 "프린터 서비스 인증에 실패했습니다 — 백엔드의 `PRINT_AGENT_TOKEN` 과
print-agent 의 `AGENT_TOKEN` 이 같은 값인지 확인하세요" 가 뜬다.

토큰을 바꾸면 **양쪽 모두** 재시작해야 한다.

### 9-2. 터널 띄우기 (Cloudflare Tunnel)

계정 없이 임시 주소를 받는 방식이다. 데모·시연에는 이걸로 충분하다.

```bash
brew install cloudflared

# 터미널 1 — 에이전트
cd print-agent && ./run.sh

# 터미널 2 — 터널
cloudflared tunnel --url http://localhost:5051
# → https://random-words-1234.trycloudflare.com 같은 주소가 찍힌다
```

살아있는지는 인증 없이 확인할 수 있다.

```bash
curl -s https://random-words-1234.trycloudflare.com/ping
# {"ok":true}
```

토큰 없이 실제 엔드포인트를 부르면 막혀야 정상이다.

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  https://random-words-1234.trycloudflare.com/health
# 401
```

`--url` 방식의 주소는 **터널을 다시 띄울 때마다 바뀐다.** 주소가 바뀌면 EC2의
`PRINT_AGENT_URL` 도 같이 고치고 백엔드를 재시작해야 한다. 시연 날 아침에 한 번
띄우고 그대로 두는 운용이 가장 편하다. 주소를 고정하려면 Cloudflare 계정을 붙여
named tunnel(`cloudflared tunnel create`)을 쓴다.

### 9-3. EC2 쪽 설정

EC2의 `.env` 에 세 줄을 넣고 백엔드를 재시작한다.

```bash
PRINT_AGENT_ENABLED=true
PRINT_AGENT_URL=https://random-words-1234.trycloudflare.com
PRINT_AGENT_TOKEN=7Qx2_9mVtK...
PRINT_KIOSK_ALLOWED_BASE_URLS=https://artifact-prod.duckdns.org
```

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d backend
```

`PRINT_KIOSK_ALLOWED_BASE_URLS` 는 접수증 QR 이 가리켜도 되는 주소의 허용 목록이다
(8장 참고). 공개 배포에서는 비워 두지 말고 실제 도메인을 적는다. 로컬 개발 화면도
같은 프린터를 써야 하면 콤마로 덧붙인다:
`https://artifact-prod.duckdns.org,http://localhost:5173`.

### 9-4. 확인

```bash
# 1) 터널 살아있나 (인증 불필요)
curl -s https://<터널주소>/ping

# 2) 토큰으로 프린터 상태 (printerReady, authRequired 확인)
curl -s -H "Authorization: Bearer $AGENT_TOKEN" https://<터널주소>/health | jq

# 3) EC2 백엔드가 실제로 부르는지 — 배포 화면에서 접수 1건
docker logs artifact-backend --tail 30 | grep -i print
```

### 9-5. 끝나면 닫는다

시연이 끝나면 `cloudflared` 를 끄는 것으로 충분하다. 주소가 사라지므로 외부에서
접근할 길이 없어진다. EC2의 `PRINT_AGENT_ENABLED` 도 `false` 로 되돌려 두면
백엔드가 죽은 주소를 계속 두드리지 않는다.

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
