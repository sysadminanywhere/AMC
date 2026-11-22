import java.util.List;

public class Solution {
    public static List<Integer> positive(List<Integer> values) {
        return values.stream().filter(value -> value > 0).toList();
    }
}
