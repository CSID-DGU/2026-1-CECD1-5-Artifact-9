package com.artifact.diagnosis.common.util;

/**
 * LIKE 검색어에 섞여 들어온 와일드카드를 무력화한다.
 *
 * 왜 필요한가. SQL의 {@code LIKE}에서 {@code %}는 "아무 글자나 몇 개든",
 * {@code _}는 "아무 글자 하나"를 뜻한다. 검색어를 그대로 {@code "%" + 입력 + "%"} 로 감싸면
 * 사용자가 친 {@code %} 가 와일드카드로 살아난다 — 환자 검색창에 {@code %} 한 글자만 넣으면
 * 전체 환자 명단이 그대로 나온다. 이름을 몰라도 목록을 통째로 받아낼 수 있다는 뜻이다.
 *
 * SQL 인젝션은 아니다. JPA가 값을 파라미터로 바인딩하므로 쿼리 구조는 바뀌지 않는다.
 * 하지만 의도한 것보다 훨씬 많은 데이터가 나간다는 점에서 결과는 비슷하게 나쁘다.
 *
 * 이스케이프 문자로 {@code !} 를 쓰는 이유. 흔히 쓰는 백슬래시는 MySQL이
 * 문자열 리터럴 단계에서 한 번 더 해석해서, JPQL → SQL → MySQL 파서를 거치는 동안
 * 몇 번을 겹쳐 써야 하는지가 환경마다 달라진다. {@code !} 는 어느 단계에서도 특별한 의미가 없어
 * H2(테스트)와 MySQL(운영)에서 똑같이 동작한다.
 *
 * 이 클래스를 쓰는 쿼리는 반드시 {@code escape '!'} 절을 함께 적어야 한다.
 * 빠뜨리면 이스케이프 문자가 그냥 {@code !} 글자로 취급돼 조용히 검색이 어긋난다.
 */
public final class LikeEscape {

    /** 쿼리의 {@code escape '!'} 절과 반드시 같은 문자여야 한다. */
    public static final char ESCAPE_CHAR = '!';

    private LikeEscape() {
    }

    /**
     * 부분일치({@code %검색어%}) 패턴을 만든다.
     *
     * 빈 문자열이나 {@code null} 은 "전체 조회"가 된다. 이는 기존 동작 그대로이며
     * 이 클래스가 바꾸려는 대상이 아니다 — 여기서 막는 것은 검색어 안에 숨은 와일드카드다.
     */
    public static String contains(String raw) {
        if (raw == null) {
            return "%";
        }
        return "%" + escape(raw) + "%";
    }

    /**
     * 와일드카드 문자 앞에 이스케이프 문자를 붙인다.
     *
     * 순서가 중요하다. 이스케이프 문자 자신({@code !})을 가장 먼저 치환해야 한다.
     * {@code %} 를 먼저 처리하면 그때 새로 붙인 {@code !} 까지 다음 단계에서 또 이스케이프돼
     * {@code !!%} 가 되어 버린다.
     */
    private static String escape(String raw) {
        return raw
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }
}
