package org.dbsp.sqlCompiler.compiler.frontend.calciteCompiler;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlCharStringLiteral;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlIntervalLiteral;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlNumericLiteral;
import org.apache.calcite.sql.SqlSelectKeyword;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.util.SqlShuttle;
import org.dbsp.sqlCompiler.compiler.errors.CompilationError;
import org.dbsp.sqlCompiler.compiler.errors.InternalCompilerError;
import org.dbsp.sqlCompiler.compiler.errors.SourcePositionRange;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static java.util.Map.entry;

/** Rewrites Spark SQL functions that Feldera does not define into equivalent Feldera SQL before validation.
 * Names declared by the program as user-defined functions are left alone. */
public final class SparkFunctions extends SqlShuttle {
    private static final String ARG = "spark_arg";

    private static final Map<String, String> TRUNC_UNITS = Map.ofEntries(
            entry("YEAR", "YEAR"), entry("YYYY", "YEAR"), entry("YY", "YEAR"),
            entry("QUARTER", "QUARTER"),
            entry("MONTH", "MONTH"), entry("MM", "MONTH"), entry("MON", "MONTH"),
            entry("WEEK", "WEEK"),
            entry("DAY", "DAY"), entry("DD", "DAY"),
            entry("HOUR", "HOUR"), entry("MINUTE", "MINUTE"), entry("SECOND", "SECOND"),
            entry("MILLISECOND", "MILLISECOND"), entry("MICROSECOND", "MICROSECOND"));

    private static final Map<String, String> PART_UNITS = Map.ofEntries(
            entry("YEAR", "YEAR"), entry("Y", "YEAR"), entry("YEARS", "YEAR"), entry("YR", "YEAR"), entry("YRS", "YEAR"),
            entry("QUARTER", "QUARTER"), entry("QTR", "QUARTER"),
            entry("MONTH", "MONTH"), entry("MON", "MONTH"), entry("MONS", "MONTH"), entry("MONTHS", "MONTH"),
            entry("WEEK", "WEEK"), entry("W", "WEEK"), entry("WEEKS", "WEEK"),
            entry("DAY", "DAY"), entry("D", "DAY"), entry("DAYS", "DAY"),
            entry("DAYOFWEEK", "DOW"), entry("DOW", "DOW"), entry("DOY", "DOY"),
            entry("HOUR", "HOUR"), entry("H", "HOUR"), entry("HOURS", "HOUR"), entry("HR", "HOUR"), entry("HRS", "HOUR"),
            entry("MINUTE", "MINUTE"), entry("M", "MINUTE"), entry("MIN", "MINUTE"), entry("MINS", "MINUTE"),
            entry("MINUTES", "MINUTE"));

    private final SqlParser.Config config;
    private final Predicate<String> isUserDefined;
    private final UnaryOperator<SqlNode> postParse;

    private SparkFunctions(SqlParser.Config config, Predicate<String> isUserDefined, UnaryOperator<SqlNode> postParse) {
        this.config = config;
        this.isUserDefined = isUserDefined;
        this.postParse = postParse;
    }

    /** @param postParse Applied to each parsed template, as the compiler does for parsed programs. */
    public static SqlNode rewrite(SqlNode node, SqlParser.Config config, Predicate<String> isUserDefined,
                                  UnaryOperator<SqlNode> postParse) {
        SqlNode result = node.accept(new SparkFunctions(config, isUserDefined, postParse));
        return result == null ? node : result;
    }

    private static CompilationError error(SqlNode node, String message) {
        return new CompilationError(message, new SourcePositionRange(node.getParserPosition()));
    }

    @Override
    public @Nullable SqlNode visit(SqlCall call) {
        SqlNode visited = super.visit(call);
        if (!(visited instanceof SqlCall c) || !(c.getOperator() instanceof SqlFunction))
            return visited;
        String name = c.getOperator().getName().toUpperCase(Locale.ROOT);
        if (this.isUserDefined.test(name))
            return c;
        SqlNode result = this.rewrite(c, name);
        return result == null ? c : result;
    }

