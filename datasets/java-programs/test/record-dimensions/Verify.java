public class Verify {
    public static void main(String[] args) throws Exception {
        var size = Solution.square(7);
        if (size.width() != 7 || size.height() != 7) throw new AssertionError();
        if (!size.equals(new Solution.Size(7, 7))) throw new AssertionError();
    }
}
