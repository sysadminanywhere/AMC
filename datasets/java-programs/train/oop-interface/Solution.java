public class Solution {
    public interface Rule {
        boolean accept(int value);
    }

    public static Rule positive() {
        return value -> value > 0;
    }
}
