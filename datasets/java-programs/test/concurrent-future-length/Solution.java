import java.util.concurrent.CompletableFuture;

public class Solution {
    public static CompletableFuture<Integer> length(String text) {
        return CompletableFuture.completedFuture(text.length());
    }
}
