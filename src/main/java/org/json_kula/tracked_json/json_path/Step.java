package org.json_kula.tracked_json.json_path;

import java.util.List;

sealed interface Step permits FieldStep, WildcardStep, IndexStep, SliceStep,
        FilterStep, RecursiveStep, UnionStep {
    <R> R accept(StepVisitor<R> visitor);
}

record FieldStep(String name) implements Step {
    public <R> R accept(StepVisitor<R> visitor) { return visitor.visitField(this); }
}

record WildcardStep() implements Step {
    public <R> R accept(StepVisitor<R> visitor) { return visitor.visitWildcard(this); }
}

record IndexStep(int index) implements Step {
    public <R> R accept(StepVisitor<R> visitor) { return visitor.visitIndex(this); }
}

// RFC 9535 slice: [start:end:step]
record SliceStep(Integer start, Integer end, int stepSize) implements Step {
    public <R> R accept(StepVisitor<R> visitor) { return visitor.visitSlice(this); }
}

// Applies predicate to each child of the input node (array elements or object values).
record FilterStep(FilterExpr predicate) implements Step {
    public <R> R accept(StepVisitor<R> visitor) { return visitor.visitFilter(this); }
}

// Collects all descendant nodes (DFS, including self), then applies inner step.
record RecursiveStep(Step inner) implements Step {
    public <R> R accept(StepVisitor<R> visitor) { return visitor.visitRecursive(this); }
}

// Union: apply each selector independently, interleaving results per input node.
record UnionStep(List<Step> selectors) implements Step {
    public <R> R accept(StepVisitor<R> visitor) { return visitor.visitUnion(this); }
}
