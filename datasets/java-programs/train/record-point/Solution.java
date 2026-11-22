public class Solution {
    public record Point(int x, int y) {}

    public static Point origin() {
        return new Point(0, 0);
    }
}
