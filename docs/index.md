## Overview

`TrackedJsonNode` is a Jackson `JsonNode` decorator that carries two pieces of provenance: its `JsonPointer` (absolute location within the document) and a reference to the document root. Every navigation call — `get`, `path`, `at`, `parent()` — propagates provenance automatically.

Bundled with it: a full [RFC 9535 JSONPath](https://www.rfc-editor.org/rfc/rfc9535) engine and a full [RFC 6902 JSON Patch](https://datatracker.ietf.org/doc/html/rfc6902) implementation, both backed by `TrackedJsonNode`.

**Requirements:** Java 21 · Jackson Databind 2.18+

---

## Installation

```xml
<dependency>
    <groupId>io.github.vlad-public-code</groupId>
    <artifactId>tracked-json</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## Quick start

```java
ObjectMapper mapper = new ObjectMapper();
JsonNode doc = mapper.readTree(json);
TrackedJsonNode root = TrackedJsonNode.ofRoot(doc);

// Field and index navigation — pointer grows automatically
TrackedJsonNode price = root.get("order").get("items").get(0).get("price");
System.out.println(price.asDouble());            // 9.99
System.out.println(price.pointer().toString());  // /order/items/0/price

// Jump directly by JsonPointer
TrackedJsonNode same = root.at("/order/items/0/price");

// Navigate upward
TrackedJsonNode item = price.parent();
System.out.println(item.pointer().toString());   // /order/items/0

// JSONPath search — each result carries its absolute pointer
List<TrackedJsonNode> cheap = JsonPathSearch.find(root, "$.order.items[?(@.price < 10)]");
cheap.forEach(n -> System.out.println(n.pointer() + " = " + n));
```

---

## TrackedJsonNode

### Factory methods

```java
TrackedJsonNode.ofRoot(JsonNode root)                            // pointer = ""
TrackedJsonNode.of(JsonNode root, JsonNode node, JsonPointer p)  // arbitrary position
```

### Navigation

Every method returns a `TrackedJsonNode` with the pointer extended one step.

| Method | Missing behaviour |
|---|---|
| `get(String field)` | returns `null` |
| `get(int index)` | returns `null` |
| `path(String field)` | returns MissingNode-wrapped node |
| `path(int index)` | returns MissingNode-wrapped node |
| `at(JsonPointer)` / `at(String)` | returns MissingNode-wrapped node |
| `parent()` | returns MissingNode-wrapped node for root |

Field names containing `/` or `~` are RFC 6901-escaped in the pointer automatically.

### Iteration

```java
Iterator<TrackedJsonNode>                     values()
Stream<TrackedJsonNode>                       valueStream()
Stream<Map.Entry<String, TrackedJsonNode>>    propertyStream()
Set<Map.Entry<String, TrackedJsonNode>>       properties()
void forEachEntry(BiConsumer<String, TrackedJsonNode> action)
```

### Last-segment descriptor

```java
JsonPointerStep step = node.step();
switch (step) {
    case JsonPointerStep.Name(var name)  -> System.out.println("field: " + name);
    case JsonPointerStep.Index(var idx) -> System.out.println("index: " + idx);
}
```

`JsonPointerStep.ROOT` (`Name("")`) is returned for the root node.

### Accessors

```java
JsonNode    node()      // raw Jackson node
JsonPointer pointer()   // absolute path from document root
JsonNode    root()      // document root node

// Type checks: isObject, isArray, isValueNode, isMissingNode, isNull,
//              isTextual, isNumber, isBoolean, has(String), has(int), size()
// Value reads: asText, asInt, asLong, asDouble, asBoolean
```

---

## JSONPath  <small>(RFC 9535)</small>

```java
// Compile once, evaluate many times
JsonPathExpression expr = JsonPathExpression.compile("$.store.book[*].author");
List<TrackedJsonNode> results = JsonPathSearch.find(root, expr);

// Or parse and evaluate in one call
List<TrackedJsonNode> results = JsonPathSearch.find(root, "$.store.book[?(@.price < 10)]");

// Each result carries its absolute pointer
results.forEach(n -> System.out.println(n.pointer() + " = " + n.asText()));
```

`InvalidPathException` is thrown for malformed expressions. An empty list is returned when nothing matches.

### Supported selectors and features

| Feature | Syntax |
|---|---|
| Child | `$.name`, `$['name']` |
| Wildcard | `.*`, `[*]` |
| Index | `[0]`, `[-1]` |
| Slice | `[1:5:2]` |
| Recursive descent | `..` |
| Union | `['a','b']`, `[0,2]` |
| Filter | `[?(@.price < 10)]` with `&&`, `\|\|`, `!`, comparisons, nested paths |
| Functions | `length()`, `count()`, `value()`, `match()`, `search()` |

Pattern matching (`match`, `search`) uses I-Regexp ([RFC 9485](https://www.rfc-editor.org/rfc/rfc9485)).

Validated against the [RFC 9535 Compliance Test Suite](https://github.com/jsonpath-standard/jsonpath-compliance-test-suite) — 703 tests, including pointer verification for the 447 tests that supply `result_paths`.

---

## JSON Patch  <small>(RFC 6902)</small>

All six operations are supported: `add`, `remove`, `replace`, `move`, `copy`, `test`.

```java
JsonNode patchDoc = mapper.readTree("""
        [
          { "op": "replace", "path": "/status",    "value": "shipped" },
          { "op": "add",     "path": "/updatedAt", "value": "2025-01-01" },
          { "op": "test",    "path": "/version",   "value": 3 }
        ]
        """);

// Compile once — validates structure, ops, and pointer syntax
JsonPatch patch = JsonPatch.compile(patchDoc);

// Apply to a plain Jackson node — original is never mutated
JsonNode result = patch.apply(document);

// Or apply to a TrackedJsonNode — returns a new root-tracked node
TrackedJsonNode result = patch.apply(trackedDocument);
```

`JsonPatchException` is thrown if the patch document is invalid at compile time, or if any operation fails at apply time. `apply` works on a deep copy, so the original document is unchanged even when a later operation fails.

### Conformance notes

- Array index `-` is accepted only by `add` (append to end).
- Array indices with leading zeros (`"01"`) or non-integer tokens (`"1e0"`) are rejected per RFC 6901.
- Root path `""` is valid for `add` and `replace`; `remove` on root throws.

Validated against 126 compliance cases drawn from 9 test data files.

---

## Build

```
mvn test
```

---

## See also

- [jsonata-jvm-compiler](https://vlad-public-code.github.io/org.json-kula.jsonata-jvm-compiler/) — A Java library that compiles [JSONata](https://jsonata.org) expressions into native Java classes at runtime
