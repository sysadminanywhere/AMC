public class Verify {
    public static void main(String[] args) throws Exception {
        var path = java.nio.file.Files.createTempFile("amc-test", ".txt");
        java.nio.file.Files.writeString(path, "a\n\nb\n");
        if (Solution.countLines(path) != 3) throw new AssertionError();
    }
}