    @Nullable
    private SqlNode rewrite(SqlCall c, String name) {
        List<SqlNode> a = c.getOperandList();
        int n = a.size();
        boolean distinct = c.getFunctionQuantifier() != null &&
                c.getFunctionQuantifier().getValue() == SqlSelectKeyword.DISTINCT;
        return switch (name) {
            case "MAX_BY" -> n == 2 ? this.template(c, "ARG_MAX(spark_arg0, spark_arg1)", a) : null;
            case "MIN_BY" -> n == 2 ? this.template(c, "ARG_MIN(spark_arg0, spark_arg1)", a) : null;
            case "COLLECT_LIST", "COLLECT_SET" -> n != 1 ? null : this.template(c,
                    distinct || name.equals("COLLECT_SET") ?
                            "ARRAY_AGG(DISTINCT spark_arg0 IGNORE NULLS)" : "ARRAY_AGG(spark_arg0 IGNORE NULLS)", a);
            case "COUNT_IF" -> n == 1 ? this.template(c, "COUNT(CASE WHEN spark_arg0 THEN 1 END)", a) : null;
            case "SIZE" -> n == 1 ? this.template(c, "COALESCE(CARDINALITY(spark_arg0), -1)", a) : null;
            case "ELEMENT_AT" -> n != 2 ? null : this.template(c, isNegativeLiteral(a.get(1)) ?
                    "spark_arg0[CARDINALITY(spark_arg0) + 1 + spark_arg1]" : "spark_arg0[spark_arg1]", a);
            case "INSTR" -> n == 2 ? this.template(c, "POSITION(spark_arg1 IN spark_arg0)", a) : null;
            case "SUBSTRING_INDEX" -> n != 3 ? null : this.template(c, """
                    CASE WHEN spark_arg0 IS NULL OR spark_arg1 IS NULL OR spark_arg2 IS NULL THEN CAST(NULL AS VARCHAR)
                    WHEN spark_arg2 = 0 OR spark_arg1 = '' THEN CAST('' AS VARCHAR)
                    WHEN spark_arg2 > 0 THEN
                      ARRAY_TO_STRING(ARRAY_SLICE(SPLIT(spark_arg0, spark_arg1), 1, spark_arg2), spark_arg1)
                    ELSE ARRAY_TO_STRING(ARRAY_SLICE(SPLIT(spark_arg0, spark_arg1),
                      GREATEST(CARDINALITY(SPLIT(spark_arg0, spark_arg1)) + spark_arg2 + 1, 1), -spark_arg2),
                      spark_arg1) END""", a);
            case "DAY" -> n == 1 ? this.template(c, "EXTRACT(DAY FROM spark_arg0)", a) : null;
            case "WEEKOFYEAR" -> n == 1 ? this.template(c, "EXTRACT(WEEK FROM spark_arg0)", a) : null;
            case "CURRENT_DATE" -> n == 0 ? this.template(c, "CAST(NOW() AS DATE)", a) : null;
            case "CURRENT_TIMESTAMP" -> n == 0 ? this.template(c, "NOW()", a) : null;
            case "DATE_ADD", "DATE_SUB", "DATEADD" -> {
                if (n != 2)
                    yield null;
                SqlNode start = identifier(a.get(0));
                if (start == null)
                    yield null;
                String sign = name.equals("DATE_SUB") ? "-" : "+";
                yield this.template(c, isInterval(a.get(1)) ? "spark_arg0 " + sign + " spark_arg1" :
                        "TIMESTAMPADD(DAY, " + sign + "(spark_arg1), CAST(spark_arg0 AS DATE))",
                        List.of(start, a.get(1)));
            }
            case "ADD_MONTHS" -> n == 2 ?
                    this.template(c, "TIMESTAMPADD(MONTH, spark_arg1, CAST(spark_arg0 AS DATE))", a) : null;
            case "LAST_DAY" -> n == 1 ? this.template(c, lastDay("spark_arg0"), a) : null;
            case "DATEDIFF" -> {
                if (n != 2)
                    yield null;
                SqlNode end = identifier(a.get(0));
                yield end == null ? null : this.template(c,
                        "DATEDIFF(DAY, CAST(spark_arg1 AS DATE), CAST(spark_arg0 AS DATE))", List.of(end, a.get(1)));
            }
            case "DATE_TRUNC" -> {
                if (n != 2 || !(a.get(0) instanceof SqlCharStringLiteral unit))
                    yield null;
                String u = TRUNC_UNITS.get(unit.getValueAs(String.class).toUpperCase(Locale.ROOT));
                if (u == null)
                    throw error(unit, "Unsupported DATE_TRUNC unit " + unit);
                if (u.equals("WEEK"))
                    yield this.template(c, "CAST(TIMESTAMPADD(DAY, 1 - EXTRACT(ISODOW FROM spark_arg1), " +
                            "CAST(spark_arg1 AS DATE)) AS TIMESTAMP)", a.subList(0, 2));
                String function = List.of("YEAR", "QUARTER", "MONTH", "DAY").contains(u) ?
                        "DATE_TRUNC" : "TIMESTAMP_TRUNC";
                yield this.template(c, function + "(CAST(spark_arg1 AS TIMESTAMP), " + u + ")", a);
            }
            case "DATE_PART" -> {
                if (n != 2 || !(a.get(0) instanceof SqlCharStringLiteral unit))
                    yield null;
                String u = PART_UNITS.get(unit.getValueAs(String.class).toUpperCase(Locale.ROOT));
                if (u == null)
                    throw error(unit, "Unsupported DATE_PART field " + unit);
                yield this.template(c, "EXTRACT(" + u + " FROM spark_arg1)", a);
            }
            case "MONTHS_BETWEEN" -> n == 2 || n == 3 ? this.template(c, monthsBetween(n == 3), a) : null;
            case "TO_DATE" -> n == 1 ? this.template(c, "SAFE_CAST(spark_arg0 AS DATE)", a) :
                    n == 2 ? this.parse(c, a.get(0), a.get(1), true) : null;
            case "TO_TIMESTAMP" -> n == 1 ? this.template(c, "SAFE_CAST(spark_arg0 AS TIMESTAMP)", a) :
                    n == 2 ? this.parse(c, a.get(0), a.get(1), false) : null;
            case "DATE_FORMAT" -> n != 2 ? null : this.template(c,
                    "FORMAT_TIMESTAMP(spark_arg1, CAST(spark_arg0 AS TIMESTAMP))",
                    List.of(a.get(0), this.format(a.get(1))));
            case "UNIX_TIMESTAMP" -> switch (n) {
                case 0 -> this.template(c, "EXTRACT(EPOCH FROM NOW())", a);
                case 1 -> this.template(c, "EXTRACT(EPOCH FROM SAFE_CAST(spark_arg0 AS TIMESTAMP))", a);
                case 2 -> this.template(c, "EXTRACT(EPOCH FROM spark_arg0)",
                        List.of(this.parse(c, a.get(0), a.get(1), false)));
                default -> null;
            };
            default -> null;
        };
    }

