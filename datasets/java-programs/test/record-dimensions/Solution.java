public class Solution {
    public record Size(int width, int height) {}

    public static Size square(int side) {
        return new Size(side, side);
    }
}
