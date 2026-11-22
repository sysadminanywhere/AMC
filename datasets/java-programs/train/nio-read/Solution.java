import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Solution {
    public static String read(Path path) throws IOException {
        return Files.readString(path);
    }
}
