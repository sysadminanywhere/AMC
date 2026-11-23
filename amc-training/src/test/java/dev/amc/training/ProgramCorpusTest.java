package dev.amc.training;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProgramCorpusTest {
    @Test
    void loadsSupportSourcesWithoutAllowingThemToReplaceTheCandidate(@TempDir Path root) throws IOException {
        Path directory = root.resolve("train/task");
        write(directory, "Training prompt", "public class Solution {}");
        Path support = Files.createDirectory(directory.resolve("support"));
        Files.writeString(support.resolve("Helper.java"), "class Helper {}");
        assertEquals("class Helper {}", ProgramCorpus.trainingReferences(root).getFirst().support().get("Helper.java"));
        Files.writeString(support.resolve("Solution.java"), "class Replacement {}");
        assertThrows(IOException.class, () -> ProgramCorpus.trainingReferences(root));
    }

    @Test
    void referenceChecksCanBeLargerThanModelInputFiles(@TempDir Path root) throws IOException {
        Path directory = root.resolve("train/large-test");
        write(directory, "Training prompt", "public class Solution {}");
        String verification = "public class Verify { /*" + "x".repeat(36_000) + "*/ }";
        Files.writeString(directory.resolve("Verify.java"), verification);
        assertEquals(verification, ProgramCorpus.trainingReferences(root).getFirst().verification());
    }

    @Test
    void trainingLoadsOnlyTrainingAndEvaluationRejectsDuplicateSolutions(@TempDir Path root) throws IOException {
        write(root.resolve("train/alpha"), "Training prompt", "public class Solution {}");
        assertEquals("alpha", ProgramCorpus.training(root).getFirst().id());
        write(root.resolve("test/beta"), "Evaluation prompt", "public class Solution {\n}");
        assertThrows(IOException.class, () -> ProgramCorpus.evaluation(root));
        write(root.resolve("test/beta"), "Evaluation prompt", "public class Solution { int value; }");
        assertEquals("beta", ProgramCorpus.evaluation(root).getFirst().id());
    }

    @Test
    void validationStaysOutOfTestAndJUnitNamesAreBasenames(@TempDir Path root) throws IOException {
        write(root.resolve("train/alpha"), "Training prompt", "public class Solution {}");
        Path validation = root.resolve("validation/beta");
        write(validation, "Validation prompt", "public class Solution { int value; }");
        Files.writeString(validation.resolve("junit.txt"), "Original.java\nOriginalTest.java\n");
        var task = ProgramCorpus.evaluation(root, "validation").getFirst();
        assertEquals("Original.java", task.junit().sourceName());
        assertEquals("OriginalTest", task.junit().testClass());
        write(root.resolve("test/gamma"), "Test prompt", "public class Solution { int value; }");
        assertThrows(IOException.class, () -> ProgramCorpus.evaluation(root));
        Files.writeString(validation.resolve("junit.txt"), "../Original.java\nOriginalTest.java\n");
        assertThrows(IOException.class, () -> ProgramCorpus.evaluation(root, "validation"));
    }

    private static void write(Path directory, String prompt, String source) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("prompt.txt"), prompt);
        Files.writeString(directory.resolve("Solution.java"), source);
        Files.writeString(directory.resolve("Verify.java"), "public class Verify {}");
    }
}
