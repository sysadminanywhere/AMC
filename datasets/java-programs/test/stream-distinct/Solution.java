import java.util.List;

public class Solution {
    public static List<String> distinct(List<String> values) {
        return values.stream().distinct().toList();
    }
}
