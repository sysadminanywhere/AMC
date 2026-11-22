public class Verify {
    public static void main(String[] args) throws Exception {
        if (Solution.length("abc").get() != 3) throw new AssertionError();
        if (Solution.length("").get() != 0) throw new AssertionError();
    }
}
