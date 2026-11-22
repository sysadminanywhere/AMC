public class Verify {
    public static void main(String[] args) throws Exception {
        var wallet = new Solution(5);
        if (wallet.balance() != 5) throw new AssertionError();
        wallet.deposit(9);
        wallet.deposit(-2);
        if (wallet.balance() != 12) throw new AssertionError();
    }
}
