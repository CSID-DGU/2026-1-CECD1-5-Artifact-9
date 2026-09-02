package com.artifact.diagnosis.docshare;

/**
 * 감열지 QR 의 문서 열람 링크가 유효기간을 넘겼을 때.
 *
 * "없는 링크"(404)와 굳이 구분하는 이유는 화면에 쓸 말이 다르기 때문이다.
 * 만료는 환자가 잘못한 것이 아니라 기한이 지난 것이므로, 접수처에 다시 요청하면
 * 된다는 안내로 이어져야 한다. 잘못된 주소에 그 안내를 띄우면 거짓말이 된다.
 *
 * 토큰이 base62 12자 난수라, 만료를 따로 알려준다고 해서 남의 문서를 찾는 데
 * 쓸 수 있는 정보가 새지는 않는다.
 */
public class ShareLinkExpiredException extends RuntimeException {

    public ShareLinkExpiredException(String message) {
        super(message);
    }
}
