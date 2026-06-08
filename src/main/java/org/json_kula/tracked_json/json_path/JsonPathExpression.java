package org.json_kula.tracked_json.json_path;

import java.util.List;

/**
 * A compiled, immutable representation of a JSONPath expression.
 * <p>
 * Instances are obtained via {@link #compile(String)} and may be cached and
 * shared across threads without additional synchronization — the internal step
 * list is unmodifiable after construction and every evaluation creates its own
 * working state.
 */
public final class JsonPathExpression {

    final List<Step> steps;
    private final String expression;

    private JsonPathExpression(List<Step> steps, String expression) {
        this.steps = steps;
        this.expression = expression;
    }

    /**
     * Parses {@code jsonPath} and returns a compiled expression ready for repeated evaluation.
     *
     * @throws InvalidPathException if {@code jsonPath} is syntactically invalid
     */
    public static JsonPathExpression compile(String jsonPath) {
        return new JsonPathExpression(List.copyOf(JsonPathParser.parse(jsonPath)), jsonPath);
    }

    @Override
    public String toString() {
        return expression;
    }
}
