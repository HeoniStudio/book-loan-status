package com.woori.library.service.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.woori.library.domain.FamilyMember;
import com.woori.library.domain.LibraryAccount;
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
import com.woori.library.service.crypto.AesGcmCipherService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 실제 issl.go.kr 접속 없이(로그인/스크래핑은 mock) 병렬 동기화·실패 보고·중복 요청 합치기를 검증한다. */
class LoanSyncServiceTest {

    private final LibraryAccountRepository accountRepo = mock(LibraryAccountRepository.class);
    private final IsslLoginService loginService = mock(IsslLoginService.class);
    private final IsslLoanScrapeService loanScrape = mock(IsslLoanScrapeService.class);
    private final IsslReservationScrapeService reservationScrape = mock(IsslReservationScrapeService.class);
    private final IsslMutualLoanScrapeService mutualScrape = mock(IsslMutualLoanScrapeService.class);
    private final AesGcmCipherService cipher = mock(AesGcmCipherService.class);
    private ExecutorService crawlerExecutor;
    private LoanSyncService service;

    private final Instant lastSuccess = Instant.parse("2026-08-26T07:32:11Z");
    private LibraryAccount dad;
    private LibraryAccount mom;

    @BeforeEach
    void setUp() {
        crawlerExecutor = Executors.newFixedThreadPool(5);
        service = new LoanSyncService(
            accountRepo,
            mock(LoanRecordRepository.class),
            mock(ReservationRecordRepository.class),
            mock(MutualLoanRecordRepository.class),
            mock(MutualLoanHistoryRecordRepository.class),
            loginService, loanScrape, reservationScrape, mutualScrape, cipher, crawlerExecutor);

        dad = new LibraryAccount(new FamilyMember(1L, "아빠", true), "dad-id", "enc-dad");
        mom = new LibraryAccount(new FamilyMember(1L, "엄마", false), "mom-id", "enc-mom");
        mom.setLastSyncedAt(lastSuccess);
        when(accountRepo.findByFamilyMember_OwnerUserId(1L)).thenReturn(List.of(dad, mom));
        when(cipher.decrypt(anyString())).thenAnswer(inv -> "pw-" + inv.getArgument(0));
        when(loanScrape.fetchLoans(any())).thenReturn(List.of());
        when(reservationScrape.fetchReservations(any())).thenReturn(List.of());
        when(mutualScrape.fetchMutualLoans(any())).thenReturn(List.of());
        when(mutualScrape.fetchMutualLoanHistory(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        crawlerExecutor.shutdownNow();
    }

    @Test
    void 비밀번호_거부는_credentialsRejected로_보고되고_마지막_성공시각이_유지된다() {
        when(loginService.login(eq("dad-id"), anyString())).thenReturn(mock(IsslSession.class));
        when(loginService.login(eq("mom-id"), anyString()))
            .thenThrow(new IsslAuthException("로그인 실패: 비밀번호가 일치하지 않습니다", true));

        SyncResponse response = service.sync(1L, null);

        assertThat(response.synced()).isEqualTo(1);
        assertThat(response.failures()).singleElement().satisfies(f -> {
            assertThat(f.memberName()).isEqualTo("엄마");
            assertThat(f.loginId()).isEqualTo("mom-id");
            assertThat(f.credentialsRejected()).isTrue();
            assertThat(f.lastSyncedAt()).isEqualTo(lastSuccess);
        });
        assertThat(dad.getLastSyncedAt()).isNotNull();
    }

    @Test
    void 사이트_오류는_credentialsRejected_false로_보고된다() {
        when(loginService.login(eq("dad-id"), anyString())).thenReturn(mock(IsslSession.class));
        when(loginService.login(eq("mom-id"), anyString())).thenReturn(mock(IsslSession.class));
        // 이번 세션에서 실제로 났던 상황: 스크래핑 중 예상 못한 예외
        when(mutualScrape.fetchMutualLoanHistory(any()))
            .thenReturn(List.of())
            .thenThrow(new IllegalStateException("파싱 오류"));

        SyncResponse response = service.sync(1L, null);

        assertThat(response.synced()).isEqualTo(1);
        assertThat(response.failures()).singleElement()
            .satisfies(f -> assertThat(f.credentialsRejected()).isFalse());
    }

    @Test
    void 계정들은_병렬로_크롤링된다() throws Exception {
        // 두 계정이 동시에 로그인 단계에 들어와야만 latch가 풀린다 — 순차 실행이면 타임아웃으로 실패.
        CountDownLatch bothInside = new CountDownLatch(2);
        when(loginService.login(anyString(), anyString())).thenAnswer(inv -> {
            bothInside.countDown();
            if (!bothInside.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("순차 실행됨");
            }
            return mock(IsslSession.class);
        });

        SyncResponse response = service.sync(1L, null);

        assertThat(response.synced()).isEqualTo(2);
        assertThat(response.failures()).isEmpty();
    }

    @Test
    void 같은_사용자의_동시_요청은_한번만_크롤링하고_결과를_공유한다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loginCalls = new AtomicInteger();
        when(loginService.login(anyString(), anyString())).thenAnswer(inv -> {
            loginCalls.incrementAndGet();
            release.await(5, TimeUnit.SECONDS);
            return mock(IsslSession.class);
        });

        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<SyncResponse> first = callers.submit(() -> service.sync(1L, null));
            Thread.sleep(300); // 첫 요청이 크롤링 중일 때 두 번째 요청이 들어오게
            Future<SyncResponse> second = callers.submit(() -> service.sync(1L, null));
            Thread.sleep(300);
            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).synced()).isEqualTo(2);
            assertThat(second.get(5, TimeUnit.SECONDS).synced()).isEqualTo(2);
            assertThat(loginCalls.get()).isEqualTo(2); // 계정 2개 × 1회 (중복 요청이 새로 로그인하지 않음)
            verify(accountRepo, times(1)).findByFamilyMember_OwnerUserId(1L);
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void 이전_동기화가_끝나면_다음_요청은_새로_크롤링한다() {
        when(loginService.login(anyString(), anyString())).thenReturn(mock(IsslSession.class));

        service.sync(1L, null);
        service.sync(1L, null);

        verify(accountRepo, times(2)).findByFamilyMember_OwnerUserId(1L);
    }
}
