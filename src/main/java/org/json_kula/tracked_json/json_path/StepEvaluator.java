package org.json_kula.tracked_json.json_path;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.tracked_json.json_node.TrackedJsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluates a {@link Step} against a list of candidate nodes and returns the matching results.
 * Implements the Visitor pattern: each {@code visitXxx} method carries the evaluation logic
 * for the corresponding step type.
 */
final class StepEvaluator implements StepVisitor<List<TrackedJsonNode>> {

    private final List<TrackedJsonNode> inputs;
    private final JsonNode root;

    StepEvaluator(List<TrackedJsonNode> inputs, JsonNode root) {
        this.inputs = inputs;
        this.root = root;
    }

    @Override
    public List<TrackedJsonNode> visitField(FieldStep step) {
        List<TrackedJsonNode> result = new ArrayList<>();
        for (TrackedJsonNode m : inputs) {
            if (!m.node().isObject()) continue;
            JsonNode val = m.node().get(step.name());
            if (val != null)
                result.add(TrackedJsonNode.of(val, m.pointer().appendProperty(step.name())));
        }
        return result;
    }

    @Override
    public List<TrackedJsonNode> visitWildcard(WildcardStep step) {
        List<TrackedJsonNode> result = new ArrayList<>();
        for (TrackedJsonNode m : inputs) {
            if (m.node().isArray()) {
                for (int i = 0; i < m.node().size(); i++)
                    result.add(TrackedJsonNode.of(m.node().get(i), m.pointer().appendIndex(i)));
            } else if (m.node().isObject()) {
                for (Map.Entry<String, JsonNode> e : m.node().properties())
                    result.add(TrackedJsonNode.of(e.getValue(), m.pointer().appendProperty(e.getKey())));
            }
        }
        return result;
    }

    @Override
    public List<TrackedJsonNode> visitIndex(IndexStep step) {
        List<TrackedJsonNode> result = new ArrayList<>();
        for (TrackedJsonNode m : inputs) {
            if (!m.node().isArray()) continue;
            int actual = step.index() < 0 ? m.node().size() + step.index() : step.index();
            if (actual >= 0 && actual < m.node().size())
                result.add(TrackedJsonNode.of(m.node().get(actual), m.pointer().appendIndex(actual)));
        }
        return result;
    }

    @Override
    public List<TrackedJsonNode> visitSlice(SliceStep step) {
        List<TrackedJsonNode> result = new ArrayList<>();
        for (TrackedJsonNode m : inputs) {
            if (!m.node().isArray()) continue;
            int len = m.node().size();
            int s = step.stepSize();
            if (s == 0) {
                // step 0 produces empty nodelist per RFC 9535
            } else if (s > 0) {
                int from = normalizePos(step.start(), len, 0);
                int to   = normalizePos(step.end(),   len, len);
                // Use long to avoid int overflow when step is very large
                for (long i = from; i < to; i += s)
                    result.add(TrackedJsonNode.of(m.node().get((int) i), m.pointer().appendIndex((int) i)));
            } else {
                int from = normalizeNeg(step.start(), len, len - 1);
                int to   = normalizeNeg(step.end(),   len, -1);
                for (long i = from; i > to; i += s)
                    result.add(TrackedJsonNode.of(m.node().get((int) i), m.pointer().appendIndex((int) i)));
            }
        }
        return result;
    }

    @Override
    public List<TrackedJsonNode> visitFilter(FilterStep step) {
        List<TrackedJsonNode> result = new ArrayList<>();
        for (TrackedJsonNode m : inputs) {
            if (m.node().isArray()) {
                for (int i = 0; i < m.node().size(); i++) {
                    JsonNode elem = m.node().get(i);
                    if (step.predicate().test(elem, root))
                        result.add(TrackedJsonNode.of(elem, m.pointer().appendIndex(i)));
                }
            } else if (m.node().isObject()) {
                for (Map.Entry<String, JsonNode> e : m.node().properties()) {
                    if (step.predicate().test(e.getValue(), root))
                        result.add(TrackedJsonNode.of(e.getValue(), m.pointer().appendProperty(e.getKey())));
                }
            }
        }
        return result;
    }

    // Collects all descendant nodes (DFS, including self), then applies the inner step.
    @Override
    public List<TrackedJsonNode> visitRecursive(RecursiveStep step) {
        List<TrackedJsonNode> all = new ArrayList<>();
        for (TrackedJsonNode m : inputs) collectAll(m.node(), m.pointer(), all);
        return step.inner().accept(new StepEvaluator(all, root));
    }

    // Union: apply each selector to each input in turn, so results interleave per node.
    // This gives the correct ordering for e.g. $..['a','d'] (not all-'a' then all-'d').
    @Override
    public List<TrackedJsonNode> visitUnion(UnionStep step) {
        List<TrackedJsonNode> result = new ArrayList<>();
        for (TrackedJsonNode input : inputs) {
            for (Step sel : step.selectors())
                result.addAll(sel.accept(new StepEvaluator(List.of(input), root)));
        }
        return result;
    }

    static void collectAll(JsonNode node, JsonPointer ptr, List<TrackedJsonNode> acc) {
        acc.add(TrackedJsonNode.of(node, ptr));
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++)
                collectAll(node.get(i), ptr.appendIndex(i), acc);
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties())
                collectAll(e.getValue(), ptr.appendProperty(e.getKey()), acc);
        }
    }

    private static int normalizePos(Integer n, int len, int def) {
        if (n == null) return def;
        int v = n >= 0 ? Math.min(n, len) : Math.max(n + len, 0);
        return Math.max(0, Math.min(v, len));
    }

    private static int normalizeNeg(Integer n, int len, int def) {
        if (n == null) return def;
        int v = n >= 0 ? Math.min(n, len - 1) : Math.max(n + len, -1);
        return Math.max(-1, Math.min(v, len - 1));
    }
}
