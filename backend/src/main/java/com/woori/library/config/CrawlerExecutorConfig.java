package com.woori.library.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * issl.go.kr 크롤링 전용 스레드 풀.
 *
 * <p>계정 하나당 Chromium 프로세스가 하나씩 뜨므로(Playwright Java는 스레드 간 공유가 안 돼 계정별로 독립 인스턴스를
 * 쓴다), 이 풀 크기가 곧 "서버 전체에서 동시에 떠 있을 수 있는 Chromium 수"의 상한이다. 사용자별이 아니라 앱 전체
 * 공용 풀로 둔 이유: 여러 사용자가 동시에 새로고침하거나 스케줄러와 겹쳐도 VM 메모리(3GB)와 issl.go.kr 동시 접속 수가
 * 이 값 이상으로 늘어나지 않게 하기 위함. 가족 구성원 상한(5명)과 맞춰 기본값 5.
 */
@Configuration
public class CrawlerExecutorConfig {

    @Bean(name = "crawlerExecutor", destroyMethod = "shutdown")
    public ExecutorService crawlerExecutor(@Value("${app.crawler.issl.max-parallel:5}") int maxParallel) {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "issl-crawler-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return Executors.newFixedThreadPool(Math.max(1, maxParallel), factory);
    }
}
