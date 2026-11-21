import type { SyncFailure } from '../api/types';

interface Props {
  failures: SyncFailure[];
  onClose: () => void;
  onGoFamily: () => void;
}

function formatLastSynced(iso: string | null): string {
  if (!iso) return '아직 한 번도 불러오지 못했어요';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `마지막 성공 ${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())} · 예전 정보일 수 있어요`;
}

/**
 * 대시보드 상단 경고 배너 — 일부 가족 계정 동기화가 실패해 화면에 예전 데이터가 남아있을 수 있음을 알린다.
 * 실패한 계정의 DB 데이터는 지워지지 않고 그대로 보이므로, 이 표시가 없으면 오래된 정보(예: 이미 반납한 책의
 * "연체 77일")가 최신처럼 보인다. ✕로 닫을 수 있고, 새로고침을 누르면 사라졌다가 그 결과로 다시 판단한다.
 */
export function SyncFailureBanner({ failures, onClose, onGoFamily }: Props) {
  const needsPasswordFix = failures.some((f) => f.credentialsRejected);
  return (
    <div
      role="alert"
      data-testid="sync-failure-banner"
      style={{
        margin: '12px 20px 0',
        padding: '10px 12px',
        borderRadius: 'var(--radius-md)',
        border: '1px solid var(--color-accent-red)',
        background: 'color-mix(in srgb, var(--color-accent-red) 6%, var(--color-bg-surface))',
        display: 'flex',
        gap: 8,
        alignItems: 'flex-start',
      }}
    >
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 6 }}>
        {failures.map((f) => (
          <div key={f.loginId} style={{ fontSize: 'var(--font-size-xs)', color: 'var(--color-text)' }}>
            <div style={{ fontWeight: 'var(--font-weight-bold)', color: 'var(--color-accent-red)' }}>
              ⚠ {f.memberName} 계정을 불러오지 못했어요
              {f.credentialsRejected ? ' (아이디/비밀번호 확인 필요)' : ''}
            </div>
            <div style={{ color: 'var(--color-neutral-600)', marginTop: 2 }}>{formatLastSynced(f.lastSyncedAt)}</div>
          </div>
        ))}
        {needsPasswordFix && (
          <button
            onClick={onGoFamily}
            style={{
              alignSelf: 'flex-start',
              background: 'none',
              border: 'none',
              padding: 0,
              fontSize: 'var(--font-size-xs)',
              color: 'var(--color-link)',
              cursor: 'pointer',
            }}
          >
            가족 관리에서 수정하기 ›
          </button>
        )}
      </div>
      <button
        aria-label="경고 닫기"
        data-testid="sync-failure-banner-close"
        onClick={onClose}
        style={{
          background: 'none',
          border: 'none',
          padding: 2,
          cursor: 'pointer',
          color: 'var(--color-neutral-600)',
          fontSize: 16,
          lineHeight: 1,
        }}
      >
        ✕
      </button>
    </div>
  );
}
