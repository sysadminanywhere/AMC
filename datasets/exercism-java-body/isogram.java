class IsogramChecker {
    boolean isIsogram(String phrase) {
        var seen = new java.util.HashSet<Integer>();
        return phrase.toLowerCase(java.util.Locale.ROOT).codePoints()
                .filter(character -> character != ' ' && character != '-')
                .allMatch(seen::add);
    }
}
