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
# 실물 프린터가 물려 있으면 종이가 나온다. 종이 없이 확인만 하려면
# 에이전트를 DRY_RUN=true 로 띄운다.

set -u
AGENT="${AGENT:-http://localhost:5051}"
ONLY="${1:-all}"

# 캘리브레이션에서 뽑아볼 QR 크기. 필요하면 바꿔서 쓴다.
QR_SIZES="${QR_SIZES:-[6, 7, 8, 10]}"

call() {
  local label="$1" method="$2" path="$3" body="${4:-}"
  echo
  echo "── $label ─────────────────────────────────────────"
  if [ -n "$body" ]; then
    curl -sS -w '\nHTTP %{http_code}\n' -X "$method" "$AGENT$path" \
      -H 'Content-Type: application/json' -d "$body"
  else
    curl -sS -w '\nHTTP %{http_code}\n' -X "$method" "$AGENT$path"
  fi
}

# 0) 살아 있는지 + 프린터가 실제로 열리는지(printerReady)
if [ "$ONLY" = "all" ] || [ "$ONLY" = "health" ]; then
  call "0) health" GET /health
fi

# 1) 접수증 — 대기번호 + 키오스크 QR
if [ "$ONLY" = "all" ] || [ "$ONLY" = "ticket" ]; then
  call "1) 접수증" POST /print/ticket '{
    "visitNo": "V00042",
    "patientName": "홍길동",
    "patientNo": "P00017",
    "kioskToken": "aB3xK9pQ"
  }'
fi

# 2) 진료 요약서 — 긴 약품명 줄바꿈과 [AI 분석 참고] 블록까지 확인되는 본문
if [ "$ONLY" = "all" ] || [ "$ONLY" = "summary" ]; then
  call "2) 진료 요약서" POST /print/visit-summary '{
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
  call "3) 발급확인증" POST /print/certificate-slip '{
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
  call "4) QR 캘리브레이션" POST /debug/qr-calibration "{\"sizes\": $QR_SIZES}"
fi

echo
