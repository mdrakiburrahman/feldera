package org.dbsp.sqlCompiler.compiler.frontend.calciteCompiler;

import org.apache.calcite.sql.JoinConditionType;
import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlSpecialOperator;
import org.apache.calcite.sql.SqlStarExclude;
import org.apache.calcite.sql.SqlStarReplace;
import org.apache.calcite.sql.SqlUnresolvedFunction;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.apache.calcite.sql.util.SqlShuttle;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.dbsp.sqlCompiler.compiler.errors.CompilationError;
import org.dbsp.sqlCompiler.compiler.errors.SourcePositionRange;
import org.dbsp.sqlCompiler.compiler.frontend.calciteCompiler.functions.SparkGeneratorRows;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Lowers Spark row generators into correlated UNNESTs before Calcite validation. */
public final class SparkGenerators extends SqlShuttle {
    private static final SqlOperator LATERAL_VIEW = new SqlSpecialOperator("LATERAL VIEW", SqlKind.OTHER) {
        @Override public void unparse(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
            call.operand(0).unparse(writer, leftPrec, 0);
            writer.keyword("LATERAL VIEW");
            if (((SqlLiteral) call.operand(2)).booleanValue())
                writer.keyword("OUTER");
            call.operand(1).unparse(writer, 0, 0);
            if (call.operand(3) != null)
                call.operand(3).unparse(writer, 0, 0);
            SqlNodeList columns = call.operand(4);
            if (!columns.isEmpty()) {
                writer.keyword("AS");
                columns.unparse(writer, 0, 0);
            }
        }
    };

    private static final SqlOperator COLUMN_ALIASES = new SqlSpecialOperator("AS", SqlKind.OTHER) {
        @Override public void unparse(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
            call.operand(0).unparse(writer, leftPrec, 0);
            writer.keyword("AS");
            SqlWriter.Frame frame = writer.startList("(", ")");
            call.operand(1).unparse(writer, 0, 0);
            writer.endList(frame);
        }
    };

    public static SqlCall lateralView(SqlParserPos pos, SqlNode input, SqlNode generator, boolean outer,
                                      @Nullable SqlIdentifier table, List<SqlNode> columns) {
        return LATERAL_VIEW.createCall(pos, input, generator, SqlLiteral.createBoolean(outer, pos), table,
                new SqlNodeList(columns, pos));
    }

    public static SqlCall columnAliases(SqlNode expr, SqlNodeList aliases) {
        if (!isGenerator(expr))
            throw error(expr, "A list of column aliases requires EXPLODE or STACK");
        return COLUMN_ALIASES.createCall(expr.getParserPosition().plus(aliases.getParserPosition()), expr, aliases);
    }

    private static CompilationError error(SqlNode node, String message) {
        return new CompilationError(message, new SourcePositionRange(node.getParserPosition()));
    }

    private static boolean isGenerator(SqlNode node) {
        if (!(node instanceof SqlCall call) || !(call.getOperator() instanceof SqlUnresolvedFunction op))
            return false;
        SqlIdentifier id = op.getNameAsId();
        return id.isSimple() && !id.isComponentQuoted(0) &&
                (id.getSimple().equalsIgnoreCase("EXPLODE") || id.getSimple().equalsIgnoreCase("STACK"));
    }

    private static SqlNode rows(SqlCall generator, boolean outer) {
        if (!isGenerator(generator))
            throw error(generator, "LATERAL VIEW requires EXPLODE or STACK");
        if (generator.getFunctionQuantifier() != null)
            throw error(generator, "EXPLODE and STACK do not accept ALL or DISTINCT");
        boolean explode = generator.getOperator().getName().equalsIgnoreCase("EXPLODE");
        if (explode && generator.operandCount() != 1)
            throw error(generator, "EXPLODE requires one ARRAY or MAP argument");
        if (!explode)
            SparkGeneratorRows.rowCount(generator);
        return (explode ? (outer ? SparkGeneratorRows.EXPLODE_OUTER : SparkGeneratorRows.EXPLODE) : SparkGeneratorRows.STACK)
                .createCall(generator.getParserPosition(), generator.getOperandList());
    }

    private static SqlNode expand(SqlNode input, SqlNode rows, SqlIdentifier table,
                                  SqlNodeList aliases, SqlParserPos pos) {
        List<SqlNode> as = new ArrayList<>();
        SqlNode right = SqlStdOperatorTable.UNNEST.createCall(pos, rows);
        if (aliases.isEmpty()) {
            // Calcite renames a single-column UNNEST to its table alias. A derived
            // table preserves Spark's default col / key,value / col0,... names.
            SqlSelect keepNames = new SqlSelect(pos, null, SqlNodeList.of(SqlIdentifier.star(pos)), right,
                    null, null, null, null, null, null, null, null, null);
            right = SqlStdOperatorTable.LATERAL.createCall(pos, keepNames);
        }
        as.add(right);
        as.add(table);
        as.addAll(aliases.getList());
        return new SqlJoin(pos, input, SqlLiteral.createBoolean(false, pos),
                JoinType.CROSS.symbol(pos), SqlStdOperatorTable.AS.createCall(pos, as),
                JoinConditionType.NONE.symbol(pos), null);
    }

