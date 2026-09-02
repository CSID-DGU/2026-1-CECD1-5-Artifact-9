"""백엔드에서 인쇄 작업을 가져와 프린터로 보내는 폴링 루프.

왜 이 방향인가
--------------
백엔드는 EC2 컨테이너 안에 있고 프린터는 이 맥북 USB 에 물려 있다. 서버가 맥북을
부르려면 맥북에 인터넷에서 닿는 주소가 있어야 하는데, 접수 데스크는 공유기 뒤라
터널을 열어야 하고 무료 터널 주소는 껐다 켤 때마다 바뀐다. 주소가 바뀔 때마다
서버의 환경변수를 사람이 고쳐야 하면 "설정 없이 항상 되는" 상태가 될 수 없다.

그래서 방향을 뒤집었다. 이쪽에서 서버에 접속해 "뽑을 것 있나" 를 묻는다.
나가는 연결만 쓰므로 공유기·방화벽·주소와 무관하고, 서버에는 넣을 설정이 없다.

    [EC2 백엔드]  <--HTTPS--  [여기]  --USB-->  [프린터]

왜 requests 를 안 쓰나
----------------------
표준 라이브러리 urllib 로 충분하고, 이 프로세스는 접수 데스크 맥북에서 사람 없이
떠 있어야 한다. 의존성이 하나 늘면 파이썬을 올렸을 때 휠이 없어 설치가 깨지는
경로가 하나 늘어난다(requirements.txt 의 pydantic 주석 참고).

왜 스레드인가
-------------
프린터 출력은 USB 블로킹 I/O 다. 이벤트 루프에서 돌리면 그동안 FastAPI 가
/health 응답조차 못 한다. printer.py 의 _print_lock 이 HTTP 경로와 이 루프가
동시에 프린터를 여는 것을 막아 주므로, 별도 스레드로 두면 그대로 안전하다.
"""

import json
import logging
import threading
import time
import urllib.error
import urllib.request
import uuid

import config
import documents
import printer as pr

log = logging.getLogger("print-agent.poller")


class _Unauthorized(Exception):
    """JWT 가 만료됐거나 자격이 틀렸다. 다시 로그인해야 한다."""


def _request(method: str, url: str, *, token: str | None = None,
             body: dict | None = None, timeout: float = 30.0,
             extra_headers: dict | None = None):
    """JSON 요청 하나. (status, 파싱된 본문 또는 None) 을 돌려준다.

    204(뽑을 것 없음)와 401(토큰 만료)은 오류가 아니라 정상적인 흐름이라
    예외로 만들지 않고 상태 코드로 다룬다.
    """
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if extra_headers:
        headers.update(extra_headers)

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            raw = res.read()
            if not raw:
                return res.status, None
            return res.status, json.loads(raw)
    except urllib.error.HTTPError as exc:
        if exc.code in (401, 403):
            raise _Unauthorized(f"HTTP {exc.code}") from exc
        raise


