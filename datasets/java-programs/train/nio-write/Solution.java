import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Solution {
    public static void write(Path path, String text) throws IOException {
        Files.writeString(path, text);
    }
}
