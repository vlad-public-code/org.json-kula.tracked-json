package org.json_kula.tracked_json.json_patch;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.tracked_json.json_node.TrackedJsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiled, immutable JSON Patch document (RFC 6902).
 * Thread-safe; each {@link #apply} call works on an independent deep copy of the input document.
 */
public final class JsonPatch {

    private final List<JsonPatchOperation> operations;

    private JsonPatch(List<JsonPatchOperation> operations) {
        this.operations = List.copyOf(operations);
    }

    public List<JsonPatchOperation> getOperations() {
        return operations;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Parses and compiles a JSON Patch document (an array of operation objects).
     *
     * @throws JsonPatchException if {@code patch} is not a valid patch document
     */
    public static JsonPatch compile(JsonNode patch) throws JsonPatchException {
        if (!patch.isArray()) {
            throw new JsonPatchException("JSON Patch document must be a JSON array");
        }
        List<JsonPatchOperation> ops = new ArrayList<>(patch.size());
        for (int i = 0; i < patch.size(); i++) {
            ops.add(parseOperation(patch.get(i), i));
        }
        return new JsonPatch(ops);
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    /**
     * Applies this patch to {@code document} and returns the patched result.
     * The original document is never modified.
     *
     * @throws JsonPatchException if any operation fails
     */
    public JsonNode apply(JsonNode document) throws JsonPatchException {
        return applyInternal(document.deepCopy()).node();
    }

    /**
     * Convenience overload — applies this patch to the underlying node of {@code document}
     * and wraps the result as a new root {@link TrackedJsonNode}.
     *
     * @throws JsonPatchException if any operation fails
     */
    public TrackedJsonNode apply(TrackedJsonNode document) throws JsonPatchException {
        return applyInternal(document.node().deepCopy());
    }

    private TrackedJsonNode applyInternal(JsonNode workingDoc) throws JsonPatchException {
        TrackedJsonNode working = TrackedJsonNode.ofRoot(workingDoc);
        for (JsonPatchOperation op : operations) {
            working = applyOp(working, op);
        }
        return working;
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private static JsonPatchOperation parseOperation(JsonNode opNode, int index) throws JsonPatchException {
        if (!opNode.isObject()) {
            throw new JsonPatchException("operation at index " + index + " must be a JSON object");
        }
        JsonNode opField = opNode.get("op");
        if (opField == null || !opField.isTextual()) {
            throw new JsonPatchException("operation at index " + index + " is missing the 'op' field");
        }
        return switch (opField.asText()) {
            case "add"     -> new JsonPatchOperation.Add(requirePointer(opNode, "path", index),
                                                         requireValue(opNode, index));
            case "remove"  -> new JsonPatchOperation.Remove(requirePointer(opNode, "path", index));
            case "replace" -> new JsonPatchOperation.Replace(requirePointer(opNode, "path", index),
                                                             requireValue(opNode, index));
            case "move"    -> new JsonPatchOperation.Move(requirePointer(opNode, "from", index),
                                                          requirePointer(opNode, "path", index));
            case "copy"    -> new JsonPatchOperation.Copy(requirePointer(opNode, "from", index),
                                                          requirePointer(opNode, "path", index));
            case "test"    -> new JsonPatchOperation.Test(requirePointer(opNode, "path", index),
                                                          requireValue(opNode, index));
            default        -> throw new JsonPatchException(
                    "unknown 'op' value '" + opField.asText() + "' at index " + index);
        };
    }

    private static JsonPointer requirePointer(JsonNode opNode, String field, int index)
            throws JsonPatchException {
        JsonNode node = opNode.get(field);
        if (node == null || !node.isTextual()) {
            throw new JsonPatchException("operation at index " + index + " is missing the '" + field + "' field");
        }
        String value = node.asText();
        if (!value.isEmpty() && !value.startsWith("/")) {
            throw new JsonPatchException(
                    "'" + field + "' at index " + index + " must be an empty string or start with '/': " + value);
        }
        return JsonPointer.compile(value);
    }

    private static JsonNode requireValue(JsonNode opNode, int index) throws JsonPatchException {
        JsonNode value = opNode.get("value");
        if (value == null) {
            throw new JsonPatchException("operation at index " + index + " is missing the 'value' field");
        }
        return value;
    }

    // ── Operation dispatch ────────────────────────────────────────────────────

    private static TrackedJsonNode applyOp(TrackedJsonNode root, JsonPatchOperation op)
            throws JsonPatchException {
        return switch (op) {
            case JsonPatchOperation.Add(var path, var value)      -> applyAdd(root, path, value);
            case JsonPatchOperation.Remove(var path)              -> applyRemove(root, path);
            case JsonPatchOperation.Replace(var path, var value)  -> applyReplace(root, path, value);
            case JsonPatchOperation.Move(var from, var path)      -> applyMove(root, from, path);
            case JsonPatchOperation.Copy(var from, var path)      -> applyCopy(root, from, path);
            case JsonPatchOperation.Test(var path, var value)     -> applyTest(root, path, value);
        };
    }

    // ── add ───────────────────────────────────────────────────────────────────

    private static TrackedJsonNode applyAdd(TrackedJsonNode root, JsonPointer path, JsonNode value)
            throws JsonPatchException {
        if (path.last() == null) {
            // RFC 6902 §4.1: 'add' on root replaces the entire document
            return TrackedJsonNode.ofRoot(value.deepCopy());
        }
        TrackedJsonNode parent = root.at(path).parent();
        if (parent.isMissingNode()) {
            throw new JsonPatchException("add: parent path does not exist: " + path.head());
        }
        String token = path.last().getMatchingProperty();
        if (parent.isObject()) {
            ((ObjectNode) parent.node()).set(token, value.deepCopy());
        } else if (parent.isArray()) {
            ArrayNode arr = (ArrayNode) parent.node();
            if ("-".equals(token)) {
                arr.add(value.deepCopy());
            } else {
                int idx = parseArrayIndex(token, path, "add");
                if (idx < 0 || idx > arr.size()) {
                    throw new JsonPatchException(
                            "add: array index " + idx + " out of range for array of size " + arr.size() + " at " + path);
                }
                arr.insert(idx, value.deepCopy());
            }
        } else {
            throw new JsonPatchException("add: parent at " + path.head() + " is not an object or array");
        }
        return root;
    }

    // ── remove ────────────────────────────────────────────────────────────────

    private static TrackedJsonNode applyRemove(TrackedJsonNode root, JsonPointer path)
            throws JsonPatchException {
        if (path.last() == null) {
            throw new JsonPatchException("remove: cannot remove the root document");
        }
        TrackedJsonNode parent = root.at(path).parent();
        if (parent.isMissingNode()) {
            throw new JsonPatchException("remove: parent path does not exist: " + path.head());
        }
        String token = path.last().getMatchingProperty();
        if (parent.isObject()) {
            ObjectNode obj = (ObjectNode) parent.node();
            if (!obj.has(token)) {
                throw new JsonPatchException("remove: field '" + token + "' not found at " + path.head());
            }
            obj.remove(token);
        } else if (parent.isArray()) {
            if ("-".equals(token)) {
                throw new JsonPatchException("remove: '-' is not a valid array index");
            }
            ArrayNode arr = (ArrayNode) parent.node();
            int idx = parseArrayIndex(token, path, "remove");
            if (idx < 0 || idx >= arr.size()) {
                throw new JsonPatchException(
                        "remove: array index " + idx + " out of range for array of size " + arr.size() + " at " + path);
            }
            arr.remove(idx);
        } else {
            throw new JsonPatchException("remove: parent at " + path.head() + " is not an object or array");
        }
        return root;
    }

    // ── replace ───────────────────────────────────────────────────────────────

    private static TrackedJsonNode applyReplace(TrackedJsonNode root, JsonPointer path, JsonNode value)
            throws JsonPatchException {
        if (path.last() == null) {
            // RFC 6902 §4.3: 'replace' on root replaces the entire document
            return TrackedJsonNode.ofRoot(value.deepCopy());
        }
        TrackedJsonNode parent = root.at(path).parent();
        if (parent.isMissingNode()) {
            throw new JsonPatchException("replace: parent path does not exist: " + path.head());
        }
        String token = path.last().getMatchingProperty();
        if (parent.isObject()) {
            ObjectNode obj = (ObjectNode) parent.node();
            if (!obj.has(token)) {
                throw new JsonPatchException("replace: field '" + token + "' not found at " + path.head());
            }
            obj.set(token, value.deepCopy());
        } else if (parent.isArray()) {
            if ("-".equals(token)) {
                throw new JsonPatchException("replace: '-' is not a valid array index");
            }
            ArrayNode arr = (ArrayNode) parent.node();
            int idx = parseArrayIndex(token, path, "replace");
            if (idx < 0 || idx >= arr.size()) {
                throw new JsonPatchException(
                        "replace: array index " + idx + " out of range for array of size " + arr.size() + " at " + path);
            }
            arr.set(idx, value.deepCopy());
        } else {
            throw new JsonPatchException("replace: parent at " + path.head() + " is not an object or array");
        }
        return root;
    }

    // ── move ──────────────────────────────────────────────────────────────────

    private static TrackedJsonNode applyMove(TrackedJsonNode root, JsonPointer from, JsonPointer path)
            throws JsonPatchException {
        TrackedJsonNode fromNode = root.at(from);
        if (fromNode.isMissingNode()) {
            throw new JsonPatchException("move: source not found at " + from);
        }
        JsonNode value = fromNode.node().deepCopy();    // capture before removal shifts the tree
        root = applyRemove(root, from);
        root = applyAdd(root, path, value);
        return root;
    }

    // ── copy ──────────────────────────────────────────────────────────────────

    private static TrackedJsonNode applyCopy(TrackedJsonNode root, JsonPointer from, JsonPointer path)
            throws JsonPatchException {
        TrackedJsonNode fromNode = root.at(from);
        if (fromNode.isMissingNode()) {
            throw new JsonPatchException("copy: source not found at " + from);
        }
        return applyAdd(root, path, fromNode.node());   // applyAdd deep-copies before inserting
    }

    // ── test ──────────────────────────────────────────────────────────────────

    private static TrackedJsonNode applyTest(TrackedJsonNode root, JsonPointer path, JsonNode expected)
            throws JsonPatchException {
        TrackedJsonNode target = root.at(path);
        if (target.isMissingNode()) {
            throw new JsonPatchException("test: path not found: " + path);
        }
        if (!target.node().equals(expected)) {
            throw new JsonPatchException("test: value at " + path + " does not equal expected value");
        }
        return root;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int parseArrayIndex(String token, JsonPointer fullPath, String op)
            throws JsonPatchException {
        if (token.length() > 1 && token.charAt(0) == '0') {
            throw new JsonPatchException(
                    op + ": array index must not have leading zeros: '" + token + "' at " + fullPath);
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new JsonPatchException(
                    op + ": invalid array index '" + token + "' at " + fullPath, e);
        }
    }
}
