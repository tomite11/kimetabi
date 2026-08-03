package app.tabikime.kimetabi.ingestion.url;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

final class HostConcurrencyLimiter {

    private final int permitsPerHost;
    private final Map<String, Entry> entries = new HashMap<>();

    HostConcurrencyLimiter(int permitsPerHost) {
        this.permitsPerHost = permitsPerHost;
    }

    Lease acquire(String hostname, Duration timeout)
            throws InterruptedException, UrlFetchException {
        Entry entry;
        synchronized (entries) {
            entry = entries.computeIfAbsent(
                    hostname, ignored -> new Entry(new Semaphore(permitsPerHost, true)));
            entry.references++;
        }
        boolean acquired = false;
        try {
            acquired = entry.semaphore.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (!acquired) {
                throw new UrlFetchException(
                        UrlFetchException.Reason.TIMEOUT,
                        "Timed out waiting for metadata host capacity");
            }
            return () -> release(hostname, entry);
        } finally {
            if (!acquired) {
                removeReference(hostname, entry);
            }
        }
    }

    private void release(String hostname, Entry entry) {
        entry.semaphore.release();
        removeReference(hostname, entry);
    }

    private void removeReference(String hostname, Entry entry) {
        synchronized (entries) {
            entry.references--;
            if (entry.references == 0) {
                entries.remove(hostname, entry);
            }
        }
    }

    @FunctionalInterface
    interface Lease extends AutoCloseable {
        @Override
        void close();
    }

    private static final class Entry {
        private final Semaphore semaphore;
        private int references;

        private Entry(Semaphore semaphore) {
            this.semaphore = semaphore;
        }
    }
}
