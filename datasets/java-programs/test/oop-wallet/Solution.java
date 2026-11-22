public class Solution {
    private int amount;

    public Solution(int start) {
        amount = start;
    }

    public void deposit(int value) {
        amount += value;
    }

    public int balance() {
        return amount;
    }
}
