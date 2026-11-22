package dev.amc.core;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** Synthetic key-value retention task: SET(key,value) operations followed by GET(key). */
public record Task(List<Assignment> assignments, int queryKey, int targetValue) {
    public record Assignment(int key, int value) {}
    public Task { assignments = List.copyOf(assignments); }
    public static Task random(SplittableRandom rng, int keys, int values, int writes) {
        if (keys < 2 || values < 2 || writes < 2) throw new IllegalArgumentException();
        var ops = new ArrayList<Assignment>();
        int[] lastValues = new int[keys];
        boolean[] present = new boolean[keys];
        // Guarantee >=2 distinct keys and ask for one that is not necessarily the final write.
        int first = rng.nextInt(keys);
        int second = (first + 1 + rng.nextInt(keys-1)) % keys;
        for (int i=0;i<writes;i++) {
            int key = i==0 ? first : i==1 ? second : rng.nextInt(keys);
            int value = rng.nextInt(values);
            ops.add(new Assignment(key,value));
            present[key] = true;
            lastValues[key] = value;
        }
        var candidates = new ArrayList<Integer>();
        for (int i=0;i<keys;i++) if (present[i]) candidates.add(i);
        int query = candidates.get(rng.nextInt(candidates.size()));
        return new Task(ops, query, lastValues[query]);
    }
}
