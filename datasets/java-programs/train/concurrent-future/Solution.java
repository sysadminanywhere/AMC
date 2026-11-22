import java.util.concurrent.CompletableFuture;

public class Solution {
    public static CompletableFuture<Integer> doubled(int value) {
        return CompletableFuture.completedFuture(value * 2);
    }
}
