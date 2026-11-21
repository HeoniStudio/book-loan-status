package com.woori.library.service.crawler;

/** issl.go.kr 로그인(아이디/비밀번호) 실패 시 발생. */
public class IsslAuthException extends RuntimeException {

    /**
     * 도서관 사이트가 자격증명 자체를 거부했는지(아이디/비밀번호 오류 등 사이트가 띄운 경고창) 여부. false면 응답 지연·
     * 서버 오류처럼 다시 시도하면 풀릴 수 있는 실패다 — 화면에서 "비밀번호 확인" 안내를 할지 정하는 데 쓴다.
     */
    private final boolean credentialsRejected;

    public IsslAuthException(String message) {
        this(message, false);
    }

    public IsslAuthException(String message, boolean credentialsRejected) {
        super(message);
        this.credentialsRejected = credentialsRejected;
    }

    public boolean isCredentialsRejected() {
        return credentialsRejected;
    }
}
