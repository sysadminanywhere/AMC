public class Verify {
    public static void main(String[] args) throws Exception {
        if (Solution.parse("3000000000", -1) != 3000000000L) throw new AssertionError();
        if (Solution.parse("invalid", 8) != 8) throw new AssertionError();
    }
}
