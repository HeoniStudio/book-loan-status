package com.woori.library.service.loan;

import com.woori.library.domain.LibraryAccount;
import com.woori.library.domain.LoanRecord;
import com.woori.library.domain.MutualLoanHistoryRecord;
import com.woori.library.domain.MutualLoanRecord;
import com.woori.library.domain.ReservationRecord;
import com.woori.library.dto.SyncResponse;
import com.woori.library.repository.LibraryAccountRepository;
import com.woori.library.repository.LoanRecordRepository;
import com.woori.library.repository.MutualLoanHistoryRecordRepository;
import com.woori.library.repository.MutualLoanRecordRepository;
import com.woori.library.repository.ReservationRecordRepository;
import com.woori.library.service.crawler.IsslAuthException;
import com.woori.library.service.crawler.IsslLoanScrapeService;
import com.woori.library.service.crawler.IsslLoginService;
import com.woori.library.service.crawler.IsslMutualLoanScrapeService;
import com.woori.library.service.crawler.IsslReservationScrapeService;
import com.woori.library.service.crawler.IsslSession;
import com.woori.library.service.crawler.LoanRecordDraft;
import com.woori.library.service.crawler.MutualLoanRecordDraft;
import com.woori.library.service.crawler.ReservationRecordDraft;
import com.woori.library.service.crypto.AesGcmCipherService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 도서관 계정별로 실제 크롤링을 수행하고, 대출현황/일반예약현황/상호대차현황(신청현황)을 한 로그인 세션 안에서
 * 함께 갱신한다. 세 화면을 매번 따로 로그인해서 긁으면 issl.go.kr에 불필요한 부하를 주므로(계정 잠금 위험) 한
 * 세션을 재사용한다.
 *
 * <p><b>병렬 처리</b>: 계정 하나 크롤링에 수십 초가 걸려서(issl.go.kr 응답 자체가 느림) 계정을 순서대로 돌면
 * 가족 수에 비례해 기다리는 시간이 늘어난다. 그래서 크롤링(네트워크/브라우저 작업)만 {@code crawlerExecutor} 풀에서
 * 계정별로 동시에 돌리고, DB 반영은 모든 크롤링이 끝난 뒤 호출 스레드에서 순서대로 한다 — JPA 엔티티를 여러 스레드가
 * 동시에 만지지 않게 하기 위함. 동시 실행 수 상한은 풀 크기({@code app.crawler.issl.max-parallel})로 제한된다.
 */
@Service
public class LoanSyncService {

    private static final Logger log = LoggerFactory.getLogger(LoanSyncService.class);

    private final LibraryAccountRepository libraryAccountRepository;
    private final LoanRecordRepository loanRecordRepository;
    private final ReservationRecordRepository reservationRecordRepository;
    private final MutualLoanRecordRepository mutualLoanRecordRepository;
    private final MutualLoanHistoryRecordRepository mutualLoanHistoryRecordRepository;
    private final IsslLoginService isslLoginService;
    private final IsslLoanScrapeService isslLoanScrapeService;
    private final IsslReservationScrapeService isslReservationScrapeService;
    private final IsslMutualLoanScrapeService isslMutualLoanScrapeService;
    private final AesGcmCipherService cipherService;
    private final ExecutorService crawlerExecutor;

    /**
     * 사용자별로 진행 중인 전체 동기화. 페이지 새로고침·여러 탭·자동 갱신·스케줄러가 겹쳐 같은 사용자의 동기화가
     * 동시에 들어오면(2026-11-22 로컬에서 12초 간격 중복 요청 실측), 새로 크롤링하지 않고 진행 중인 결과를 같이
     * 받는다 — 같은 계정으로 issl.go.kr에 동시에 두 번 로그인하는 것과 DB 동시 갱신을 막기 위함.
     */
    private final ConcurrentMap<Long, CompletableFuture<SyncResponse>> inFlightSyncs = new ConcurrentHashMap<>();

    public LoanSyncService(
        LibraryAccountRepository libraryAccountRepository,
        LoanRecordRepository loanRecordRepository,
        ReservationRecordRepository reservationRecordRepository,
        MutualLoanRecordRepository mutualLoanRecordRepository,
        MutualLoanHistoryRecordRepository mutualLoanHistoryRecordRepository,
        IsslLoginService isslLoginService,
        IsslLoanScrapeService isslLoanScrapeService,
        IsslReservationScrapeService isslReservationScrapeService,
        IsslMutualLoanScrapeService isslMutualLoanScrapeService,
        AesGcmCipherService cipherService,
        @Qualifier("crawlerExecutor") ExecutorService crawlerExecutor) {
        this.libraryAccountRepository = libraryAccountRepository;
        this.loanRecordRepository = loanRecordRepository;
        this.reservationRecordRepository = reservationRecordRepository;
        this.mutualLoanRecordRepository = mutualLoanRecordRepository;
        this.mutualLoanHistoryRecordRepository = mutualLoanHistoryRecordRepository;
        this.isslLoginService = isslLoginService;
        this.isslLoanScrapeService = isslLoanScrapeService;
        this.isslReservationScrapeService = isslReservationScrapeService;
        this.isslMutualLoanScrapeService = isslMutualLoanScrapeService;
        this.cipherService = cipherService;
        this.crawlerExecutor = crawlerExecutor;
    }

