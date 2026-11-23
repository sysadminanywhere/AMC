public class PangramChecker {
    public boolean isPangram(String input) {
        int letters = 0;
        for (int i = 0; i < input.length(); i++) {
            char letter = Character.toLowerCase(input.charAt(i));
            if (letter >= 'a' && letter <= 'z') {
                letters |= 1 << (letter - 'a');
            }
        }
        return letters == (1 << 26) - 1;
    }
}
