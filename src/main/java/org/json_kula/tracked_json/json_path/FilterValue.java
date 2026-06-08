package org.json_kula.tracked_json.json_path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.json_kula.tracked_json.json_node.TrackedJsonNode;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

// VALUE: can be used in comparisons; LOGICAL: used as boolean (match/search).
enum FunctionResultType { VALUE, LOGICAL }

sealed interface FilterValue permits SingularFilterPath, LiteralValue, NonSingularFilterPath,
        FunctionCallValue {
    // Evaluate to a single node (required for comparisons; throws for non-singular paths).
    JsonNode evaluate(JsonNode element, JsonNode root);

    // Evaluate to a list of nodes (used for existence/count checks).
    default List<JsonNode> evaluateAll(JsonNode element, JsonNode root) {
        JsonNode v = evaluate(element, root);
        return v.isMissingNode() ? List.of() : List.of(v);
    }
}

// RFC 9535 §2.4 built-in function extensions.
record FunctionCallValue(String name, List<FilterValue> args,
                         FunctionResultType resultType) implements FilterValue {
    public JsonNode evaluate(JsonNode element, JsonNode root) {
        return switch (name) {
            case "length" -> {
                JsonNode val = args.get(0).evaluate(element, root);
                if (val.isMissingNode()) yield MissingNode.getInstance();
                if (val.isTextual()) {
                    String s = val.textValue();
                    yield JsonNodeFactory.instance.numberNode(s.codePointCount(0, s.length()));
                }
                if (val.isArray() || val.isObject()) yield JsonNodeFactory.instance.numberNode(val.size());
                yield MissingNode.getInstance();
            }
            case "count" -> JsonNodeFactory.instance.numberNode(args.get(0).evaluateAll(element, root).size());
            case "value" -> {
                List<JsonNode> nodes = args.get(0).evaluateAll(element, root);
                yield nodes.size() == 1 ? nodes.get(0) : MissingNode.getInstance();
            }
            default -> MissingNode.getInstance(); // match/search handled via evaluateLogical
        };
    }

    // Called for LOGICAL-type functions (match, search).
    public boolean evaluateLogical(JsonNode element, JsonNode root) {
        JsonNode str = args.get(0).evaluate(element, root);
        JsonNode pat = args.get(1).evaluate(element, root);
        if (!str.isTextual() || !pat.isTextual()) return false;
        try {
            Pattern p = Pattern.compile(iregexpToJavaPattern(pat.textValue()),
                    Pattern.UNICODE_CHARACTER_CLASS);
            return switch (name) {
                case "match"  -> p.matcher(str.textValue()).matches();
                case "search" -> p.matcher(str.textValue()).find();
                default       -> false;
            };
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    // Converts an I-Regexp (RFC 9485) pattern to a Java regex pattern.
    // The key difference: I-Regexp '.' matches any char except CR/LF (U+000D/U+000A),
    // including U+2028/U+2029 — whereas Java's '.' also excludes those two.
    private static String iregexpToJavaPattern(String irePattern) {
        StringBuilder sb = new StringBuilder();
        boolean inClass = false;
        int i = 0;
        while (i < irePattern.length()) {
            char c = irePattern.charAt(i);
            if (c == '\\' && i + 1 < irePattern.length()) {
                sb.append(c);
                sb.append(irePattern.charAt(i + 1));
                i += 2;
            } else if (c == '[' && !inClass) {
                inClass = true;
                sb.append(c);
                i++;
            } else if (c == ']' && inClass) {
                inClass = false;
                sb.append(c);
                i++;
            } else if (c == '.' && !inClass) {
                sb.append("[^\\r\\n]");
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}

// Navigates a singular relative path starting from @ or absolute from $.
// Returns MissingNode when any step fails (distinguishes absent from JSON null).
record SingularFilterPath(boolean absolute, List<Object> steps) implements FilterValue {
    // String = field name, Integer = array index
    public JsonNode evaluate(JsonNode element, JsonNode root) {
        JsonNode cur = absolute ? root : element;
        for (Object step : steps) {
            if (cur == null || cur.isMissingNode()) return MissingNode.getInstance();
            if (step instanceof String name) {
                if (!cur.isObject()) return MissingNode.getInstance();
                cur = cur.get(name);
            } else {
                int idx = (Integer) step;
                if (!cur.isArray()) return MissingNode.getInstance();
                int actual = idx < 0 ? cur.size() + idx : idx;
                cur = (actual >= 0 && actual < cur.size()) ? cur.get(actual) : null;
            }
        }
        return cur != null ? cur : MissingNode.getInstance();
    }
}

// Non-singular filter path (wildcard, union, slice, nested filter).
// Valid only in existence checks, not in comparisons.
record NonSingularFilterPath(boolean absolute, List<Step> steps) implements FilterValue {
    public JsonNode evaluate(JsonNode element, JsonNode root) {
        throw new InvalidPathException("Non-singular query cannot be used in comparison");
    }

    @Override
    public List<JsonNode> evaluateAll(JsonNode element, JsonNode root) {
        JsonNode start = absolute ? root : element;
        List<TrackedJsonNode> cursor = List.of(TrackedJsonNode.ofRoot(start));
        for (Step s : steps) cursor = s.accept(new StepEvaluator(cursor, root));
        return cursor.stream().filter(n -> !n.isMissingNode()).map(TrackedJsonNode::node).toList();
    }
}

record LiteralValue(JsonNode value) implements FilterValue {
    public JsonNode evaluate(JsonNode element, JsonNode root) { return value; }
}
