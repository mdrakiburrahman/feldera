package org.dbsp.sqlCompiler.compiler.frontend.calciteCompiler.functions;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlInternalOperator;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNumericLiteral;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.fun.SqlLibraryOperators;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.SqlOperandTypeChecker;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.sql2rel.SqlRexContext;
import org.dbsp.sqlCompiler.compiler.errors.CompilationError;
import org.dbsp.sqlCompiler.compiler.errors.SourcePositionRange;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Internal collection expressions that feed UNNEST; these are not SQL scalar functions. */
public final class SparkGeneratorRows extends SqlInternalOperator {
    public static final SqlOperator EXPLODE = new SparkGeneratorRows("$EXPLODE_ROWS",
            binding -> explodeType(binding, false), OperandTypes.ARRAY_OR_MAP);
    public static final SqlOperator EXPLODE_OUTER = new SparkGeneratorRows("$EXPLODE_OUTER_ROWS",
            binding -> explodeType(binding, true), OperandTypes.ARRAY_OR_MAP);
    public static final SqlOperator STACK = new SparkGeneratorRows("$STACK_ROWS",
            SparkGeneratorRows::stackType, OperandTypes.VARIADIC);

    private SparkGeneratorRows(String name, SqlReturnTypeInference result, SqlOperandTypeChecker arguments) {
        super(name, SqlKind.OTHER_FUNCTION, 100, true, result, null, arguments);
    }

    @Override public SqlSyntax getSyntax() {
        return SqlSyntax.FUNCTION;
    }

    private static RelDataType explodeType(SqlOperatorBinding binding, boolean outer) {
        RelDataType type = binding.getOperandType(0);
        RelDataTypeFactory factory = binding.getTypeFactory();
        RelDataTypeFactory.Builder row = factory.builder();
        if (type.getSqlTypeName() == SqlTypeName.MAP) {
            row.add("key", Objects.requireNonNull(type.getKeyType()));
            row.add("value", Objects.requireNonNull(type.getValueType()));
        } else if (type.getSqlTypeName() == SqlTypeName.ARRAY) {
            // UNNEST flattens record elements. An envelope keeps each element in one column.
            row.add("col", Objects.requireNonNull(type.getComponentType()));
        } else {
            throw error(((SqlCallBinding) binding).operand(0), "EXPLODE requires an ARRAY or MAP, not " + type);
        }
        // Pad before UNNEST: pushing a projection between a left correlate and UNNEST
        // otherwise makes the plan unsupported (notably for arrays of structs).
        return factory.createArrayType(factory.enforceTypeWithNullability(row.build(), outer), -1);
    }

    public static int rowCount(SqlCall call) {
        if (call.operandCount() < 2)
            throw error(call, "STACK requires a row count followed by at least one value");
        SqlNode count = call.operand(0);
        if (!(count instanceof SqlNumericLiteral n) || !n.isExact() || !n.isInteger() || n.intValue(true) <= 0)
            throw error(count, "STACK row count must be a positive integer literal");
        return n.intValue(true);
    }

    private static CompilationError error(SqlNode node, String message) {
        return new CompilationError(message, new SourcePositionRange(node.getParserPosition()));
    }

    private static RelDataType stackType(SqlOperatorBinding binding) {
        SqlCall call = ((SqlCallBinding) binding).getCall();
        int height = rowCount(call), size = call.operandCount() - 1;
        int width = 1 + (size - 1) / height;
        RelDataTypeFactory factory = binding.getTypeFactory();
        RelDataTypeFactory.Builder row = factory.builder();
        for (int c = 0; c < width; c++) {
            List<RelDataType> types = new ArrayList<>();
            RelDataType first = null;
            for (int i = c + 1; i <= size; i += width) {
                RelDataType type = binding.getOperandType(i);
                if (type.getSqlTypeName() != SqlTypeName.NULL) {
                    if (first != null && !SqlTypeUtil.equalSansNullability(factory, first, type)
                            && !(SqlTypeUtil.isCharacter(first) && SqlTypeUtil.isCharacter(type)))
                        throw error(call.operand(i), "STACK column " + c + " has incompatible types "
                                + first + " and " + type);
                    first = type;
                }
                types.add(type);
            }
            RelDataType type = Objects.requireNonNull(factory.leastRestrictive(types));
            if (types.size() < height)
                type = factory.enforceTypeWithNullability(type, true);
            row.add("col" + c, type);
        }
        return factory.createArrayType(row.build(), -1);
    }

    public static RexNode convertStack(SqlRexContext cx, SqlCall call) {
        RexBuilder rex = cx.getRexBuilder();
        SqlParserPos pos = call.getParserPosition();
        RelDataType array = cx.getValidator().getValidatedNodeType(call);
        RelDataType row = Objects.requireNonNull(array.getComponentType());
        int height = rowCount(call), width = row.getFieldCount();
        List<RexNode> rows = new ArrayList<>();
        for (int i = 1; i < call.operandCount(); i += width) {
            List<RexNode> fields = new ArrayList<>();
            for (int col = 0; col < width; col++) {
                RelDataType type = row.getFieldList().get(col).getType();
                fields.add(i + col < call.operandCount()
                        ? rex.ensureType(pos, type, cx.convertExpression(call.operand(i + col)), true)
                        : rex.makeNullLiteral(type));
            }
            rows.add(rex.makeCall(pos, row, SqlStdOperatorTable.ROW, fields));
        }
        RexNode values = rex.makeCall(pos, array, SqlStdOperatorTable.ARRAY_VALUE_CONSTRUCTOR, rows);
        if (rows.size() == height)
            return values;
        // A large row-count literal must not manufacture that many compiler AST nodes.
        List<RexNode> nulls = row.getFieldList().stream().map(f -> (RexNode) rex.makeNullLiteral(f.getType())).toList();
        RexNode blank = rex.makeCall(pos, row, SqlStdOperatorTable.ROW, nulls);
        RexNode missing = rex.makeExactLiteral(BigDecimal.valueOf(height - rows.size()));
        RexNode padding = rex.makeCall(pos, array, SqlLibraryOperators.ARRAY_REPEAT, List.of(blank, missing));
        return rex.makeCall(pos, array, SqlLibraryOperators.ARRAY_CONCAT, List.of(values, padding));
    }
}
