import java.util.Map;

class WordCount {
    public Map<String, Integer> phrase(String input) {
        var words = new java.util.HashMap<String, Integer>();
        var tokens = java.util.regex.Pattern.compile("[a-z0-9]+(?:'[a-z0-9]+)*")
                .matcher(input.toLowerCase(java.util.Locale.ROOT));
        while (tokens.find()) words.merge(tokens.group(), 1, Integer::sum);
        return words;
    }
}
