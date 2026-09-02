#!/bin/bash
#
# print-agent 를 맥 로그인 시 자동으로 뜨게 등록한다.
#
# 왜 필요한가
#   접수증은 접수하는 순간 나와야 한다. 그런데 이 서비스가 사람이 터미널에서
#   띄워야만 도는 것이면, 아침에 맥북을 켠 사람이 그 사실을 알고 명령을 쳐야
#   프린터가 산다. 접수 데스크에서 그걸 기대할 수 없다.
#
#   launchd 에 등록하면 로그인과 동시에 뜨고, 죽으면 알아서 다시 뜬다.
#   백엔드는 EC2 에 있고 이쪽이 접속해 작업을 가져가는 구조라(poller.py),
#   이 프로세스만 살아 있으면 원격 배포본의 접수증이 여기서 나온다.
#
# 사용법
#   ./install-launchd.sh            등록(또는 재등록)
#   ./install-launchd.sh uninstall  해제
#   ./install-launchd.sh status     상태 보기
#
set -euo pipefail

LABEL="com.artifact.print-agent"
AGENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG_DIR="$HOME/Library/Logs"
LOG="$LOG_DIR/artifact-print-agent.log"
PYTHON="$AGENT_DIR/.venv/bin/python"
TARGET="gui/$(id -u)/$LABEL"

usage_hint() {
  echo
  echo "로그:   tail -f $LOG"
  echo "상태:   $0 status"
  echo "해제:   $0 uninstall"
}

case "${1:-install}" in
  uninstall)
    launchctl bootout "gui/$(id -u)" "$PLIST" 2>/dev/null || true
    rm -f "$PLIST"
    echo "해제했다. 이제 print-agent 는 자동으로 뜨지 않는다."
    exit 0
    ;;
  status)
    if launchctl print "$TARGET" >/dev/null 2>&1; then
      launchctl print "$TARGET" | grep -E "state|pid|last exit" || true
    else
      echo "등록되어 있지 않다."
    fi
    echo
    tail -n 20 "$LOG" 2>/dev/null || echo "(로그 없음)"
    exit 0
    ;;
esac

# ── 사전 확인 ────────────────────────────────────────────────────────────────
# 여기서 걸러 두지 않으면 launchd 가 조용히 재시도만 반복하고, 왜 종이가 안 나오는지
# 알 방법이 로그를 파헤치는 것뿐이 된다.
[ -x "$PYTHON" ] || {
  echo "가상환경이 없다: $PYTHON"
  echo "먼저 만들 것:  python3 -m venv .venv && .venv/bin/pip install -r requirements.txt"
  exit 1
}
[ -f "$AGENT_DIR/.env" ] || {
  echo ".env 가 없다. .env.example 을 복사해 만들 것:"
  echo "  cp $AGENT_DIR/.env.example $AGENT_DIR/.env"
  exit 1
}
grep -q '^BACKEND_LOGIN_ID=.\+' "$AGENT_DIR/.env" || {
  echo "경고: .env 에 BACKEND_LOGIN_ID 가 비어 있다."
  echo "      그 상태로도 뜨지만 원격 배포본의 접수증은 이 프린터로 오지 않는다."
}

mkdir -p "$HOME/Library/LaunchAgents" "$LOG_DIR"

# ── plist 작성 ───────────────────────────────────────────────────────────────
# 경로를 스크립트가 직접 채운다. 손으로 적게 하면 프로젝트를 다른 곳으로 옮겼을 때
# 조용히 안 뜨는 상태가 된다.
cat > "$PLIST" <<PLIST_EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>$LABEL</string>

  <key>ProgramArguments</key>
  <array>
    <string>$PYTHON</string>
    <string>$AGENT_DIR/main.py</string>
  </array>

  <!-- .env 와 hospital-seal.svg 를 상대 경로로 찾는다. -->
  <key>WorkingDirectory</key>
  <string>$AGENT_DIR</string>

  <!-- 로그인하면 뜬다. -->
  <key>RunAtLoad</key>
  <true/>

  <!-- 죽으면 다시 띄운다. 프린터를 뽑았다 꽂거나 와이파이가 끊겨도 스스로 복구한다. -->
  <key>KeepAlive</key>
  <true/>

  <!-- 기동 실패가 반복될 때 launchd 가 두드리는 최소 간격(초). -->
  <key>ThrottleInterval</key>
  <integer>10</integer>

  <key>StandardOutPath</key>
  <string>$LOG</string>
  <key>StandardErrorPath</key>
  <string>$LOG</string>

  <key>EnvironmentVariables</key>
  <dict>
    <!-- launchd 는 로그인 셸을 거치지 않아 PATH 가 거의 비어 있다. libusb 가
         있는 homebrew 경로를 넣어 둔다. -->
    <key>PATH</key>
    <string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
    <!-- 로그가 버퍼에 갇혀 tail 로 안 보이는 일을 막는다. -->
    <key>PYTHONUNBUFFERED</key>
    <string>1</string>
  </dict>
</dict>
</plist>
PLIST_EOF

# ── 등록 ─────────────────────────────────────────────────────────────────────
# bootout 을 먼저 하는 이유: 이미 등록돼 있으면 bootstrap 이 "Input/output error"
# 로 실패한다. 재등록을 매번 되게 하려고 항상 내렸다 올린다.
launchctl bootout "gui/$(id -u)" "$PLIST" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
launchctl enable "$TARGET" 2>/dev/null || true

echo "등록했다: $LABEL"
echo "이제 맥북에 로그인하면 print-agent 가 저절로 뜬다."
usage_hint