    public SyncResponse sync(Long ownerUserId, Long onlyLibraryAccountId) {
        if (onlyLibraryAccountId != null) {
            return doSync(ownerUserId, onlyLibraryAccountId);
        }
        CompletableFuture<SyncResponse> mine = new CompletableFuture<>();
        CompletableFuture<SyncResponse> running = inFlightSyncs.putIfAbsent(ownerUserId, mine);
        if (running != null) {
            log.info("[sync] ownerUserId={} 이미 진행 중인 동기화가 있어 그 결과를 함께 기다림", ownerUserId);
            return unwrap(running);
        }
        try {
            SyncResponse response = doSync(ownerUserId, null);
            mine.complete(response);
            return response;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlightSyncs.remove(ownerUserId, mine);
        }
    }

    private SyncResponse doSync(Long ownerUserId, Long onlyLibraryAccountId) {
        List<LibraryAccount> accounts = libraryAccountRepository.findByFamilyMember_OwnerUserId(ownerUserId);
        if (onlyLibraryAccountId != null) {
            accounts = accounts.stream().filter(a -> a.getId().equals(onlyLibraryAccountId)).toList();
        }

        long t0 = System.currentTimeMillis();
        // 엔티티 필드 읽기/복호화는 호출 스레드에서 끝내고, 작업 스레드에는 문자열만 넘긴다.
        List<CompletableFuture<CrawlResult>> futures = new ArrayList<>(accounts.size());
        for (LibraryAccount account : accounts) {
            String loginId = account.getLoginId();
            String encryptedPassword = account.getEncryptedPassword();
            futures.add(CompletableFuture.supplyAsync(
                () -> crawl(loginId, cipherService.decrypt(encryptedPassword)), crawlerExecutor));
        }

        int synced = 0;
        List<String> failed = new ArrayList<>();
        List<SyncResponse.SyncFailure> failures = new ArrayList<>();
        for (int i = 0; i < accounts.size(); i++) {
            LibraryAccount account = accounts.get(i);
            try {
                CrawlResult result = unwrap(futures.get(i));
                replaceLoanRecords(account, result.loans());
                mergeReservationRecords(account, result.reservations());
                mergeMutualLoanRecords(account, result.mutualLoans());
                replaceMutualLoanHistoryRecords(account, result.mutualLoanHistory());
                account.setLastLoginOk(true);
                account.setLastSyncedAt(Instant.now());
                libraryAccountRepository.save(account);
                synced++;
            } catch (IsslAuthException e) {
                log.warn("동기화 중 issl.go.kr 로그인 실패 (loginId={}): {}", account.getLoginId(), e.getMessage());
                account.setLastLoginOk(false);
                libraryAccountRepository.save(account);
                failed.add(account.getLoginId() + ": " + e.getMessage());
                failures.add(toFailure(account, e.isCredentialsRejected()));
            } catch (RuntimeException e) {
                log.error("동기화 중 예상 못한 오류 (loginId={})", account.getLoginId(), e);
                failed.add(account.getLoginId() + ": " + e.getMessage());
                failures.add(toFailure(account, false));
            }
        }
        log.info(
            "[sync] ownerUserId={} 계정 {}개 동기화 완료 ({}ms, 성공 {} / 실패 {})",
            ownerUserId, accounts.size(), System.currentTimeMillis() - t0, synced, failed.size());
        return new SyncResponse(synced, failed, failures);
    }

    private static SyncResponse.SyncFailure toFailure(LibraryAccount account, boolean credentialsRejected) {
        // lastSyncedAt은 성공 시에만 갱신되므로 곧 "화면에 보이는 이 가족 정보의 기준 시점"이다.
        return new SyncResponse.SyncFailure(
            account.getFamilyMember().getName(), account.getLoginId(), credentialsRejected, account.getLastSyncedAt());
    }

