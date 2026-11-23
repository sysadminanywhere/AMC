package dev.amc.core;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Reversible UTF-8 tokenization with merges fitted on training sources. */
final class BytePairTokenizer {
    private static final int ALPHABET = 256;
    private static final int MAX_MERGES = 256;
    private static final int MAX_PIECE_BYTES = 128;
    private static final int TOKEN_BITS = 9;
    private static final int TOKEN_MASK = (1 << TOKEN_BITS) - 1;

    private record Merge(int left, int right) {}

    private final List<Merge> merges;
    private final List<byte[]> pieces;

    private BytePairTokenizer(List<Merge> merges) {
        this.merges = List.copyOf(merges);
        this.pieces = basePieces();
        var pairs = new HashSet<Merge>();
        for (var merge : merges) {
            if (!pairs.add(merge) || merge.left() < 0 || merge.right() < 0
                    || merge.left() >= pieces.size() || merge.right() >= pieces.size()) {
                throw new IllegalArgumentException("Invalid byte pair merge");
            }
            append(pieces, merge);
        }
    }

    static BytePairTokenizer train(List<String> sources, int maxMerges) {
        if (maxMerges < 0 || maxMerges > MAX_MERGES) throw new IllegalArgumentException("Invalid merge count");
        var sequences = new ArrayList<>(sources.stream().map(BytePairTokenizer::bytes).toList());
        var pieces = basePieces();
        var merges = new ArrayList<Merge>();
        for (int rank = 0; rank < maxMerges; rank++) {
            var counts = new HashMap<Integer, Integer>();
            for (int[] sequence : sequences) {
                for (int i = 1; i < sequence.length; i++) {
                    int left = sequence[i - 1], right = sequence[i];
                    if (pieces.get(left).length + pieces.get(right).length <= MAX_PIECE_BYTES) {
                        counts.merge((left << TOKEN_BITS) | right, 1, Integer::sum);
                    }
                }
            }
            int bestPair = Integer.MAX_VALUE;
            int bestCount = 1;
            for (var entry : counts.entrySet()) {
                int count = entry.getValue();
                if (count > bestCount || (count > 1 && count == bestCount && entry.getKey() < bestPair)) {
                    bestCount = count;
                    bestPair = entry.getKey();
                }
            }
            if (bestCount < 2) break;
            var merge = new Merge(bestPair >>> TOKEN_BITS, bestPair & TOKEN_MASK);
            int id = pieces.size();
            for (int i = 0; i < sequences.size(); i++) {
                sequences.set(i, apply(sequences.get(i), merge, id));
            }
            append(pieces, merge);
            merges.add(merge);
        }
        return new BytePairTokenizer(merges);
    }

    int size() {
        return pieces.size();
    }

    int[] encode(String source) {
        int[] result = bytes(source);
        for (int i = 0; i < merges.size(); i++) result = apply(result, merges.get(i), ALPHABET + i);
        return result;
    }

    String decode(int[] ids) {
        var bytes = new ByteArrayOutputStream();
        for (int id : ids) bytes.writeBytes(pieces.get(id));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static List<byte[]> basePieces() {
        var pieces = new ArrayList<byte[]>();
        for (int i = 0; i < ALPHABET; i++) pieces.add(new byte[]{(byte) i});
        return pieces;
    }

    private static void append(List<byte[]> pieces, Merge merge) {
        byte[] left = pieces.get(merge.left()), right = pieces.get(merge.right());
        if (left.length + right.length > MAX_PIECE_BYTES) throw new IllegalArgumentException("Oversize byte pair");
        byte[] joined = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, joined, left.length, right.length);
        pieces.add(joined);
    }

    private static int[] bytes(String source) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        int[] sequence = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) sequence[i] = Byte.toUnsignedInt(bytes[i]);
        return sequence;
    }

    private static int[] apply(int[] sequence, Merge merge, int id) {
        int write = 0;
        for (int read = 0; read < sequence.length; read++) {
            if (read + 1 < sequence.length && sequence[read] == merge.left() && sequence[read + 1] == merge.right()) {
                sequence[write++] = id;
                read++;
            } else {
                sequence[write++] = sequence[read];
            }
        }
        return write == sequence.length ? sequence : Arrays.copyOf(sequence, write);
    }

    void write(DataOutputStream out) throws IOException {
        out.writeInt(merges.size());
        for (var merge : merges) {
            out.writeShort(merge.left());
            out.writeShort(merge.right());
        }
    }

    static BytePairTokenizer read(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_MERGES) throw new IOException("Invalid byte pair merge count");
        var merges = new ArrayList<Merge>(count);
        for (int i = 0; i < count; i++) merges.add(new Merge(in.readUnsignedShort(), in.readUnsignedShort()));
        return new BytePairTokenizer(merges);
    }
}
