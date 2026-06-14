package org.json_kula.tracked_json.json_node;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.json_kula.tracked_json.json_path.JsonPathSearch;
import org.json_kula.tracked_json.json_pointer.JsonPointerStep;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public final class TrackedJsonNode {
    private final JsonNode root;
    private final JsonNode node;
    private final JsonPointer pointer;
    private JsonPointerStep step;   // lazy calculated for some cases
    private TrackedJsonNode parent; // lazy calculated

    private TrackedJsonNode(JsonNode root, JsonNode node, JsonPointer pointer, JsonPointerStep step, TrackedJsonNode parent) {
        this.root = root;
        this.node = node;
        this.pointer = pointer;
        this.step = step;
        this.parent = parent;
    }

    public static TrackedJsonNode ofRoot(JsonNode root) {
        return new TrackedJsonNode(root, root, JsonPointer.empty(), JsonPointerStep.ROOT, null);
    }

    public static TrackedJsonNode of(JsonNode root, JsonNode node, JsonPointer pointer) {
        return new TrackedJsonNode(root, node, pointer, null, null);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public JsonNode node() {
        return node;
    }

    public JsonPointer pointer() {
        return pointer;
    }

    public JsonNode root() {
        return root;
    }

    /**
     * Returns the last navigation step that leads to this node —
     * a {@link JsonPointerStep.Name} for an object property or a {@link JsonPointerStep.Index} for an array element.
     * Empty for the root node (empty pointer).
     */
    public JsonPointerStep step() {
        if (step == null) {
            JsonPointer last = pointer.last();
            if (last == null) {
                step = JsonPointerStep.ROOT;
            }
            else {
                int idx = last.getMatchingIndex();
                step = idx == -1 ? stepForName(last.getMatchingProperty()) : stepForIndex(idx);
            }
        }
        return step;
    }

    /**
     * Returns the parent node in the document.
     * Returns a MissingNode-wrapped instance for the root or for nodes created without a root context.
     */
    public TrackedJsonNode parent() {
        if (parent == null) {
            if (pointer.last() == null || root == null || root().isMissingNode()) {
                parent = new TrackedJsonNode(MissingNode.getInstance(), MissingNode.getInstance(), JsonPointer.empty(), JsonPointerStep.ROOT, null);
            } else {
                JsonPointer parentPointer = pointer.head();
                parent = new TrackedJsonNode(root, root.at(parentPointer), parentPointer, null, null);
            }
        }
        return parent;
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    /** Returns null when the field is absent, mirroring {@link JsonNode#get(String)}. */
    public TrackedJsonNode get(String fieldName) {
        JsonNode child = node.get(fieldName);
        if (child == null) return null;
        return new TrackedJsonNode(root, child, childPointer(fieldName), stepForName(fieldName), this);
    }

    /** Returns null when the index is out of range, mirroring {@link JsonNode#get(int)}. */
    public TrackedJsonNode get(int index) {
        JsonNode child = node.get(index);
        if (child == null) return null;
        return new TrackedJsonNode(root, child, pointer.appendIndex(index), stepForIndex(index), this);
    }

    /** Returns a MissingNode-wrapped TrackedJsonNode when the field is absent, mirroring {@link JsonNode#path(String)}. */
    public TrackedJsonNode path(String fieldName) {
        return new TrackedJsonNode(root, node.path(fieldName), childPointer(fieldName), stepForName(fieldName), this);
    }

    /** Returns a MissingNode-wrapped TrackedJsonNode when the index is out of range, mirroring {@link JsonNode#path(int)}. */
    public TrackedJsonNode path(int index) {
        return new TrackedJsonNode(root, node.path(index), pointer.appendIndex(index), stepForIndex(index), this);
    }

    /**
     * Navigates to the node at {@code rel} relative to this node.
     * The resulting pointer is this node's pointer with {@code rel} appended.
     */
    public TrackedJsonNode at(JsonPointer rel) {
        return new TrackedJsonNode(root, node.at(rel), pointer.append(rel), null, null);
    }

    public TrackedJsonNode at(String jsonPtrExpr) {
        return at(JsonPointer.compile(jsonPtrExpr));
    }

    // ── Iteration ─────────────────────────────────────────────────────────────

    /** Iterates array elements (by index) or object values (by name), each wrapped with its pointer. */
    public Iterator<TrackedJsonNode> elements() {
        return values();
    }

    /** Iterates array elements (by index) or object values (by name), each wrapped with its pointer. */
    public Iterator<TrackedJsonNode> values() {
        TrackedJsonNode _this = this;
        if (node.isArray()) {
            return new Iterator<>() {
                private int i = 0;
                @Override public boolean hasNext() { return i < node.size(); }
                @Override public TrackedJsonNode next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    int idx = i++;
                    return new TrackedJsonNode(root, node.get(idx), pointer.appendIndex(idx), stepForIndex(idx), _this);
                }
            };
        }
        else {
            Iterator<Map.Entry<String, JsonNode>> props = node.properties().iterator();
            return new Iterator<>() {
                @Override public boolean hasNext() { return props.hasNext(); }
                @Override public TrackedJsonNode next() {
                    Map.Entry<String, JsonNode> e = props.next();
                    return new TrackedJsonNode(root, e.getValue(), childPointer(e.getKey()), stepForName(e.getKey()), _this);
                }
            };
        }
    }

    /** Streams array elements (by index) or object values (by name), each wrapped with its pointer. */
    public Stream<TrackedJsonNode> valueStream() {
        if (node.isArray()) {
            return IntStream.range(0, node.size())
                    .mapToObj(i -> new TrackedJsonNode(root, node.get(i), pointer.appendIndex(i), stepForIndex(i), this));
        }
        else {
            return node.properties().stream()
                    .map(e -> new TrackedJsonNode(root, e.getValue(), childPointer(e.getKey()), stepForName(e.getKey()), this));
        }
    }

    /** Streams object properties, each value wrapped with its named pointer. */
    public Stream<Map.Entry<String, TrackedJsonNode>> propertyStream() {
        return node.properties().stream()
                .map(e -> Map.entry(e.getKey(),
                        new TrackedJsonNode(root, e.getValue(), childPointer(e.getKey()), stepForName(e.getKey()), this)));
    }

    /** Calls {@code action} for each object property, passing the key and a wrapped value. */
    public void forEachEntry(BiConsumer<? super String, TrackedJsonNode> action) {
        node.properties().forEach(e ->
                action.accept(e.getKey(), new TrackedJsonNode(root, e.getValue(), childPointer(e.getKey()), stepForName(e.getKey()), this)));
    }

    /** Returns object properties, each value wrapped with its named pointer. */
    public Set<Map.Entry<String, TrackedJsonNode>> properties() {
        Set<Map.Entry<String, TrackedJsonNode>> result = new LinkedHashSet<>(node.size());
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            result.add(Map.entry(e.getKey(),
                    new TrackedJsonNode(root, e.getValue(), childPointer(e.getKey()), stepForName(e.getKey()), this)));
        }
        return result;
    }

    // ── JSONPath search ───────────────────────────────────────────────────────

    /**
     * Evaluates {@code jsonPath} relative to this node and returns all matching nodes,
     * each wrapped with its absolute {@link JsonPointer}.
     * Returns an empty list when nothing matches or the expression is invalid.
     */
    public List<TrackedJsonNode> findByJsonPath(String jsonPath) {
        return JsonPathSearch.find(this, jsonPath);
    }

    // ── Delegation ────────────────────────────────────────────────────────────

    public boolean isObject()    { return node.isObject(); }
    public boolean isArray()     { return node.isArray(); }
    public boolean isValueNode() { return node.isValueNode(); }
    public boolean isMissingNode() { return node.isMissingNode(); }
    public boolean isNull()      { return node.isNull(); }
    public boolean isTextual()   { return node.isTextual(); }
    public boolean isNumber()    { return node.isNumber(); }
    public boolean isBoolean()   { return node.isBoolean(); }
    public boolean has(String fieldName) { return node.has(fieldName); }
    public boolean has(int index)        { return node.has(index); }
    public int     size()                { return node.size(); }

    public String  asText()    { return node.asText(); }
    public int     asInt()     { return node.asInt(); }
    public long    asLong()    { return node.asLong(); }
    public double  asDouble()  { return node.asDouble(); }
    public boolean asBoolean() { return node.asBoolean(); }

    @Override
    public String toString() {
        return node + " @" + pointer;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Appends a property token to this node's pointer, applying RFC 6901 escaping. */
    private JsonPointer childPointer(String fieldName) {
        return pointer.appendProperty(fieldName);
    }

    private JsonPointerStep stepForName(String name) {
        return new JsonPointerStep.Name(name);
    }

    private JsonPointerStep stepForIndex(int index) {
        return new JsonPointerStep.Index(index);
    }
}