    private static String lastDay(String date) {
        return "DATE_TRUNC(CAST(" + date + " AS DATE), MONTH) + INTERVAL '1' MONTH - INTERVAL '1' DAY";
    }

    /** Spark's months_between: whole months when the days of month match or both are month ends,
     * otherwise the remainder counts in 31-day months, rounded to 8 digits unless roundOff is false. */
    private static String monthsBetween(boolean roundOff) {
        String a = "CAST(spark_arg0 AS TIMESTAMP)";
        String b = "CAST(spark_arg1 AS TIMESTAMP)";
        String months = "CAST((EXTRACT(YEAR FROM " + a + ") - EXTRACT(YEAR FROM " + b + ")) * 12 + EXTRACT(MONTH FROM " +
                a + ") - EXTRACT(MONTH FROM " + b + ") AS DOUBLE)";
        String seconds = "CAST((EXTRACT(DAY FROM " + a + ") - EXTRACT(DAY FROM " + b + ")) * 86400 + " +
                "(EXTRACT(EPOCH FROM " + a + ") - EXTRACT(EPOCH FROM CAST(CAST(" + a + " AS DATE) AS TIMESTAMP))) - " +
                "(EXTRACT(EPOCH FROM " + b + ") - EXTRACT(EPOCH FROM CAST(CAST(" + b + " AS DATE) AS TIMESTAMP))) AS DOUBLE)";
        String fraction = months + " + " + seconds + " / CAST(2678400 AS DOUBLE)";
        String rounded = roundOff ? "CASE WHEN spark_arg2 THEN ROUND(" + fraction + ", 8) ELSE " + fraction + " END" :
                "ROUND(" + fraction + ", 8)";
        return "CASE WHEN EXTRACT(DAY FROM " + a + ") = EXTRACT(DAY FROM " + b + ") OR (CAST(" + a + " AS DATE) = " +
                lastDay(a) + " AND CAST(" + b + " AS DATE) = " + lastDay(b) + ") THEN " + months +
                " ELSE " + rounded + " END";
    }

