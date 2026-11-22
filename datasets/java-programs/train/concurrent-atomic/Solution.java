import java.util.concurrent.atomic.AtomicInteger;

public class Solution {
    private final AtomicInteger count = new AtomicInteger();

    public int next() {
        return count.incrementAndGet();
    }
}
