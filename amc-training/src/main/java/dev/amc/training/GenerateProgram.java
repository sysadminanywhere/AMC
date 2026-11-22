package dev.amc.training;

import dev.amc.core.JavaLanguageModel;

import java.io.IOException;
import java.nio.file.Path;

public final class GenerateProgram {
    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Usage: GenerateProgram prompt-file [weights]");
        }
        var prompt = ProgramCorpus.readPrompt(Path.of(args[0]));
        var model = JavaLanguageModel.load(args.length == 2 ? Path.of(args[1]) : TrainPrograms.DEFAULT_CHECKPOINT);
        if (!model.sourceMode()) throw new IllegalArgumentException("A source-mode checkpoint is required");
        var generation = model.generate(prompt, ProgramCorpus.MAX_TOKENS);
        if (!generation.complete()) throw new IllegalStateException("Generation reached its token limit");
        System.out.print(generation.body());
    }
}
