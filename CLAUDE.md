# CLAUDE.md — TrackedJSON

## Project purpose

`TrackedJsonNode` is a Jackson `JsonNode` decorator that carries the node's `JsonPointer` (absolute location in the document). Navigation propagates the pointer automatically. It is a foundational type for the broader `json_kula` ecosystem.

## Package and build

- Package: `org.json_kula.tracked_json`
- Java 21, Maven
- `mvn test` to verify

## Source files

| File | Role |
|---|---|
| `TrackedJsonNode.java` | Core wrapper — navigation, iteration, delegation |
| `JsonPointerStep.java` | Sealed interface: `Name(String)` / `Index(int)` representing the last navigation step |
| `JsonPathSearch.java` | JSONPath search utility; extracted from `TrackedJsonNode` |

## Key design decisions

**`TrackedJsonNode` is final and not a `JsonNode` subclass.** Callers that need the raw Jackson API must unwrap with `node()`. This avoids the complexity of subclassing Jackson internals.

**`step` field is lazy.** The three-arg private constructor sets `step` eagerly when it is already known (navigation via `get`/`path`/`values`). Nodes created via `of()` or `at()` set `step = null` and compute it on first call to `step()` by inspecting the tail of the pointer.

**RFC 6901 escaping is handled by Jackson.** `pointer.appendProperty(name)` escapes `~` → `~0` and `/` → `~1` automatically. Never escape manually.

**`node.properties()` not `node.fields()`.** `fields()` is deprecated since Jackson 2.18. Use `properties()` everywhere.

## JSONPath search (`JsonPathSearch`)

Uses Jayway JsonPath with `JacksonJsonNodeJsonProvider` + `AS_PATH_LIST` + `SUPPRESS_EXCEPTIONS` + `ALWAYS_RETURN_LIST`.

- `AS_PATH_LIST` causes JsonPath to return an `ArrayNode` of `TextNode`s (normalized path strings like `$['key'][0]`), **not** a `List<String>`. Type the result as `JsonNode`, iterate with for-each.
- `SUPPRESS_EXCEPTIONS` suppresses `PathNotFoundException` (no match), but **not** `InvalidPathException` (bad syntax). Bad syntax propagates as a `RuntimeException` — this is intentional.
- `toRelativePointer` converts the normalized path to a `JsonPointer`; `origin.at(rel)` then resolves the node and combines pointers in one call.

## Testing conventions

- All tests use JUnit 5 (`@BeforeAll` + `@Test`).
- Test document is parsed once in `@BeforeAll` and stored in a `static` field.
- `TrackedJsonNodeTest` — navigation, iteration, step, RFC 6901 escaping, edge cases, plus a small subset of `findByJsonPath` smoke tests.
- `FindByJsonPathTest` — dedicated coverage for `findByJsonPath`: root selection, empty-key property, wildcard, recursive descent, predicates, non-root origin, no-match, invalid expression.
- Run with `mvn test`.
