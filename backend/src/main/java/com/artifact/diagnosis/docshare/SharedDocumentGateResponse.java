package com.artifact.diagnosis.docshare;

import java.time.LocalDateTime;

/**
 * 본인 확인 화면을 그리기 위한 최소 응답.
 *
 * <p><b>여기에 문서 내용을 담지 않는 것이 이 기능의 전부다.</b> 생년월일을 화면에서만 확인하고
 * 내용은 미리 받아둔다면, 개발자도구 Network 탭에 진단명과 소견이 그대로 남는다. 잠금이 아니라
 * 가림막일 뿐이다. 그래서 이 단계에서 서버가 알려주는 것은 "링크가 살아 있고 언제 닫히는가"뿐이고,
 * 실제 내용은 {@code POST .../verify} 가 생년월일을 맞춘 뒤에야 나간다.
 *
 * <p>서류 종류·환자명도 여기 넣지 않았다. 종이를 든 사람에게는 이미 인쇄되어 있는 값이라
 * 넣어도 얻는 게 없고, 링크 주소만 어깨너머로 본 사람에게는 새로 알려주는 정보가 되기 때문이다.
 *
 * @param expiresAt 이 링크가 닫히는 시각. 확인 화면 하단 안내에 그대로 쓴다.
 */
public record SharedDocumentGateResponse(LocalDateTime expiresAt) {}
