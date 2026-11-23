package dev.amc.training;

import dev.amc.core.JavaLanguageModel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

public final class GenerateProgram {
    public static void main(String[] args) throws IOException {
        var flags = Arrays.stream(args).takeWhile(arg -> arg.startsWith("--")).toList();
        boolean stopOnRepeat = flags.contains("--stop-on-repeat");
        boolean scaffoldMode = flags.contains("--scaffold");
        boolean bodyMode = flags.contains("--body");
        if ((scaffoldMode && bodyMode) || flags.stream().anyMatch(flag -> !flag.equals("--scaffold")
                && !flag.equals("--body") && !flag.equals("--stop-on-repeat"))) {
            throw new IllegalArgumentException("Unknown generation option");
        }
        args = Arrays.copyOfRange(args, flags.size(), args.length);
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Usage: GenerateProgram [--scaffold | --body] [--stop-on-repeat] prompt-file [weights]");
        }
        Path promptFile = Path.of(args[0]);
        String prompt = ProgramCorpus.readPrompt(promptFile);
        var scaffold = scaffoldMode || bodyMode
                ? new JavaScaffold(ProgramCorpus.readPrompt(promptFile.resolveSibling("Starter.java"))) : null;
        Path defaultCheckpoint = bodyMode ? TrainPrograms.DEFAULT_BODY_CHECKPOINT
                : scaffoldMode ? TrainPrograms.DEFAULT_SCAFFOLD_CHECKPOINT : TrainPrograms.DEFAULT_CHECKPOINT;
        var model = JavaLanguageModel.load(args.length == 2 ? Path.of(args[1]) : defaultCheckpoint);
        if (!model.sourceMode()) throw new IllegalArgumentException("A source-mode checkpoint is required");
        var decoded = model.decode(bodyMode ? scaffold.bodyPrompt(prompt) : scaffoldMode ? scaffold.prompt(prompt) : prompt,
                ProgramCorpus.MAX_TOKENS, stopOnRepeat);
        if (!decoded.generation().complete()) {
            throw new IllegalStateException("Generation stopped: " + decoded.stop() + " after " + decoded.tokens() + " tokens");
        }
        String generated = decoded.generation().body();
        System.out.print(bodyMode ? scaffold.applyBody(generated) : scaffoldMode ? scaffold.apply(generated) : generated);
    }
}
