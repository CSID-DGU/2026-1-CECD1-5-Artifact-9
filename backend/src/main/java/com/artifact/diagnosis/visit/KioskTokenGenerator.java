package com.artifact.diagnosis.visit;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 대기실 키오스크 QR URL(/kiosk/{token})에 실릴 접수별 토큰 생성기.
 * base62 12자 = 62^12(약 3.2×10^21) 경우의 수 — 같은 LAN에서 URL을 추측해
 * 남의 예비분석을 덮어쓰는 것을 막는 것이 목적이다.
 */
@Component
public class KioskTokenGenerator {

    /** Visit.kioskToken / visit.kiosk_token(CHAR(12))의 길이와 반드시 일치해야 한다. */
    public static final int TOKEN_LENGTH = 12;

    private static final String ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder token = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            token.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return token.toString();
    }
}
