"""요청 인증.

기본 운영 형태(맥북 안에서만 도는 localhost 서비스)에서는 인증이 필요 없다.
AGENT_TOKEN 이 비어 있으면 이 모듈은 아무것도 하지 않는다.

터널로 공개할 때만 켠다. Cloudflare Tunnel 로 이 에이전트를 공개 HTTPS 주소에
올리면 EC2 백엔드가 맥북 프린터에 닿을 수 있게 되지만, 동시에 인터넷 전체가
닿을 수 있게 된다. 그 상태에서 토큰이 없으면 주소를 아는 누구나 병원 프린터로
종이를 뽑을 수 있다 — 용지를 소진시키는 장난부터, 환자에게 위조된 안내문을
쥐여주는 일까지 가능해진다.
"""

import logging
import secrets

from fastapi import Header, HTTPException

import config

log = logging.getLogger("print-agent.auth")


def require_token(authorization: str | None = Header(default=None)) -> None:
    """Authorization: Bearer <AGENT_TOKEN> 을 검사한다.

    AGENT_TOKEN 이 비어 있으면 무조건 통과시킨다. 토큰을 설정하지 않은 채
    터널만 열어두는 실수를 막을 방법은 코드에 없으므로, 그 경고는 기동 로그
    (main.py 의 startup)와 /health 응답의 authRequired 필드로 드러낸다.

    비교에 secrets.compare_digest 를 쓰는 이유: 문자열 비교(==)는 앞에서부터
    다른 글자가 나오는 즉시 끝나서, 응답 시간 차이로 토큰을 한 글자씩 알아낼 수
    있다. 공개된 주소에서는 실제로 시도할 수 있는 공격이다.
    """
    if not config.AGENT_TOKEN:
        return

    expected = f"Bearer {config.AGENT_TOKEN}"
    if not authorization or not secrets.compare_digest(authorization, expected):
        log.warning("인증 실패한 요청을 거부했다 (Authorization 헤더 %s)",
                    "없음" if not authorization else "불일치")
        raise HTTPException(status_code=401, detail="인증 토큰이 필요합니다 (Authorization: Bearer ...).")
