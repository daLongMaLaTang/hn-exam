package com.huaneng.shanyin.hnexam.service;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

/** Only adjacent blank markers separated by list punctuation may exchange answers. */
public final class ConsecutiveBlankOrder {
    private static final Pattern MARKER = Pattern.compile("[（(][\\s_＿]*[）)]|[_＿]{2,}");
    private static final Pattern JOIN = Pattern.compile("[\\s、,，;；]*(?:和|与|及)?[\\s、,，;；]*");
    private ConsecutiveBlankOrder() {}
    public record Block(int start, int end) {}

    public static List<Block> blocks(String stem, int count) {
        var matcher = MARKER.matcher(Objects.toString(stem, ""));
        var starts = new ArrayList<Integer>(); var ends = new ArrayList<Integer>();
        while (matcher.find()) { starts.add(matcher.start()); ends.add(matcher.end()); }
        // Ambiguous/missing markers must never silently make unrelated blanks interchangeable.
        if (starts.size() != count) return List.of();
        var result = new ArrayList<Block>(); int start = 0;
        for (int i = 1; i <= count; i++) {
            if (i == count || !JOIN.matcher(stem.substring(ends.get(i - 1), starts.get(i))).matches()) {
                if (i - start > 1) result.add(new Block(start, i));
                start = i;
            }
        }
        return result;
    }

    public static String slotLabel(String stem, int count, int slot) {
        return blocks(stem, count).stream().filter(b -> slot > b.start() && slot <= b.end())
                .map(b -> "第 " + (b.start() + 1) + "–" + b.end() + " 空（顺序不限）")
                .findFirst().orElse("第 " + slot + " 空");
    }

    /** Stable maximum matching preserves duplicates, alternatives, empty inputs and original answer text. */
    public static List<String> align(String stem, int count, List<String> submitted, BiPredicate<Integer, String> accepts) {
        var aligned = new ArrayList<String>();
        for (int i = 0; i < count; i++) aligned.add(i < submitted.size() ? submitted.get(i) : "");
        for (var block : blocks(stem, count)) {
            var values = new ArrayList<>(aligned.subList(block.start(), block.end()));
            values.sort(Comparator.naturalOrder());
            int[] owners = new int[values.size()]; Arrays.fill(owners, -1);
            for (int slot = block.start(); slot < block.end(); slot++)
                match(slot, values, owners, new boolean[values.size()], accepts);
            var remaining = new ArrayList<String>(); var assigned = new HashSet<Integer>();
            for (int i = 0; i < owners.length; i++) {
                if (owners[i] < 0) remaining.add(values.get(i));
                else { aligned.set(owners[i], values.get(i)); assigned.add(owners[i]); }
            }
            int next = 0;
            for (int slot = block.start(); slot < block.end(); slot++)
                if (!assigned.contains(slot)) aligned.set(slot, remaining.get(next++));
        }
        return aligned;
    }

    private static boolean match(int slot, List<String> values, int[] owners, boolean[] seen,
                                 BiPredicate<Integer, String> accepts) {
        for (int i = 0; i < values.size(); i++) {
            if (seen[i] || values.get(i).isEmpty() || !accepts.test(slot, values.get(i))) continue;
            seen[i] = true;
            if (owners[i] < 0 || match(owners[i], values, owners, seen, accepts)) {
                owners[i] = slot; return true;
            }
        }
        return false;
    }
}
