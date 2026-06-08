package org.json_kula.tracked_json.json_path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import org.json_kula.tracked_json.json_node.TrackedJsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Package-private JSONPath parser.
 * Converts a JSONPath string into a list of {@link Step}s that, when applied
 * sequentially to a {@link TrackedJsonNode} list,
 * produce the matching nodes.
 */
final class JsonPathParser {

    static final JsonNodeFactory NF = JsonNodeFactory.instance;

    private JsonPathParser() {}

    static List<Step> parse(String expr) {
        if (expr == null || expr.isEmpty() || expr.charAt(0) != '$')
            throw new InvalidPathException("JSONPath must start with '$': " + expr);
        Cursor c = new Cursor(expr, 1);
        List<Step> steps = new ArrayList<>();
        while (c.hasMore()) {
            char ch = c.peek();
            // RFC 9535 §2.1: insignificant whitespace is allowed between segments (not trailing)
            if (Character.isWhitespace(ch)) {
                int wsStart = c.pos;
                skipWhitespace(c);
                if (!c.hasMore() || (c.peek() != '.' && c.peek() != '['))
                    throw new InvalidPathException(
                            "Unexpected character at position " + wsStart + " in: " + expr);
                ch = c.peek();
            }
            if (ch == '.') {
                c.advance();
                if (c.hasMore() && c.peek() == '.') {
                    c.advance();
                    steps.add(new RecursiveStep(parseAfterDoubledDot(c)));
                } else if (c.hasMore() && c.peek() == '*') {
                    c.advance();
                    steps.add(new WildcardStep());
                } else {
                    steps.add(new FieldStep(parseNameShorthand(c)));
                }
            } else if (ch == '[') {
                c.advance();
                steps.add(parseBracketContent(c));
            } else {
                throw new InvalidPathException(
                        "Unexpected character '" + ch + "' at position " + c.pos + " in: " + expr);
            }
        }
        return steps;
    }

    private static Step parseAfterDoubledDot(Cursor c) {
        if (!c.hasMore())
            throw new InvalidPathException("'..' must be followed by a step in: " + c.src);
        if (c.peek() == '[') {
            c.advance();
            return parseBracketContent(c);
        }
        if (c.peek() == '*') {
            c.advance();
            return new WildcardStep();
        }
        return new FieldStep(parseNameShorthand(c));
    }

    private static Step parseBracketContent(Cursor c) {
        skipWhitespace(c);
        if (!c.hasMore() || c.peek() == ']')
            throw new InvalidPathException("Empty brackets '[]' are not allowed in: " + c.src);
        List<Step> selectors = new ArrayList<>();
        while (true) {
            skipWhitespace(c);
            selectors.add(parseSelector(c));
            skipWhitespace(c);
            if (!c.hasMore()) throw new InvalidPathException("Unclosed '[' in: " + c.src);
            if (c.peek() == ']') { c.advance(); break; }
            if (c.peek() == ',') { c.advance(); continue; }
            throw new InvalidPathException(
                    "Expected ',' or ']' at position " + c.pos + " in: " + c.src);
        }
        return selectors.size() == 1 ? selectors.getFirst() : new UnionStep(selectors);
    }

    private static Step parseSelector(Cursor c) {
        char ch = c.peek();
        if (ch == '*') { c.advance(); return new WildcardStep(); }
        if (ch == '\'' || ch == '"') return new FieldStep(parseQuotedString(c, ch));
        if (ch == '?') {
            c.advance();
            skipWhitespace(c);
            return new FilterStep(parseOrExpr(c));
        }
        if (ch == ':') return parseSliceFrom(c, null);
        if (ch == '-' || Character.isDigit(ch)) return parseNumberOrSlice(c);
        throw new InvalidPathException("Invalid selector at position " + c.pos + " in: " + c.src);
    }

    private static Step parseNumberOrSlice(Cursor c) {
        int first = readInteger(c);
        skipWhitespace(c);
        if (c.hasMore() && c.peek() == ':') return parseSliceFrom(c, first);
        return new IndexStep(first);
    }

    private static Step parseSliceFrom(Cursor c, Integer start) {
        c.advance(); // ':'
        skipWhitespace(c);
        Integer end = null;
        if (c.hasMore() && c.peek() != ':' && c.peek() != ']' && c.peek() != ',')
            end = readInteger(c);
        skipWhitespace(c);
        int stepSize = 1;
        if (c.hasMore() && c.peek() == ':') {
            c.advance();
            skipWhitespace(c);
            if (c.hasMore() && c.peek() != ']' && c.peek() != ',')
                stepSize = readInteger(c);
        }
        return new SliceStep(start, end, stepSize);
    }

