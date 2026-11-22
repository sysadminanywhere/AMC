public class Solution {
    public interface Rule {
        boolean accept(int value);
    }

    public static Rule negative() {
        return value -> value < 0;
    }
}