    /** TO_DATE / TO_TIMESTAMP with a Spark datetime pattern. */
    private SqlNode parse(SqlCall c, SqlNode value, SqlNode pattern, boolean date) {
        SqlLiteral format = this.format(pattern);
        boolean hasTime = format.getValueAs(String.class).matches(".*%-?[HIMS].*");
        String parse = hasTime ? "PARSE_TIMESTAMP(spark_arg1, spark_arg0)" : "PARSE_DATE(spark_arg1, spark_arg0)";
        String cast = date == hasTime ? "CAST(" + parse + " AS " + (date ? "DATE" : "TIMESTAMP") + ")" : parse;
        return this.template(c, cast, List.of(value, format));
    }

    private SqlLiteral format(SqlNode pattern) {
        if (!(pattern instanceof SqlCharStringLiteral literal))
            throw error(pattern, "Spark datetime patterns must be string literals");
        return SqlLiteral.createCharString(strftime(literal, literal.getValueAs(String.class)),
                pattern.getParserPosition());
    }

    /** Converts a Spark datetime pattern into a chrono strftime format. */
    static String strftime(SqlNode node, String pattern) {
        StringBuilder out = new StringBuilder();
        int length = pattern.length();
        for (int i = 0; i < length; ) {
            char ch = pattern.charAt(i);
            if (ch == '\'') {
                int j = i + 1;
                if (j < length && pattern.charAt(j) == '\'') {
                    out.append('\'');
                    i = j + 1;
                    continue;
                }
                while (j < length) {
                    char q = pattern.charAt(j);
                    if (q == '\'') {
                        if (j + 1 < length && pattern.charAt(j + 1) == '\'') {
                            out.append('\'');
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    out.append(q == '%' ? "%%" : String.valueOf(q));
                    j++;
                }
                i = j + 1;
                continue;
            }
            if (!Character.isLetter(ch)) {
                out.append(ch == '%' ? "%%" : String.valueOf(ch));
                i++;
                continue;
            }
            int j = i;
            while (j < length && pattern.charAt(j) == ch)
                j++;
            int count = j - i;
            String spec = switch (ch) {
                case 'y' -> count == 2 ? "%y" : "%Y";
                case 'M', 'L' -> count == 1 ? "%-m" : count == 2 ? "%m" : count == 3 ? "%b" : "%B";
                case 'd' -> count == 1 ? "%-d" : "%d";
                case 'D' -> "%j";
                case 'H' -> count == 1 ? "%-H" : "%H";
                case 'h' -> count == 1 ? "%-I" : "%I";
                case 'm' -> count == 1 ? "%-M" : "%M";
                case 's' -> count == 1 ? "%-S" : "%S";
                case 'S' -> count == 3 ? "%3f" : count == 6 ? "%6f" : count == 9 ? "%9f" : null;
                case 'a' -> "%p";
                case 'E' -> count <= 3 ? "%a" : "%A";
                default -> null;
            };
            if (spec == null)
                throw error(node, "Unsupported Spark datetime pattern '" + pattern.substring(i, j) + "'");
            out.append(spec);
            i = j;
        }
        return out.toString();
    }

    /** DATEDIFF and DATEADD parse a leading identifier as a time unit. */
    @Nullable
    private static SqlNode identifier(SqlNode node) {
        if (!(node instanceof SqlIntervalQualifier qualifier))
            return node;
        return qualifier.timeFrameName == null ? null :
                new SqlIdentifier(qualifier.timeFrameName, qualifier.getParserPosition());
    }

    private static boolean isInterval(SqlNode node) {
        if (node instanceof SqlIntervalLiteral || node instanceof SqlIntervalQualifier)
            return true;
        return node instanceof SqlCall call && call.getOperandList().stream()
                .anyMatch(o -> o != null && isInterval(o));
    }

    private static boolean isNegativeLiteral(SqlNode node) {
        if (node instanceof SqlNumericLiteral literal)
            return literal.getValueAs(BigDecimal.class).signum() < 0;
        return node.getKind() == SqlKind.MINUS_PREFIX && ((SqlCall) node).operand(0) instanceof SqlNumericLiteral;
    }

    private SqlNode template(SqlCall call, String sql, List<SqlNode> args) {
        SqlNode parsed;
        try {
            parsed = this.postParse.apply(SqlParser.create(sql, this.config).parseExpression());
        } catch (SqlParseException e) {
            throw new InternalCompilerError("Cannot parse Spark function template " + sql + ": " + e.getMessage());
        }
        return instantiate(parsed, args, new int[args.size()], call.getParserPosition());
    }

    private static SqlNode instantiate(SqlNode node, List<SqlNode> args, int[] uses, SqlParserPos pos) {
        if (node instanceof SqlIdentifier id && id.isSimple() &&
                id.getSimple().toLowerCase(Locale.ROOT).startsWith(ARG)) {
            int index = Integer.parseInt(id.getSimple().substring(ARG.length()));
            return uses[index]++ == 0 ? args.get(index) : copy(args.get(index));
        }
        if (node instanceof SqlNodeList list) {
            List<SqlNode> items = new ArrayList<>();
            for (SqlNode item : list)
                items.add(item == null ? null : instantiate(item, args, uses, pos));
            return new SqlNodeList(items, pos);
        }
        if (node instanceof SqlCall call) {
            List<SqlNode> operands = new ArrayList<>();
            for (SqlNode operand : call.getOperandList())
                operands.add(operand == null ? null : instantiate(operand, args, uses, pos));
            return call.getOperator().createCall(call.getFunctionQuantifier(), pos, operands.toArray(new SqlNode[0]));
        }
        return node.clone(pos);
    }

    private static SqlNode copy(SqlNode node) {
        if (node instanceof SqlNodeList list) {
            List<SqlNode> items = new ArrayList<>();
            for (SqlNode item : list)
                items.add(item == null ? null : copy(item));
            return new SqlNodeList(items, list.getParserPosition());
        }
        if (node instanceof SqlCall call) {
            List<SqlNode> operands = new ArrayList<>();
            for (SqlNode operand : call.getOperandList())
                operands.add(operand == null ? null : copy(operand));
            return call.getOperator().createCall(call.getFunctionQuantifier(), call.getParserPosition(),
                    operands.toArray(new SqlNode[0]));
        }
        return node.clone(node.getParserPosition());
    }
}
