public class Verify {
    public static void main(String[] args) throws Exception {
        var values = java.util.List.of("z", "a", "z", "b", "a");
        if (!Solution.distinct(values).equals(java.util.List.of("z", "a", "b"))) throw new AssertionError();
    }
}