class Poller(threading.Thread):
    """작업을 가져와 출력하고 결과를 돌려주는 루프. 데몬 스레드로 돈다."""

    def __init__(self):
        super().__init__(name="print-poller", daemon=True)
        self._stop = threading.Event()
        self._token: str | None = None
        # 연속 실패 횟수. 백오프 계산과 로그 억제에 함께 쓴다.
        self._failures = 0
        # 이 프로세스의 이름표. 재시작할 때마다 새로 만들어진다.
        #
        # 폴링을 하다가 프로세스가 죽으면, 서버 쪽에는 그 연결이 끊긴 줄 모른 채
        # 최대 25초 더 살아 있는 것으로 남는다. 그동안 접수가 들어오면 서버는 그
        # 유령 연결에 작업을 건네고, 종이는 나오지 않는다. 새 이름표를 들고 오면
        # 서버가 이전 이름표의 연결을 그 자리에서 걷어낸다.
        self._instance = uuid.uuid4().hex[:12]

    def stop(self):
        self._stop.set()

    # ── 루프 ────────────────────────────────────────────────────────────────

    def run(self):
        log.info("인쇄 작업 폴링 시작 — %s (계정 %s, 인스턴스 %s)",
                 config.BACKEND_URL, config.BACKEND_LOGIN_ID, self._instance)
        while not self._stop.is_set():
            try:
                job = self._next_job()
                self._failures = 0
                if job is not None:
                    self._handle(job)
            except _Unauthorized as exc:
                # 토큰 만료는 하루에 한 번쯤 정상적으로 일어난다. 버리고 다시 받는다.
                log.info("인증이 만료돼 다시 로그인한다 (%s)", exc)
                self._token = None
                self._sleep_backoff()
            except Exception as exc:
                self._failures += 1
                # 서버 재배포·와이파이 끊김은 몇 초 뒤면 돌아온다. 매번 스택트레이스를
                # 남기면 로그가 못 쓰게 되므로, 계속 실패할 때만 크게 알린다.
                level = logging.WARNING if self._failures in (1, 5) or self._failures % 60 == 0 \
                    else logging.DEBUG
                log.log(level, "백엔드에 닿지 못했다 (%d회째): %s", self._failures, exc)
                self._sleep_backoff()
        log.info("인쇄 작업 폴링 종료")

    def _sleep_backoff(self):
        """실패가 이어지면 간격을 벌린다 — 최대 30초.

        서버가 죽어 있는 동안 1초마다 두드리면 로그만 쌓이고 배터리를 먹는다.
        30초를 넘기지 않는 이유는, 서버가 돌아왔을 때 그만큼 늦게 알아채기 때문이다.
        """
        delay = min(30.0, 2.0 * max(1, self._failures))
        self._stop.wait(delay)

    # ── 단계 ────────────────────────────────────────────────────────────────

    def _login(self) -> str:
        status, body = _request(
            "POST", f"{config.BACKEND_URL}/api/v1/auth/login",
            body={"loginId": config.BACKEND_LOGIN_ID, "password": config.BACKEND_PASSWORD},
            timeout=15.0,
        )
        token = (body or {}).get("token")
        if not token:
            raise RuntimeError(f"로그인 응답에 토큰이 없다 (HTTP {status})")
        log.info("백엔드 로그인 성공 — %s", (body or {}).get("role", "?"))
        return token

    def _ensure_token(self) -> str:
        if self._token is None:
            self._token = self._login()
        return self._token

    def _next_job(self) -> dict | None:
        """롱 폴링. 뽑을 것이 없으면 서버가 204 로 끊고, 그러면 곧바로 다시 묻는다."""
        token = self._ensure_token()
        # 서버는 poll-wait-seconds(기본 25초)에 끊는다. 여기 타임아웃은 그보다
        # 넉넉해야 한다 — 더 짧으면 서버가 막 건네려던 작업을 받지 못하고 끊는다.
        status, body = _request(
            "GET", f"{config.BACKEND_URL}/api/v1/print/jobs/next",
            token=token, timeout=config.POLL_TIMEOUT_SECONDS,
            extra_headers={"X-Agent-Instance": self._instance},
        )
        if status == 204 or not body:
            return None
        return body

    def _handle(self, job: dict):
        job_id = job.get("jobId")
        doc_type = job.get("docType")
        payload = job.get("payload") or {}
        log.info("인쇄 작업 수신 [%s] %s", doc_type, job_id)

        try:
            documents.print_document(doc_type, payload)
            ok, detail = True, "출력했습니다."
        except documents.UnknownDocumentType as exc:
            # 서버가 이 에이전트보다 새 문서 종류를 보냈다. 재시도해도 소용없으니
            # 실패를 알리고 넘어간다 — 큐에 남겨 두면 같은 작업이 계속 돌아온다.
            ok, detail = False, str(exc)
            log.warning("알 수 없는 문서 종류 [%s]", doc_type)
        except pr.PrinterError as exc:
            ok, detail = False, str(exc)
            log.warning("출력 실패 [%s]: %s", doc_type, exc)
        except Exception as exc:
            ok, detail = False, f"출력 중 오류: {exc}"
            log.exception("출력 중 예상치 못한 오류 [%s]", doc_type)

        self._report(job_id, ok, detail)

    def _report(self, job_id: str, ok: bool, detail: str):
        """결과 회신.

        여기서 실패해도 다시 시도하지 않는다. 종이는 이미 나왔고, 회신이 없으면
        서버가 잠시 뒤 같은 작업을 한 번 더 건넨다(중복 한 장). 여기서 재시도까지
        겹치면 언제 몇 장이 나올지 알 수 없게 되므로, 되돌리는 판단은 서버 한 곳에
        모아 둔다.
        """
        try:
            _request(
                "POST", f"{config.BACKEND_URL}/api/v1/print/jobs/{job_id}/result",
                token=self._token, body={"ok": ok, "detail": detail}, timeout=15.0,
            )
        except _Unauthorized:
            self._token = None
            log.info("결과 회신 중 인증 만료 — 다음 회차에 다시 로그인한다")
        except Exception as exc:
            log.warning("출력 결과를 알리지 못했다 (jobId=%s): %s", job_id, exc)


_poller: Poller | None = None


def start() -> bool:
    """설정이 갖춰져 있으면 폴링을 시작한다. 시작했으면 True."""
    global _poller
    if not config.POLL_ENABLED:
        log.info("폴링 꺼짐 (POLL_ENABLED=false) — HTTP 요청을 받아서만 출력한다")
        return False
    if not (config.BACKEND_LOGIN_ID and config.BACKEND_PASSWORD):
        log.warning(
            "폴링에 쓸 계정이 없다. .env 에 BACKEND_LOGIN_ID / BACKEND_PASSWORD 를 넣어야 "
            "원격 배포본의 접수증이 이 프린터로 나온다."
        )
        return False
    _poller = Poller()
    _poller.start()
    return True


def stop():
    if _poller is not None:
        _poller.stop()
