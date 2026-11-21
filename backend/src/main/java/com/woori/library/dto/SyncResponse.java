package com.woori.library.dto;

import java.time.Instant;
import java.util.List;

/**
 * @param failed 기존 호환용 "loginId: 사유" 문자열 목록
 * @param failures 화면 경고 배너용 계정별 실패 정보
 */
public record SyncResponse(int synced, List<String> failed, List<SyncFailure> failures) {

    /**
     * @param memberName 가족 구성원 이름
     * @param credentialsRejected 도서관이 아이디/비밀번호를 거부함(→ 가족 관리에서 비밀번호 수정 필요). false면 일시적 오류
     * @param lastSyncedAt 마지막으로 동기화에 성공한 시각(한 번도 성공 못 했으면 null) — 화면에 보이는 정보의 기준 시점
     */
    public record SyncFailure(String memberName, String loginId, boolean credentialsRejected, Instant lastSyncedAt) {}
}
