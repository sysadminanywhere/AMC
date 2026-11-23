package dev.amc.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BytePairTokenizerTest {
    @Test
    void mergesRepeatedSourceAndRoundTripsUnseenUtf8() {
        String java = "    return \"a  b\";\r\n";
        var codec = BytePairTokenizer.train(List.of(java, java, java), 24);
        assertTrue(codec.encode(java).length < java.getBytes(StandardCharsets.UTF_8).length);
        var unseen = "// Новый 😀\r\n" + java + "\t// end";
        assertEquals(unseen, codec.decode(codec.encode(unseen)));
    }

    @Test
    void tieBreakingDoesNotDependOnCorpusOrder() {
        var first = BytePairTokenizer.train(List.of("abab", "cdcd"), 4);
        var second = BytePairTokenizer.train(List.of("cdcd", "abab"), 4);
        assertArrayEquals(first.encode("abcd"), second.encode("abcd"));
    }
}