    // =========================================================================
    // Filter expression parser (recursive descent)
    // =========================================================================

    private static FilterExpr parseOrExpr(Cursor c) {
        FilterExpr left = parseAndExpr(c);
        skipWhitespace(c);
        if (!c.hasMore() || !c.startsWith("||")) return left;
        List<FilterExpr> terms = new ArrayList<>();
        terms.add(left);
        while (c.hasMore() && c.startsWith("||")) {
            c.advance(2); skipWhitespace(c);
            terms.add(parseAndExpr(c));
            skipWhitespace(c);
        }
        return new OrExpr(terms);
    }

    private static FilterExpr parseAndExpr(Cursor c) {
        FilterExpr left = parseNotExpr(c);
        skipWhitespace(c);
        if (!c.hasMore() || !c.startsWith("&&")) return left;
        List<FilterExpr> terms = new ArrayList<>();
        terms.add(left);
        while (c.hasMore() && c.startsWith("&&")) {
            c.advance(2); skipWhitespace(c);
            terms.add(parseNotExpr(c));
            skipWhitespace(c);
        }
        return new AndExpr(terms);
    }

    private static FilterExpr parseNotExpr(Cursor c) {
        if (c.hasMore() && c.peek() == '!'
                && (c.pos + 1 >= c.src.length() || c.src.charAt(c.pos + 1) != '=')) {
            c.advance(); skipWhitespace(c);
            return new NotExpr(parseNotExpr(c));
        }
        return parsePrimary(c);
    }

    private static FilterExpr parsePrimary(Cursor c) {
        skipWhitespace(c);
        if (c.peek() == '(') {
            c.advance(); skipWhitespace(c);
            FilterExpr inner = parseOrExpr(c);
            skipWhitespace(c);
            expect(c, ')');
            return inner;
        }
        FilterValue left = parseFilterValue(c);
        skipWhitespace(c);
        ComparisonOp op = tryParseOp(c);
        if (op == null) {
            // No comparison operator — must be a boolean context
            if (left instanceof SingularFilterPath sfp) return new ExistenceExpr(sfp);
            if (left instanceof NonSingularFilterPath nsfp) return new ExistenceExpr(nsfp);
            if (left instanceof FunctionCallValue fcv) {
                if (fcv.resultType() == FunctionResultType.LOGICAL) return new FunctionBoolExpr(fcv);
                throw new InvalidPathException(
                        "Function '" + fcv.name() + "' returns a value type and cannot be used as a boolean in: " + c.src);
            }
            throw new InvalidPathException(
                    "Expected comparison operator after literal in: " + c.src);
        }
        // Comparison — neither side may be non-singular or LOGICAL-type function
        validateComparisonOperand(left, "left", c.src);
        skipWhitespace(c);
        FilterValue right = parseFilterValue(c);
        validateComparisonOperand(right, "right", c.src);
        return new ComparisonExpr(left, op, right);
    }

    private static void validateComparisonOperand(FilterValue v, String side, String src) {
        if (v instanceof NonSingularFilterPath)
            throw new InvalidPathException(
                    "Non-singular query cannot be used as " + side + " of comparison in: " + src);
        if (v instanceof FunctionCallValue fcv && fcv.resultType() == FunctionResultType.LOGICAL)
            throw new InvalidPathException(
                    "Function '" + fcv.name() + "' returns logical type and cannot be used in comparison in: " + src);
    }

