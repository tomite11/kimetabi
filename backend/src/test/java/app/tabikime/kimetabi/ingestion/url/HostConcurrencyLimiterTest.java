package app.tabikime.kimetabi.ingestion.url;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class HostConcurrencyLimiterTest {

    @Test
    void allowsAtMostTwoConcurrentLeasesForTheSameHost() throws Exception {
        HostConcurrencyLimiter limiter = new HostConcurrencyLimiter(2);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < 3; index++) {
                executor.submit(() -> {
                    try (var ignored = limiter.acquire(
                            "public.example", Duration.ofSeconds(2))) {
                        int current = active.incrementAndGet();
                        maximum.accumulateAndGet(current, Math::max);
                        entered.countDown();
                        release.await();
                        active.decrementAndGet();
                    }
                    return null;
                });
            }

            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50);
            assertThat(maximum).hasValue(2);
            release.countDown();
        }
    }
}