    /** 작업 스레드에서 던진 예외를 원래 타입 그대로 꺼내서, 위의 IsslAuthException/RuntimeException 분기가 그대로 동작하게 한다. */
    private static <T> T unwrap(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    private record CrawlResult(
        List<LoanRecordDraft> loans,
        List<ReservationRecordDraft> reservations,
        List<MutualLoanRecordDraft> mutualLoans,
        List<MutualLoanRecordDraft> mutualLoanHistory) {}

    private CrawlResult crawl(String loginId, String password) {
        long t0 = System.currentTimeMillis();
        try (IsslSession session = isslLoginService.login(loginId, password)) {
            long tLogin = System.currentTimeMillis();
            List<LoanRecordDraft> loans = isslLoanScrapeService.fetchLoans(session);
            long tLoans = System.currentTimeMillis();
            List<ReservationRecordDraft> reservations = isslReservationScrapeService.fetchReservations(session);
            long tReservations = System.currentTimeMillis();
            List<MutualLoanRecordDraft> mutualLoans = isslMutualLoanScrapeService.fetchMutualLoans(session);
            long tMutual = System.currentTimeMillis();
            List<MutualLoanRecordDraft> mutualLoanHistory = isslMutualLoanScrapeService.fetchMutualLoanHistory(session);
            long tEnd = System.currentTimeMillis();
            // 어느 단계가 느린지 실측하기 위한 로그 — 비밀번호 등 민감정보는 남기지 않는다.
            log.info(
                "[sync-timing] loginId={} 전체 {}ms (로그인 {} / 대출 {} / 예약 {} / 상호대차 {} / 이력 {})",
                loginId, tEnd - t0, tLogin - t0, tLoans - tLogin, tReservations - tLoans, tMutual - tReservations,
                tEnd - tMutual);
            return new CrawlResult(loans, reservations, mutualLoans, mutualLoanHistory);
        }
    }

    /** 대출현황은 상태를 유지할 필요가 없는 스냅샷이라 매번 전체 재작성한다. */
    private void replaceLoanRecords(LibraryAccount account, List<LoanRecordDraft> drafts) {
        loanRecordRepository.deleteAll(loanRecordRepository.findByLibraryAccountId(account.getId()));
        for (LoanRecordDraft draft : drafts) {
            loanRecordRepository.save(
                new LoanRecord(account, draft.bookTitle(), draft.branchName(), draft.loanDate(), draft.dueDate()));
        }
    }

    /** 이력현황은 이미 종결된 건이라 loan_record와 동일하게 알림 상태를 보존할 필요 없이 전체 재작성한다. */
    private void replaceMutualLoanHistoryRecords(LibraryAccount account, List<MutualLoanRecordDraft> drafts) {
        mutualLoanHistoryRecordRepository.deleteAll(mutualLoanHistoryRecordRepository.findByLibraryAccountId(account.getId()));
        for (MutualLoanRecordDraft draft : drafts) {
            mutualLoanHistoryRecordRepository.save(
                new MutualLoanHistoryRecord(
                    account, draft.bookTitle(), draft.appliedAt(), draft.branchName(), draft.pickupBranchName(),
                    draft.statusText()));
        }
    }

    /**
     * 예약/상호대차 건은 {@code ready_notified_at}(대출가능 알림 중복 발송 방지 플래그)을 보존해야 하므로,
     * loan_record처럼 전체 삭제 후 재삽입하지 않고 자연키(도서명+날짜) 기준으로 갱신/삽입/삭제한다.
     */
    private void mergeReservationRecords(LibraryAccount account, List<ReservationRecordDraft> drafts) {
        Map<String, ReservationRecord> existing = new HashMap<>();
        for (ReservationRecord r : reservationRecordRepository.findByLibraryAccountId(account.getId())) {
            existing.put(r.getBookTitle() + "|" + r.getReservedAt(), r);
        }
        for (ReservationRecordDraft draft : drafts) {
            String key = draft.bookTitle() + "|" + draft.reservedAt();
            ReservationRecord record = existing.remove(key);
            if (record == null) {
                record = new ReservationRecord(
                    account, draft.bookTitle(), draft.branchName(), draft.reservedAt(), draft.expiresAt(), draft.rank(),
                    draft.statusText());
            } else {
                record.setBranchName(draft.branchName());
                record.setExpiresAt(draft.expiresAt());
                record.setRank(draft.rank());
                record.setStatusText(draft.statusText());
                record.setFetchedAt(Instant.now());
            }
            reservationRecordRepository.save(record);
        }
        // drafts에 더 이상 없는 예약(취소/만료/대출 전환 등)은 삭제.
        reservationRecordRepository.deleteAll(existing.values());
    }

    private void mergeMutualLoanRecords(LibraryAccount account, List<MutualLoanRecordDraft> drafts) {
        Map<String, MutualLoanRecord> existing = new HashMap<>();
        for (MutualLoanRecord r : mutualLoanRecordRepository.findByLibraryAccountId(account.getId())) {
            existing.put(r.getBookTitle() + "|" + r.getAppliedAt(), r);
        }
        for (MutualLoanRecordDraft draft : drafts) {
            String key = draft.bookTitle() + "|" + draft.appliedAt();
            MutualLoanRecord record = existing.remove(key);
            if (record == null) {
                record = new MutualLoanRecord(
                    account, draft.bookTitle(), draft.appliedAt(), draft.branchName(), draft.pickupBranchName(),
                    draft.statusText());
            } else {
                record.setBranchName(draft.branchName());
                record.setPickupBranchName(draft.pickupBranchName());
                record.setStatusText(draft.statusText());
                record.setFetchedAt(Instant.now());
            }
            mutualLoanRecordRepository.save(record);
        }
        mutualLoanRecordRepository.deleteAll(existing.values());
    }
}