    private static FilterValue parseFilterValue(Cursor c) {
        char ch = c.peek();
        if (ch == '@' || ch == '$')  return parseFilterPath(c);
        if (ch == '\'' || ch == '"') return new LiteralValue(NF.textNode(parseQuotedString(c, ch)));
        if (ch == '-' || Character.isDigit(ch)) return new LiteralValue(parseNumericLiteral(c));
        if (c.startsWith("true"))    { c.advance(4); return new LiteralValue(BooleanNode.TRUE); }
        if (c.startsWith("false"))   { c.advance(5); return new LiteralValue(BooleanNode.FALSE); }
        if (c.startsWith("null"))    { c.advance(4); return new LiteralValue(NullNode.getInstance()); }
        // Function call: identifier '(' args... ')'
        if (Character.isLetter(ch)) {
            int start = c.pos;
            while (c.hasMore() && (Character.isLetterOrDigit(c.peek()) || c.peek() == '_')) c.advance();
            String fname = c.src.substring(start, c.pos);
            if (!c.hasMore() || c.peek() != '(')
                throw new InvalidPathException(
                        "Unknown identifier '" + fname + "' at position " + start + " in: " + c.src);
            c.advance(); // '('
            List<FilterValue> args = new ArrayList<>();
            skipWhitespace(c);
            while (c.hasMore() && c.peek() != ')') {
                if (!args.isEmpty()) {
                    if (c.peek() != ',')
                        throw new InvalidPathException(
                                "Expected ',' between function arguments in: " + c.src);
                    c.advance(); skipWhitespace(c);
                }
                args.add(parseFilterValue(c));
                skipWhitespace(c);
            }
            expect(c, ')');
            return parseFunctionCall(fname, args, c.src);
        }
        throw new InvalidPathException(
                "Expected filter value at position " + c.pos + " in: " + c.src);
    }

    private static FunctionCallValue parseFunctionCall(String name, List<FilterValue> args, String src) {
        return switch (name) {
            case "length" -> {
                if (args.size() != 1)
                    throw new InvalidPathException("length() requires 1 argument in: " + src);
                FilterValue arg = args.get(0);
                // length() requires a value-type argument (not a non-singular nodes-type)
                if (arg instanceof NonSingularFilterPath)
                    throw new InvalidPathException(
                            "length() requires a value-type argument in: " + src);
                if (arg instanceof FunctionCallValue fcv && fcv.resultType() == FunctionResultType.LOGICAL)
                    throw new InvalidPathException(
                            "length() requires a value-type argument in: " + src);
                yield new FunctionCallValue("length", args, FunctionResultType.VALUE);
            }
            case "count" -> {
                if (args.size() != 1)
                    throw new InvalidPathException("count() requires 1 argument in: " + src);
                FilterValue arg = args.get(0);
                // count() requires a nodes-type argument (a query path)
                if (!(arg instanceof SingularFilterPath || arg instanceof NonSingularFilterPath))
                    throw new InvalidPathException(
                            "count() requires a nodes-type (query) argument in: " + src);
                yield new FunctionCallValue("count", args, FunctionResultType.VALUE);
            }
            case "match" -> {
                if (args.size() != 2)
                    throw new InvalidPathException("match() requires 2 arguments in: " + src);
                for (FilterValue arg : args)
                    if (arg instanceof NonSingularFilterPath)
                        throw new InvalidPathException(
                                "match() requires value-type arguments in: " + src);
                yield new FunctionCallValue("match", args, FunctionResultType.LOGICAL);
            }
            case "search" -> {
                if (args.size() != 2)
                    throw new InvalidPathException("search() requires 2 arguments in: " + src);
                for (FilterValue arg : args)
                    if (arg instanceof NonSingularFilterPath)
                        throw new InvalidPathException(
                                "search() requires value-type arguments in: " + src);
                yield new FunctionCallValue("search", args, FunctionResultType.LOGICAL);
            }
            case "value" -> {
                if (args.size() != 1)
                    throw new InvalidPathException("value() requires 1 argument in: " + src);
                FilterValue arg = args.get(0);
                // value() requires a nodes-type argument (a query path)
                if (!(arg instanceof SingularFilterPath || arg instanceof NonSingularFilterPath))
                    throw new InvalidPathException(
                            "value() requires a nodes-type (query) argument in: " + src);
                yield new FunctionCallValue("value", args, FunctionResultType.VALUE);
            }
            default -> throw new InvalidPathException(
                    "Unknown function '" + name + "' in: " + src);
        };
    }

