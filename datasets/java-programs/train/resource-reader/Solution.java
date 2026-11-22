import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Solution {
    public static String firstLine(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            return reader.readLine();
        }
    }
}
