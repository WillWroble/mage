package mage.util.logging;


import org.apache.log4j.Level;
import org.apache.log4j.spi.Filter;
import org.apache.log4j.spi.LoggingEvent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Log4j 1.x rate-limiting filter.
 *
 * Configuration properties (from log4j.properties):
 *  - WindowMillis (int): time window in milliseconds (default 1000)
 *  - MaxEvents   (int): max allowed events per window (default 100)
 *  - ExcludeErrors (boolean): never drop ERROR+ (default true)
 *  - ExcludeWarn   (boolean): never drop WARN+ (default false)
 *
 * The filter is per-appender: attach it to each appender you want to throttle.
 */
public class RateLimitingFilter extends Filter {

    private volatile int windowMillis = 1000;  // default 1s
    private volatile int maxEvents = 100;      // default 100 msgs/window
    private volatile boolean excludeErrors = false;
    private volatile boolean excludeWarn = false;

    private volatile long windowStart = System.currentTimeMillis();
    private final AtomicInteger count = new AtomicInteger();

    // --- Setters for log4j.properties (JavaBean-style) ---
    public void setWindowMillis(int windowMillis) {
        if (windowMillis > 0) this.windowMillis = windowMillis;
    }

    public void setMaxEvents(int maxEvents) {
        if (maxEvents >= 0) this.maxEvents = maxEvents;
    }

    public void setExcludeErrors(boolean excludeErrors) {
        this.excludeErrors = excludeErrors;
    }

    public void setExcludeWarn(boolean excludeWarn) {
        this.excludeWarn = excludeWarn;
    }

    @Override
    public int decide(LoggingEvent event) {
        if (event == null) return NEUTRAL;

        // only applies to warn right now
        Level level = event.getLevel();
        if(!level.equals(Level.WARN)) return NEUTRAL;

        final long now = System.currentTimeMillis();
        long start = windowStart;
        if (now - start >= windowMillis) {
            synchronized (this) {
                if (windowStart == start) {
                    windowStart = now;
                    count.set(0);
                }
            }
        }

        int c = count.incrementAndGet();
        return (c <= maxEvents) ? NEUTRAL : DENY;
    }
}