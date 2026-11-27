package org.dbsp.sqlCompiler.compiler.frontend.calciteCompiler.functions;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Keeps Spark arithmetic out of Calcite's SQL-standard constant evaluator. */
public final class SparkArithmeticFunction extends CustomFunctions.NonOptimizedFunction {
    public final SqlKind operation;

    private SparkArithmeticFunction(SqlOperator operator) {
        super("$spark_" + operator.kind.name(), SqlKind.OTHER_FUNCTION,
                binding -> inferType(operator, binding), operator.getOperandTypeChecker(),
                SqlFunctionCategory.SYSTEM, "operators", "SparkNumericTests");
        this.operation = operator.kind;
    }

    private static RelDataType inferType(SqlOperator operator, SqlOperatorBinding binding) {
        var factory = binding.getTypeFactory();
        if (operator.kind == SqlKind.DIVIDE &&
                (SqlTypeUtil.isIntType(binding.getOperandType(0)) || binding.getOperandType(0).getSqlTypeName() == SqlTypeName.NULL) &&
                (SqlTypeUtil.isIntType(binding.getOperandType(1)) || binding.getOperandType(1).getSqlTypeName() == SqlTypeName.NULL)) {
            return factory.createTypeWithNullability(factory.createSqlType(SqlTypeName.DOUBLE), true);
        }
        RelDataType result = Objects.requireNonNull(operator.inferReturnType(binding));
        if (SqlTypeUtil.isDecimal(result) ||
                operator.kind == SqlKind.DIVIDE && SqlTypeUtil.isApproximateNumeric(result))
            return factory.createTypeWithNullability(result, true);
        return result;
    }

    private static final Map<SqlOperator, SparkArithmeticFunction> OPERATORS = Map.of(
            SqlStdOperatorTable.PLUS, new SparkArithmeticFunction(SqlStdOperatorTable.PLUS),
            SqlStdOperatorTable.MINUS, new SparkArithmeticFunction(SqlStdOperatorTable.MINUS),
            SqlStdOperatorTable.MULTIPLY, new SparkArithmeticFunction(SqlStdOperatorTable.MULTIPLY),
            SqlStdOperatorTable.DIVIDE, new SparkArithmeticFunction(SqlStdOperatorTable.DIVIDE),
            SqlStdOperatorTable.MOD, new SparkArithmeticFunction(SqlStdOperatorTable.MOD));

    public static @Nullable SparkArithmeticFunction replacement(SqlOperator operator) {
        return OPERATORS.get(operator);
    }

    public static List<SparkArithmeticFunction> functions() {
        return List.copyOf(OPERATORS.values());
    }
}
