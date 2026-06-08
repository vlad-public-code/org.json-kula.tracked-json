package org.json_kula.tracked_json.json_path;

interface StepVisitor<R> {
    R visitField(FieldStep step);
    R visitWildcard(WildcardStep step);
    R visitIndex(IndexStep step);
    R visitSlice(SliceStep step);
    R visitFilter(FilterStep step);
    R visitRecursive(RecursiveStep step);
    R visitUnion(UnionStep step);
}
