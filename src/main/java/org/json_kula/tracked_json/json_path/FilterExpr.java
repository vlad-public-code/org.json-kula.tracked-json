package org.json_kula.tracked_json.json_path;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

enum ComparisonOp { EQ, NEQ, LT, LTE, GT, GTE }

sealed interface FilterExpr permits OrExpr, AndExpr, NotExpr, ExistenceExpr, ComparisonExpr,
        FunctionBoolExpr {
    boolean test(JsonNode element, JsonNode root);
}

// Wraps a LOGICAL-type function call (match, search) used as a boolean filter expression.
record FunctionBoolExpr(FunctionCallValue call) implements FilterExpr {
    public boolean test(JsonNode e, JsonNode root) { return call.evaluateLogical(e, root); }
}

record OrExpr(List<FilterExpr> terms) implements FilterExpr {
    public boolean test(JsonNode e, JsonNode root) {
        for (FilterExpr t : terms) if (t.test(e, root)) return true;
        return false;
    }
}

record AndExpr(List<FilterExpr> terms) implements FilterExpr {
    public boolean test(JsonNode e, JsonNode root) {
        for (FilterExpr t : terms) if (!t.test(e, root)) return false;
        return true;
    }
}

record NotExpr(FilterExpr inner) implements FilterExpr {
    public boolean test(JsonNode e, JsonNode root) { return !inner.test(e, root); }
}

// Existence: path returns at least one non-missing node.
// Null, false, 0 all count as existing. Works for both singular and non-singular paths.
record ExistenceExpr(FilterValue path) implements FilterExpr {
    public boolean test(JsonNode e, JsonNode root) { return !path.evaluateAll(e, root).isEmpty(); }
}

record ComparisonExpr(FilterValue left, ComparisonOp op, FilterValue right) implements FilterExpr {
    public boolean test(JsonNode e, JsonNode root) {
        JsonNode l = left.evaluate(e, root);
        JsonNode r = right.evaluate(e, root);
        return switch (op) {
            // RFC 9535: nothing == nothing → true; nothing == value → false
            case EQ  -> l.isMissingNode() && r.isMissingNode()  ? true
                      : l.isMissingNode() || r.isMissingNode()  ? false
                      : nodesEqual(l, r);
            // RFC 9535: nothing != nothing → false; nothing != value → true
            case NEQ -> l.isMissingNode() && r.isMissingNode()  ? false
                      : l.isMissingNode() || r.isMissingNode()  ? true
                      : !nodesEqual(l, r);
            // Ordering: defined for numbers and strings; for same-type non-ordered pairs
            // (null, bool, object, array) use equality — so null>=null, false>=false → true.
            case LT  -> !l.isMissingNode() && !r.isMissingNode() && orderedLess(l, r);
            case GT  -> !l.isMissingNode() && !r.isMissingNode() && orderedLess(r, l);
            case LTE -> !l.isMissingNode() && !r.isMissingNode() && (nodesEqual(l, r) || orderedLess(l, r));
            case GTE -> !l.isMissingNode() && !r.isMissingNode() && (nodesEqual(l, r) || orderedLess(r, l));
        };
    }

    // Structural equality; numbers compared by double value so 1.0 == 1.
    private static boolean nodesEqual(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
        return a.equals(b);
    }

    // Strict ordering: only defined for number-number and string-string pairs.
    private static boolean orderedLess(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) return Double.compare(a.doubleValue(), b.doubleValue()) < 0;
        if (a.isTextual() && b.isTextual()) return a.textValue().compareTo(b.textValue()) < 0;
        return false;
    }
}
