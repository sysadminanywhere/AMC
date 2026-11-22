public class Verify {
    public static void main(String[] args) throws Exception {
        Solution.Rule rule = Solution.negative();
        if (!rule.accept(-2) || rule.accept(0) || rule.accept(8)) throw new AssertionError();
    }
}
