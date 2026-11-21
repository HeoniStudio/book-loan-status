package com.woori.library.service.crawler;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 서버 기동 직후 Playwright 드라이버 준비 + Chromium 첫 실행을 미리 한 번 해둔다.
 *
 * <p>Playwright Java는 JVM에서 처음 {@code Playwright.create()}를 호출할 때 드라이버를 임시 폴더에 풀어놓는데,
 * 이게 첫 사용자 요청 시점에 일어나면 그 요청만 브라우저 기동에 85초가 걸렸다(2026-11-22 로컬 실측, 이후 호출은
 * 0.7초). 기동 직후 백그라운드에서 이 비용을 미리 치러서 사용자가 기다리지 않게 한다. 실패해도 실제 요청 때 다시
 * 시도되므로 경고 로그만 남긴다.
 */
@Component
public class PlaywrightWarmup {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightWarmup.class);

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        Thread thread = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try (Playwright playwright = Playwright.create()) {
                playwright.chromium()
                    .launch(new BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")))
                    .close();
                log.info("[playwright-warmup] 완료 ({}ms)", System.currentTimeMillis() - t0);
            } catch (RuntimeException e) {
                log.warn("[playwright-warmup] 실패 ({}ms): {}", System.currentTimeMillis() - t0, e.getMessage());
            }
        }, "playwright-warmup");
        thread.setDaemon(true);
        thread.start();
    }
}
