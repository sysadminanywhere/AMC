public class Solution {
    public static int requirePositive(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("positive value required");
        }
        return value;
    }
}
