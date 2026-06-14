# TrackedJSON

A thin wrapper around Jackson's `JsonNode` that carries its `JsonPointer` — the node's absolute location within the document. Every navigation call propagates tracking automatically, so you always know where a value came from.

## Why

When you traverse a JSON document with plain `JsonNode`, you lose location information. Error messages become vague ("invalid value") and diagnostics require re-traversal. `TrackedJsonNode` keeps pointer and value together at all times.

## Requirements

- Java 21
- Jackson Databind 2.18+

## Package layout

| Package | Contents |
|---|---|
| `org.json_kula.tracked_json.json_node` | `TrackedJsonNode` — the core pointer-tracking wrapper |
| `org.json_kula.tracked_json.json_pointer` | `JsonPointerStep` — last-segment descriptor |
| `org.json_kula.tracked_json.json_path` | `JsonPathSearch`, `JsonPathExpression`, `InvalidPathException` — JSONPath public API |
| `org.json_kula.tracked_json.json_patch` | `JsonPatch`, `JsonPatchException` — JSON Patch public API |

## Quick start

```java
ObjectMapper mapper = new ObjectMapper();
JsonNode doc = mapper.readTree(json);
TrackedJsonNode root = TrackedJsonNode.ofRoot(doc);

TrackedJsonNode value = root.get("order").get("items").get(0).get("price");
System.out.println(value.asDouble());           // 9.99
System.out.println(value.pointer().toString()); // /order/items/0/price
```

## API

### Factory

```java
TrackedJsonNode.ofRoot(JsonNode root)                            // pointer = ""
TrackedJsonNode.of(JsonNode root, JsonNode node, JsonPointer p)  // arbitrary position
```

### Navigation — pointer extends automatically

| Method | Description | Missing behaviour |
|---|---|---|
| `get(String field)` | Navigate to an object property by name | returns `null` |
| `get(int index)` | Navigate to an array element by zero-based index | returns `null` |
| `path(String field)` | Navigate to an object property by name (null-safe) | returns MissingNode-wrapped node |
| `path(int index)` | Navigate to an array element by zero-based index (null-safe) | returns MissingNode-wrapped node |
| `at(JsonPointer rel)` | Navigate to a descendant by relative JSON Pointer | returns MissingNode-wrapped node |
| `at(String jsonPtrExpr)` | Navigate to a descendant by relative JSON Pointer expression | returns MissingNode-wrapped node |
| `parent()` | Navigate to the parent node | returns MissingNode-wrapped node for the root |

Field names containing `/` or `~` are RFC 6901-escaped in the pointer automatically.

### Iteration

```java
// Array elements or object values — each wrapped with its pointer
Iterator<TrackedJsonNode>  values()
Stream<TrackedJsonNode>    valueStream()

// Object properties
Stream<Map.Entry<String, TrackedJsonNode>>   propertyStream()
Set<Map.Entry<String, TrackedJsonNode>>      properties()
void forEachEntry(BiConsumer<String, TrackedJsonNode> action)
```

### JSONPath search

Implements [RFC 9535 — JSONPath: Query Expressions for JSON](https://www.rfc-editor.org/rfc/rfc9535).

```java
// Compile once, evaluate many times
JsonPathExpression expr = JsonPathExpression.compile("$.store.book[*].author");
List<TrackedJsonNode> results = JsonPathSearch.find(root, expr);

// Or parse and evaluate in one call
List<TrackedJsonNode> results = JsonPathSearch.find(root, "$.store.book[?(@.price < 10)]");

// Each result carries its absolute pointer
results.forEach(n -> System.out.println(n.pointer() + " = " + n.asText()));
```

Supported features:
- Child (`$.name`, `$['name']`) and wildcard (`.*`, `[*]`) selectors
- Index (`[0]`, `[-1]`) and slice (`[1:5:2]`) selectors
- Recursive descent (`..`)
- Union (`['a','b']`, `[0,2]`)
- Filter expressions (`[?(...)]`) with `&&`, `||`, `!`, comparison operators, and nested paths
- Function extensions: `length()`, `count()`, `value()`, `match()`, `search()` (I-Regexp per [RFC 9485](https://www.rfc-editor.org/rfc/rfc9485))

Throws `InvalidPathException` for malformed expressions; returns an empty list when nothing matches. Validated against the [RFC 9535 Compliance Test Suite](https://github.com/jsonpath-standard/jsonpath-compliance-test-suite) (703 tests).

### JSON Patch

Implements [RFC 6902 — JavaScript Object Notation (JSON) Patch](https://datatracker.ietf.org/doc/html/rfc6902). Supports all six operations: `add`, `remove`, `replace`, `move`, `copy`, `test`.

```java
// Compile once (validates the patch document), apply many times
JsonNode patch = mapper.readTree("""
        [
          { "op": "replace", "path": "/status", "value": "shipped" },
          { "op": "add",     "path": "/updatedAt", "value": "2025-01-01" },
          { "op": "test",    "path": "/version", "value": 3 }
        ]
        """);

JsonPatch compiled = JsonPatch.compile(patch);  // throws JsonPatchException if invalid
JsonNode result    = compiled.apply(document);  // throws JsonPatchException if an operation fails

// Convenience overload for TrackedJsonNode — returns a new root-tracked node
TrackedJsonNode result = compiled.apply(trackedDocument);
```

`compile` validates the structure of every operation (missing fields, unknown ops, non-pointer `path`/`from` values). `apply` works on a deep copy of the document, so the original is never mutated even when a later operation fails.


### Step — last navigation segment

```java
JsonPointerStep step = node.step();
switch (step) {
    case JsonPointerStep.Name(var name) -> System.out.println("field: " + name);
    case JsonPointerStep.Index(var idx) -> System.out.println("index: " + idx);
}
```

`JsonPointerStep.ROOT` (`Name("")`) is returned for the root node.

### Accessors and delegation

```java
JsonNode    node()      // raw Jackson node
JsonPointer pointer()   // absolute path from document root
JsonNode    root()      // document root node

// Type checks: isObject, isArray, isValueNode, isMissingNode, isNull,
//              isTextual, isNumber, isBoolean, has(String), has(int), size()
// Value reads:  asText, asInt, asLong, asDouble, asBoolean
```

## Build

```
mvn test
```