    private final SqlNode root;
    private final Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private int index;

    private SparkGenerators(SqlNode root) {
        this.root = root;
    }

    private SqlIdentifier fresh(SqlParserPos pos) {
        if (this.index == 0) {
            this.root.accept(new SqlBasicVisitor<Void>() {
                @Override public Void visit(SqlIdentifier id) {
                    SparkGenerators.this.names.addAll(id.names);
                    return null;
                }
            });
        }
        String name;
        do {
            name = "$spark$" + this.index++;
        } while (!this.names.add(name));
        return new SqlIdentifier(name, pos);
    }

    public static SqlNode lower(SqlNode node) {
        return Objects.requireNonNull(node.accept(new SparkGenerators(node)));
    }

    @Override public SqlNode visit(SqlCall original) {
        SqlCall call = (SqlCall) Objects.requireNonNull(super.visit(original));
        SqlParserPos pos = call.getParserPosition();
        if (call.getOperator() == LATERAL_VIEW) {
            SqlIdentifier alias = call.operand(3);
            return expand(call.operand(0), rows(call.operand(1), ((SqlLiteral) call.operand(2)).booleanValue()),
                    alias != null ? alias : this.fresh(pos), call.operand(4), pos);
        }
        if (!(call instanceof SqlSelect select))
            return call;
        for (SqlNode item : select.getSelectList()) {
            if (isGenerator(unaliased(item)))
                return this.project(select);
        }
        return select;
    }

    private static SqlNode unaliased(SqlNode item) {
        return item instanceof SqlCall call && (call.getKind() == SqlKind.AS || call.getOperator() == COLUMN_ALIASES)
                ? call.operand(0) : item;
    }

    /** Project the collection and scalar siblings on the input side of the UNNEST. In particular,
     * WHERE, aggregates and windows must see the original input rows, not generated rows. */
    private SqlNode project(SqlSelect select) {
        SqlParserPos pos = select.getParserPosition();
        SqlIdentifier input = this.fresh(pos), generated = this.fresh(pos), collection = this.fresh(pos);
        List<SqlNode> inner = new ArrayList<>(), outer = new ArrayList<>(), hidden = new ArrayList<>();
        SqlNodeList aliases = null;
        int star = -1;
        for (SqlNode item : select.getSelectList()) {
            SqlNode value = unaliased(item);
            SqlParserPos p = item.getParserPosition();
            if (isGenerator(value)) {
                if (aliases != null)
                    throw error(item, "Only one generator is allowed per SELECT; use successive LATERAL VIEW clauses");
                aliases = item instanceof SqlCall as && as.getOperator() == COLUMN_ALIASES ? as.operand(1)
                        : item.getKind() == SqlKind.AS ? SqlNodeList.of(((SqlCall) item).operand(1))
                        : SqlNodeList.EMPTY;
                inner.add(SqlStdOperatorTable.AS.createCall(p, rows((SqlCall) value, false), collection));
                hidden.add(collection);
                outer.add(generated.plusStar());
            } else if ((item instanceof SqlIdentifier id && id.isStar()) || item instanceof SqlStarExclude) {
                SqlIdentifier name = this.fresh(p);
                inner.add(SqlStdOperatorTable.AS.createCall(p, SqlStdOperatorTable.ROW.createCall(p, item), name));
                hidden.add(name);
                outer.add(input.plus(name.getSimple(), p).plusStar());
            } else if (item instanceof SqlStarReplace) {
                if (star >= 0)
                    throw error(item, "Only one * REPLACE is supported alongside a projection generator");
                star = outer.size();
                inner.add(item);
                outer.add(input.plusStar());
            } else {
                SqlIdentifier name = item.getKind() == SqlKind.AS ? ((SqlCall) item).operand(1) : this.fresh(p);
                inner.add(SqlStdOperatorTable.AS.createCall(p, value, name));
                hidden.add(name);
                SqlIdentifier label = item.getKind() == SqlKind.AS ? ((SqlCall) item).operand(1)
                        : new SqlIdentifier(SqlValidatorUtil.alias(item, outer.size()), p);
                outer.add(SqlStdOperatorTable.AS.createCall(p, input.plus(name.getSimple(), p), label));
            }
        }
        if (star >= 0)
            outer.set(star, new SqlStarExclude(pos, input.plusStar(), new SqlNodeList(hidden, pos)));
        SqlSelect values = new SqlSelect(pos, null, new SqlNodeList(inner, pos), select.getFrom(), select.getWhere(),
                select.getGroup(), select.getHaving(), select.getWindowList(), select.getQualify(), null, null,
                null, select.getHints());
        SqlNode from = expand(SqlStdOperatorTable.AS.createCall(pos, values, input),
                input.plus(collection.getSimple(), pos), generated, Objects.requireNonNull(aliases), pos);
        return new SqlSelect(pos, select.operand(0), new SqlNodeList(outer, pos), from, null, null, null,
                null, null, select.getOrderList(), select.getOffset(), select.getFetch(), null, select.getDistinctOn());
    }
}