    // Parses a filter path starting with @ (relative) or $ (absolute).
    // Returns SingularFilterPath for paths that always yield one node,
    // NonSingularFilterPath for paths that may yield multiple nodes.
    private static FilterValue parseFilterPath(Cursor c) {
        boolean absolute = c.peek() == '$';
        c.advance(); // consume @ or $

        // Collect steps as a mixed list: String/Integer for singular, Step for non-singular.
        // Once we see a non-singular step (wildcard, union, slice, nested filter), we switch mode.
        List<Object> singularSteps = new ArrayList<>(); // String=field, Integer=index
        List<Step> steps = null; // non-null once we go non-singular

        while (c.hasMore()) {
            // RFC 9535 allows insignificant whitespace between path steps inside filter paths
            skipWhitespace(c);
            if (!c.hasMore()) break;
            char ch = c.peek();
            if (ch == '.') {
                c.advance();
                if (c.hasMore() && c.peek() == '.') {
                    // Recursive descent in filter path is non-singular
                    c.advance();
                    Step inner = parseAfterDoubledDot(c);
                    steps = toStepList(singularSteps);
                    steps.add(new RecursiveStep(inner));
                } else if (c.hasMore() && c.peek() == '*') {
                    c.advance();
                    steps = toStepList(singularSteps);
                    steps.add(new WildcardStep());
                } else {
                    String name = parseNameShorthand(c);
                    if (steps != null) steps.add(new FieldStep(name));
                    else singularSteps.add(name);
                }
            } else if (ch == '[') {
                c.advance(); // consume '['
                // Delegate to parseBracketContent which handles all cases:
                // single name/index (singular), wildcard/union/slice/nested-filter (non-singular)
                Step bracketStep = parseBracketContent(c);
                if (bracketStep instanceof FieldStep fs) {
                    if (steps != null) steps.add(fs);
                    else singularSteps.add(fs.name());
                } else if (bracketStep instanceof IndexStep is) {
                    if (steps != null) steps.add(is);
                    else singularSteps.add(is.index());
                } else {
                    steps = toStepList(singularSteps);
                    steps.add(bracketStep);
                }
            } else {
                break;
            }
        }

        if (steps != null) return new NonSingularFilterPath(absolute, steps);
        return new SingularFilterPath(absolute, singularSteps);
    }

    // Convert accumulated singular steps (String/Integer) to a Step list.
    private static List<Step> toStepList(List<Object> singular) {
        List<Step> result = new ArrayList<>();
        for (Object s : singular) {
            if (s instanceof String name) result.add(new FieldStep(name));
            else result.add(new IndexStep((Integer) s));
        }
        return result;
    }

    private static ComparisonOp tryParseOp(Cursor c) {
        if (!c.hasMore()) return null;
        if (c.startsWith("==")) { c.advance(2); return ComparisonOp.EQ; }
        if (c.startsWith("!=")) { c.advance(2); return ComparisonOp.NEQ; }
        if (c.startsWith("<=")) { c.advance(2); return ComparisonOp.LTE; }
        if (c.startsWith(">=")) { c.advance(2); return ComparisonOp.GTE; }
        if (c.peek() == '<')   { c.advance();   return ComparisonOp.LT; }
        if (c.peek() == '>')   { c.advance();   return ComparisonOp.GT; }
        return null;
    }

    // =========================================================================
    // Tokenizer helpers
    // =========================================================================

    // Parses a shorthand name with RFC 9535 name-first character validation.
    // Used for $.name and ..name contexts.
    private static String parseNameShorthand(Cursor c) {
        if (!c.hasMore())
            throw new InvalidPathException("Expected name at end of: " + c.src);
        char first = c.peek();
        if (!isNameFirst(first))
            throw new InvalidPathException(
                    "Invalid name-first character '" + first + "' at position " + c.pos
                    + " in: " + c.src);
        return parseName(c);
    }

    // RFC 9535 name-first: ALPHA | "_" | %x80-10FFFF (any non-ASCII)
    private static boolean isNameFirst(char ch) {
        return Character.isLetter(ch) || ch == '_' || (int) ch > 0x7F;
    }

    // Reads identifier characters until a structurally significant delimiter.
    // Stops at operator characters so @.a==1 parses field "a", not "a==1".
    private static String parseName(Cursor c) {
        int start = c.pos;
        while (c.hasMore()) {
            char ch = c.peek();
            if (ch == '.' || ch == '[' || ch == ']' || ch == ',' || ch == ')'
                    || ch == '=' || ch == '<' || ch == '>' || ch == '!' || ch == '('
                    || ch == '|' || ch == '&'
                    || Character.isWhitespace(ch)) break;
            c.advance();
        }
        if (c.pos == start)
            throw new InvalidPathException(
                    "Expected identifier at position " + start + " in: " + c.src);
        return c.src.substring(start, c.pos);
    }

