import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Solution {
    public static long countLines(Path path) throws IOException {
        try (var lines = Files.lines(path)) {
            return lines.count();
        }
    }
}
