public class Solution {
    public static long parse(String text, long fallback) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
