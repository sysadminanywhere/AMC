public class Solution {
    private int count;

    public Solution(int start) {
        count = start;
    }

    public void increment() {
        count++;
    }

    public int value() {
        return count;
    }
}
