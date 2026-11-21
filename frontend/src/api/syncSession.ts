import { syncLoans } from './loans';
import type { SyncFailure, SyncResponse } from './types';

/**
 * 대시보드 동기화 상태를 페이지 컴포넌트 밖(모듈 단위)에 보관한다.
 *
 * <p>다른 화면에 갔다가 대시보드로 돌아오면 DashboardPage가 새로 마운트되는데, 상태를 컴포넌트 안에만 두면
 * "방금 동기화했다"는 사실을 잊고 진입 시 자동 동기화를 또 돌려서 issl.go.kr에 불필요하게 다시 로그인했다
 * (2026-11-22 사용자 제보). 브라우저 새로고침(F5) 시에는 이 모듈도 초기화되므로 그때는 정상적으로 다시 동기화한다.
 */
interface SyncSessionState {
  /** 마지막 동기화가 끝난 시각(ms). 아직 없으면 null */
  lastFinishedAt: number | null;
  /** 화면 하단 "최근 갱신" 표시 문자열 */
  lastSyncedLabel: string | null;
  /** 마지막 동기화에서 실패한 계정(경고 배너). 사용자가 ✕로 닫으면 비운다 */
  failures: SyncFailure[];
  inFlight: Promise<SyncResponse> | null;
}

export const syncSession: SyncSessionState = {
  lastFinishedAt: null,
  lastSyncedLabel: null,
  failures: [],
  inFlight: null,
};

function formatLabel(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}.${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 진행 중인 동기화가 있으면 그 결과를 같이 기다리고, 없으면 새로 시작한다. */
export function runSync(): Promise<SyncResponse> {
  if (syncSession.inFlight) return syncSession.inFlight;
  syncSession.failures = [];
  const promise = syncLoans()
    .then((result) => {
      syncSession.failures = result.failures ?? [];
      syncSession.lastSyncedLabel = formatLabel(new Date());
      return result;
    })
    .finally(() => {
      syncSession.lastFinishedAt = Date.now();
      syncSession.inFlight = null;
    });
  syncSession.inFlight = promise;
  return promise;
}

/** 로그아웃 시 다른 사용자가 같은 탭에서 로그인해도 이전 상태가 남지 않게 초기화한다. */
export function resetSyncSession(): void {
  syncSession.lastFinishedAt = null;
  syncSession.lastSyncedLabel = null;
  syncSession.failures = [];
  syncSession.inFlight = null;
}
