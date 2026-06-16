package com.thesisguard.review;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Outcome of the cheap triage pass over a group of pending news items. Each verdict
 * decides whether one item could plausibly affect the long-term thesis (material) or is
 * noise. Only material items are escalated to the expensive doctrine review.
 */
public record NewsTriageResult(List<Verdict> verdicts) {

    public record Verdict(Long newsItemId, boolean material, boolean related, String reason) {}

    public boolean hasMaterial() {
        return verdicts.stream().anyMatch(Verdict::material);
    }

    public Set<Long> materialIds() {
        return verdicts.stream().filter(Verdict::material).map(Verdict::newsItemId).collect(Collectors.toSet());
    }

    /** Triage reason per news item id; later duplicates win but ids are expected unique. */
    public Map<Long, String> reasonsById() {
        return verdicts.stream().collect(Collectors.toMap(
                Verdict::newsItemId,
                v -> v.reason() == null ? "" : v.reason(),
                (first, second) -> second));
    }

    /** Whether each item is about this stock; missing ids default to true (fail-safe). */
    public Map<Long, Boolean> relatedById() {
        return verdicts.stream().collect(Collectors.toMap(
                Verdict::newsItemId,
                Verdict::related,
                (first, second) -> second));
    }

    /** Builds a result that marks every supplied item material and related; the fail-safe fallback. */
    public static NewsTriageResult allMaterial(List<Long> newsItemIds, String reason) {
        return new NewsTriageResult(newsItemIds.stream()
                .map(id -> new Verdict(id, true, true, reason))
                .toList());
    }

    public static NewsTriageResult of(List<Verdict> verdicts) {
        return new NewsTriageResult(verdicts);
    }

    /** Convenience for callers holding entities keyed by id. */
    public static <T> List<Long> ids(List<T> items, Function<T, Long> idFn) {
        return items.stream().map(idFn).toList();
    }
}
