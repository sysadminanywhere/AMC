public class Verify {
    public static void main(String[] args) throws Exception {
        if (!Solution.last(java.util.List.of("a", "b")).equals("b")) throw new AssertionError();
        if (Solution.last(java.util.List.of(7, 9)) != 9) throw new AssertionError();
    }
}