    // Parses a quoted string per RFC 9535 Section 2.3.1.1:
    //   double-quoted: allows backslash-quote; backslash, b, f, n, r, t, uHHHH
    //   single-quoted: allows backslash-apos; backslash, b, f, n, r, t, uHHHH
    //   Rejects: unescaped control chars; lone surrogates; invalid escape sequences.
    private static String parseQuotedString(Cursor c, char quote) {
        c.advance(); // opening quote
        StringBuilder sb = new StringBuilder();
        while (c.hasMore() && c.peek() != quote) {
            char ch = c.src.charAt(c.pos++);
            if (ch == '\\') {
                if (!c.hasMore())
                    throw new InvalidPathException("Unterminated escape in: " + c.src);
                char esc = c.src.charAt(c.pos++);
                switch (esc) {
                    case '\\' -> sb.append('\\');
                    case '/'  -> sb.append('/');
                    case 'b'  -> sb.append('\b');
                    case 'f'  -> sb.append('\f');
                    case 'n'  -> sb.append('\n');
                    case 'r'  -> sb.append('\r');
                    case 't'  -> sb.append('\t');
                    case '"'  -> {
                        if (quote != '"')
                            throw new InvalidPathException(
                                    "Invalid escape '\\\"' in single-quoted string in: " + c.src);
                        sb.append('"');
                    }
                    case '\'' -> {
                        if (quote != '\'')
                            throw new InvalidPathException(
                                    "Invalid escape \"\\\'\" in double-quoted string in: " + c.src);
                        sb.append('\'');
                    }
                    case 'u'  -> {
                        if (c.pos + 4 > c.src.length())
                            throw new InvalidPathException("Incomplete \\uXXXX escape in: " + c.src);
                        String hex = c.src.substring(c.pos, c.pos + 4);
                        // Validate all 4 chars are hex digits
                        for (char hc : hex.toCharArray())
                            if (!isHexDigit(hc))
                                throw new InvalidPathException(
                                        "Invalid \\u escape '" + hex + "' in: " + c.src);
                        c.advance(4);
                        int cp = Integer.parseInt(hex, 16);
                        if (cp >= 0xD800 && cp <= 0xDBFF) {
                            // High surrogate — must be followed by low surrogate
                            if (!c.startsWith("\\u") || c.pos + 6 > c.src.length())
                                throw new InvalidPathException(
                                        "Lone high surrogate \\u" + hex + " in: " + c.src);
                            String hex2 = c.src.substring(c.pos + 2, c.pos + 6);
                            for (char hc : hex2.toCharArray())
                                if (!isHexDigit(hc))
                                    throw new InvalidPathException(
                                            "Invalid \\u escape '" + hex2 + "' in: " + c.src);
                            int cp2 = Integer.parseInt(hex2, 16);
                            if (cp2 < 0xDC00 || cp2 > 0xDFFF)
                                throw new InvalidPathException(
                                        "High surrogate \\u" + hex + " not followed by low surrogate in: " + c.src);
                            c.advance(6); // skip the surrogate pair
                            // Combine into supplementary character
                            sb.appendCodePoint(Character.toCodePoint((char) cp, (char) cp2));
                        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
                            throw new InvalidPathException(
                                    "Lone low surrogate \\u" + hex + " in: " + c.src);
                        } else {
                            sb.append((char) cp);
                        }
                    }
                    default -> throw new InvalidPathException(
                            "Invalid escape '\\" + esc + "' in: " + c.src);
                }
            } else {
                // Unescaped character — control chars U+0000–U+001F are forbidden per RFC 9535
                if (ch < 0x20)
                    throw new InvalidPathException(
                            "Unescaped control character U+" + String.format("%04X", (int) ch)
                            + " in string in: " + c.src);
                sb.append(ch);
            }
        }
        if (!c.hasMore()) throw new InvalidPathException("Unterminated string in: " + c.src);
        c.advance(); // closing quote
        return sb.toString();
    }

    private static boolean isHexDigit(char ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
    }

