import java.util.List;

public class Solution {
    public static List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }
}
