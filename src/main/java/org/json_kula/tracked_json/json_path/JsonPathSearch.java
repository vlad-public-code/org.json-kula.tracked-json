package org.json_kula.tracked_json.json_path;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.tracked_json.json_node.TrackedJsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates JSONPath expressions (RFC 9535) against a {@link TrackedJsonNode},
 * returning matches with their absolute {@link JsonPointer}s preserved.
 */
public final class JsonPathSearch {

    private JsonPathSearch() {}

    /**
     * Evaluates a pre-compiled {@code jsonPath} expression relative to {@code origin}
     * and returns all matching nodes, each wrapped with its absolute {@link JsonPointer}.
     * <p>
     * The expression is thread-safe and may be shared; each call creates its own
     * evaluation state. Returns an empty list when nothing matches.
     */
    public static List<TrackedJsonNode> find(TrackedJsonNode origin, JsonPathExpression jsonPath) {
        JsonNode root = origin.node();
        List<TrackedJsonNode> cursor = new ArrayList<>();
        cursor.add(origin);
        for (Step step : jsonPath.steps) {
            cursor = step.accept(new StepEvaluator(cursor, root));
            if (cursor.isEmpty()) return List.of();
        }
        return cursor.stream().filter(n -> !n.isMissingNode()).toList();
    }

    /**
     * Parses {@code jsonPath} and evaluates it relative to {@code origin}.
     * Prefer {@link #find(TrackedJsonNode, JsonPathExpression)} with a cached
     * {@link JsonPathExpression} when the same expression is used repeatedly.
     *
     * @throws InvalidPathException for syntactically invalid expressions
     */
    public static List<TrackedJsonNode> find(TrackedJsonNode origin, String jsonPath) {
        return find(origin, JsonPathExpression.compile(jsonPath));
    }
}