    // Array index parser. RFC 9535 §2.3.3:
    //   int  = "0" | ("-"? non-zero-digit digit*)   — no leading zeros, no negative zero
    //   Valid range: [-(2^53-1), 2^53-1]
    private static int readInteger(Cursor c) {
        boolean neg = c.hasMore() && c.peek() == '-';
        if (neg) c.advance();
        if (!c.hasMore() || !Character.isDigit(c.peek()))
            throw new InvalidPathException(
                    "Expected integer at position " + c.pos + " in: " + c.src);
        int start = c.pos;
        while (c.hasMore() && Character.isDigit(c.peek())) c.advance();
        String digits = c.src.substring(start, c.pos);

        // Leading zero: "0" alone is fine, but "01", "00" etc. are not
        if (digits.length() > 1 && digits.charAt(0) == '0')
            throw new InvalidPathException(
                    "Leading zero in index at position " + start + " in: " + c.src);
        // Negative zero is not allowed
        if (neg && "0".equals(digits))
            throw new InvalidPathException(
                    "Negative zero index in: " + c.src);

        final long MAX_SAFE = 9007199254740991L; // 2^53 - 1
        long val;
        try {
            val = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            throw new InvalidPathException(
                    "Index out of range at position " + start + " in: " + c.src);
        }
        if (val > MAX_SAFE)
            throw new InvalidPathException(
                    "Index out of range (exceeds 2^53-1) at position " + start + " in: " + c.src);
        if (neg) val = -val;
        // Clamp to int range for Java array access (valid indices fit trivially)
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, val));
    }

    // RFC 9535 number literal (strict):
    //   number = int frac? exp?
    //   int    = "0" | ("-"? non-zero-digit digit*)   — no leading zeros
    //   frac   = "." 1*digit
    //   exp    = ("e"|"E") ["+"|"-"] 1*digit
    // Rejects: +1  .1  00  01  -.1  1.e1  1e-  1e+-1  1e2e3  1e2.3  etc.
    private static JsonNode parseNumericLiteral(Cursor c) {
        int start = c.pos;
        // optional minus
        if (c.hasMore() && c.peek() == '-') c.advance();
        // integer part
        if (!c.hasMore() || !Character.isDigit(c.peek()))
            throw new InvalidPathException(
                    "Invalid number at position " + start + " in: " + c.src);
        char first = c.peek(); c.advance();
        if (first == '0') {
            // "0" — a following digit means leading zero, which is invalid
            if (c.hasMore() && Character.isDigit(c.peek()))
                throw new InvalidPathException(
                        "Leading zero in number at position " + start + " in: " + c.src);
        } else {
            while (c.hasMore() && Character.isDigit(c.peek())) c.advance();
        }
        boolean isFloat = false;
        // fraction: "." 1*digit
        if (c.hasMore() && c.peek() == '.') {
            isFloat = true; c.advance();
            if (!c.hasMore() || !Character.isDigit(c.peek()))
                throw new InvalidPathException(
                        "Fraction must have at least one digit at position " + c.pos
                        + " in: " + c.src);
            while (c.hasMore() && Character.isDigit(c.peek())) c.advance();
        }
        // exponent: ("e"|"E") [sign] 1*digit
        if (c.hasMore() && (c.peek() == 'e' || c.peek() == 'E')) {
            isFloat = true; c.advance();
            if (c.hasMore() && (c.peek() == '+' || c.peek() == '-')) c.advance();
            if (!c.hasMore() || !Character.isDigit(c.peek()))
                throw new InvalidPathException(
                        "Exponent must have at least one digit at position " + c.pos
                        + " in: " + c.src);
            while (c.hasMore() && Character.isDigit(c.peek())) c.advance();
            // disallow malformed suffix like 1e2.3 or 1e2e3
            if (c.hasMore() && (c.peek() == '.' || c.peek() == 'e' || c.peek() == 'E'))
                throw new InvalidPathException(
                        "Invalid character after exponent at position " + c.pos
                        + " in: " + c.src);
        }
        String raw = c.src.substring(start, c.pos);
        try {
            return isFloat ? NF.numberNode(Double.parseDouble(raw))
                           : NF.numberNode(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            throw new InvalidPathException("Invalid number '" + raw + "' in: " + c.src);
        }
    }

    private static void skipWhitespace(Cursor c) {
        while (c.hasMore() && Character.isWhitespace(c.peek())) c.advance();
    }

    private static void expect(Cursor c, char ch) {
        if (!c.hasMore() || c.peek() != ch)
            throw new InvalidPathException(
                    "Expected '" + ch + "' at position " + c.pos + " in: " + c.src);
        c.advance();
    }

    static final class Cursor {
        final String src;
        int pos;
        Cursor(String src, int pos) { this.src = src; this.pos = pos; }
        boolean hasMore()     { return pos < src.length(); }
        char peek()           { return src.charAt(pos); }
        void advance()        { pos++; }
        void advance(int n)   { pos += n; }
        boolean startsWith(String s) { return src.startsWith(s, pos); }
    }
}
