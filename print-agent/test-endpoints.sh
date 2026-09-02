#!/usr/bin/env bash
#
# print-agent 엔드포인트 스모크 테스트.
#
# 여러 줄 curl 을 터미널에 붙여넣으면 줄이 잘려서 "URL string malformed" 가 나기
# 쉬우므로, 그냥 이 스크립트를 실행한다.
#
#   ./test-endpoints.sh                 # localhost:5051 로 전부 실행
#   AGENT=http://localhost:5099 ./test-endpoints.sh
#   ./test-endpoints.sh ticket          # 하나만
#   ./test-endpoints.sh qr              # QR 캘리브레이션만
#
# AGENT_TOKEN 을 설정해 띄웠다면(터널 공개 시 필수) 같은 값을 주고 실행한다:
#   AGENT_TOKEN=xxxx ./test-endpoints.sh
# 터널 너머로 확인할 때는 둘 다 준다:
#   AGENT=https://print.example.com AGENT_TOKEN=xxxx ./test-endpoints.sh
#
# 실물 프린터가 물려 있으면 종이가 나온다. 종이 없이 확인만 하려면
# 에이전트를 DRY_RUN=true 로 띄운다.

set -u
AGENT="${AGENT:-http://localhost:5051}"
ONLY="${1:-all}"

# 캘리브레이션에서 뽑아볼 QR 크기. 필요하면 바꿔서 쓴다.
QR_SIZES="${QR_SIZES:-[6, 7, 8, 10]}"

# 에이전트가 AGENT_TOKEN 을 들고 떠 있으면 같은 값을 줘야 401 이 안 난다.
# 비어 있으면 헤더 자체를 안 붙인다 — 토큰 없이 뜬 에이전트에는 그게 정상이다.
AGENT_TOKEN="${AGENT_TOKEN:-}"
AUTH_ARGS=()
if [ -n "$AGENT_TOKEN" ]; then
  AUTH_ARGS=(-H "Authorization: Bearer $AGENT_TOKEN")
fi

# 아래에서 "${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"}" 로 전개하는 이유:
# 맥 기본 bash 는 3.2 인데, 3.2 는 set -u 상태에서 "빈 배열" 을 전개하면
# unbound variable 로 죽는다. ${arr[@]+...} 는 배열이 비었을 때 아무것도
# 내놓지 않는 관용구다. 토큰 없이 쓰는 기본 구성이 주 경로이므로 꼭 필요하다.

call() {
  local label="$1" method="$2" path="$3" body="${4:-}"
  echo
  echo "── $label ─────────────────────────────────────────"
  if [ -n "$body" ]; then
    curl -sS -w '\nHTTP %{http_code}\n' -X "$method" "$AGENT$path" \
      ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} -H 'Content-Type: application/json' -d "$body"
  else
    curl -sS -w '\nHTTP %{http_code}\n' -X "$method" "$AGENT$path" \
      ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"}
  fi
}

# 0) 프로세스/터널이 살아 있는지 (인증 불필요 — 터널 점검용)
if [ "$ONLY" = "all" ] || [ "$ONLY" = "ping" ]; then
  call "0) ping" GET /ping
fi

# 1) 프린터가 실제로 열리는지(printerReady) + 설정 확인 (인증 필요)
if [ "$ONLY" = "all" ] || [ "$ONLY" = "health" ]; then
  call "1) health" GET /health
fi

# 1) 접수증 — 대기번호 + 키오스크 QR
if [ "$ONLY" = "all" ] || [ "$ONLY" = "ticket" ]; then
  # kioskBaseUrl 을 주면 그 주소로 QR 이 찍힌다(접수 화면이 보내는 값과 같은 자리).
  # 빼면 .env 의 KIOSK_BASE_URL 이 쓰인다 — 둘 다 확인해 볼 것.
  call "2) 접수증" POST /print/ticket "{
    \"visitNo\": \"V00042\",
    \"patientName\": \"홍길동\",
    \"patientNo\": \"P00017\",
    \"kioskToken\": \"aB3xK9pQ\",
    \"kioskBaseUrl\": \"${KIOSK_BASE_URL:-https://artifact-prod.duckdns.org}\"
  }"
fi

# 2) 진료 요약서 — 긴 약품명 줄바꿈과 [AI 분석 참고] 블록까지 확인되는 본문
if [ "$ONLY" = "all" ] || [ "$ONLY" = "summary" ]; then
  call "3) 진료 요약서" POST /print/visit-summary '{
    "visitId": 42,
    "patientName": "홍길동",
    "patientNo": "P00017",
    "visitDateTime": "2026-09-01T14:30:00",
    "doctorName": "김의사",
    "diseases": [
      { "code": "L309", "nameKo": "상세불명의 피부염" },
      { "code": "L82",  "nameKo": "지루각화증" }
    ],
    "prescriptions": [
      { "drugName": "몬테루카스트나트륨정 10mg (싱귤레어)", "dosage": "1일 1회 1정", "durationDays": 30 },
      { "drugName": "데스로라타딘정 5mg", "dosage": "1일 1회 1정", "durationDays": 14 }
    ],
    "aiSummary": "제시된 병변은 양성 각화증 소견에 가깝습니다. 크기 변화나 색조 변화가 관찰되면 재내원을 권장합니다."
  }'
fi

# 3) 발급확인증 — 도장 이미지 포함
if [ "$ONLY" = "all" ] || [ "$ONLY" = "slip" ]; then
  call "4) 발급확인증" POST /print/certificate-slip '{
    "certificateId": 7,
    "typeLabel": "진료확인서",
    "patientName": "홍길동",
    "patientNo": "P00017",
    "serialNo": "2026-000007",
    "issuedAt": "2026-09-01T15:02:00",
    "issuerName": "김의사",
    "issuerLicenseNo": "12345"
  }'
fi

# 4) QR 크기 실측 — 실제 쓸 아이패드로 하나씩 찍어보고 가장 작은 값을 QR_SIZE 에
if [ "$ONLY" = "all" ] || [ "$ONLY" = "qr" ]; then
  call "5) QR 캘리브레이션" POST /debug/qr-calibration "{\"sizes\": $QR_SIZES}"
fi

echo
