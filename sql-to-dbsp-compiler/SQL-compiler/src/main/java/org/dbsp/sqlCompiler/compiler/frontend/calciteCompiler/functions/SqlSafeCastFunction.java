/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dbsp.sqlCompiler.compiler.frontend.calciteCompiler.functions;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlDynamicParam;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperandCountRange;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.SqlOperandCountRanges;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeMappingRule;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.sql.validate.SqlValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;
import static org.apache.calcite.sql.type.SqlTypeUtil.*;
import static org.apache.calcite.sql.type.SqlTypeUtil.isMap;
import static org.apache.calcite.util.Static.RESOURCE;

/** SAFE_CAST preserves nested nullability instead of making all element/key/value types nullable.
 * The Spark CAST marker also preserves nullable string-to-integer results through conversion. */
public class SqlSafeCastFunction extends CustomFunctions.NonOptimizedFunction {
    public static final SqlSafeCastFunction INSTANCE = new SqlSafeCastFunction(false);
    public static final SqlSafeCastFunction SPARK_CAST = new SqlSafeCastFunction(true);

    public static final class SparkIntegerCast extends CustomFunctions.NonOptimizedFunction {
        private SparkIntegerCast(SqlTypeName type) {
            super("$spark_cast_" + type.name(), binding -> binding.getTypeFactory()
                            .createTypeWithNullability(binding.getTypeFactory().createSqlType(type), true),
                    OperandTypes.CHARACTER, SqlFunctionCategory.SYSTEM, "casts", "SparkNumericTests");
        }
    }

    public static final Map<SqlTypeName, SparkIntegerCast> SPARK_INTEGER_CASTS = Map.of(
            SqlTypeName.TINYINT, new SparkIntegerCast(SqlTypeName.TINYINT),
            SqlTypeName.SMALLINT, new SparkIntegerCast(SqlTypeName.SMALLINT),
            SqlTypeName.INTEGER, new SparkIntegerCast(SqlTypeName.INTEGER),
            SqlTypeName.BIGINT, new SparkIntegerCast(SqlTypeName.BIGINT));

    private SqlSafeCastFunction(boolean sparkCast) {
        super(sparkCast ? "$SPARK_CAST" : "SAFE_CAST", sparkCast ? SqlKind.CAST : SqlKind.SAFE_CAST,
                returnTypeInference(sparkCast),
                null, SqlFunctionCategory.SYSTEM, "casts#safe-casts",
                "runtime_aggtest/illarg_tests/test_cast.py");
    }

    @Override
    public String getSignatureTemplate(final int operandsCount) {
        return "{0}({1} AS {2}])";
    }

    @Override
    public SqlOperandCountRange getOperandCountRange() {
        return SqlOperandCountRanges.of(2);
    }

    @Override public SqlSyntax getSyntax() {
        return SqlSyntax.SPECIAL;
    }

    @Override public void unparse(
            SqlWriter writer,
            SqlCall call,
            int leftPrec,
            int rightPrec) {
        assert call.operandCount() <= 3;
        final SqlWriter.Frame frame = writer.startFunCall(getName());
        call.operand(0).unparse(writer, 0, 0);
        writer.sep("AS");
        if (call.operand(1) instanceof SqlIntervalQualifier) {
            writer.sep("INTERVAL");
        }
        call.operand(1).unparse(writer, 0, 0);
        if (call.getOperandList().size() > 2) {
            writer.sep("FORMAT");
            call.operand(2).unparse(writer, 0, 0);
        }
        writer.endFunCall(frame);
    }

    /**
     * Makes sure that the number and types of arguments are allowable.
     * Operators (such as "ROW" and "AS") which do not check their arguments can
     * override this method.
     */
    @Override
    public boolean checkOperandTypes(
            SqlCallBinding callBinding,
            boolean throwOnFailure) {
        final SqlNode left = callBinding.operand(0);
        final SqlNode right = callBinding.operand(1);
        final SqlLiteral format = callBinding.getOperandCount() > 2
                ? (SqlLiteral) callBinding.operand(2) : SqlLiteral.createNull(SqlParserPos.ZERO);

        if (SqlUtil.isNullLiteral(left, false)
                || left instanceof SqlDynamicParam) {
            return true;
        }
        final SqlValidator validator = callBinding.getValidator();
        final RelDataType validatedNodeType =
                validator.getValidatedNodeType(left);
        final RelDataType returnType = SqlTypeUtil.deriveType(callBinding, right);
        final SqlTypeMappingRule mappingRule = validator.getTypeMappingRule();

        if (!SqlTypeUtil.canCastFrom(returnType, validatedNodeType, mappingRule)) {
            if (throwOnFailure) {
                throw callBinding.newError(
                        RESOURCE.cannotCastValue(validatedNodeType.getFullTypeString(),
                                returnType.getFullTypeString()));
            }
            return false;
        }
        if (SqlTypeUtil.areCharacterSetsMismatched(
                validatedNodeType,
                returnType)) {
            if (throwOnFailure) {
                // Include full type string to indicate character
                // set mismatch.
                throw callBinding.newError(
                        RESOURCE.cannotCastValue(validatedNodeType.getFullTypeString(),
                                returnType.getFullTypeString()));
            }
            return false;
        }
        // Validate format argument is string type if included
        return SqlUtil.isNullLiteral(format, false)
                || SqlLiteral.valueMatchesType(format.getValue(), SqlTypeName.CHAR);
    }

    static SqlReturnTypeInference returnTypeInference(boolean sparkCast) {
        return opBinding -> {
            assert opBinding.getOperandCount() <= 3;
            if (sparkCast && !(SqlTypeUtil.isCharacter(opBinding.getOperandType(0)) &&
                    SPARK_INTEGER_CASTS.containsKey(opBinding.getOperandType(1).getSqlTypeName())))
                return SqlStdOperatorTable.CAST.inferReturnType(opBinding);
            final RelDataType ret = deriveType(opBinding.getTypeFactory(), opBinding.getOperandType(0),
                    opBinding.getOperandType(1));

            if (opBinding instanceof SqlCallBinding callBinding) {
                SqlNode operand0 = callBinding.operand(0);
                // dynamic parameters and null constants need their types assigned
                // to them using the type they are cast to.
                if (SqlUtil.isNullLiteral(operand0, false)
                        || operand0 instanceof SqlDynamicParam) {
                    callBinding.getValidator().setValidatedNodeType(operand0, ret);
                }
            }
            return ret;
        };
    }

    public static RelDataType deriveType(RelDataTypeFactory typeFactory,
                                  RelDataType expressionType, RelDataType targetType) {
        return createTypeWithNullabilityFromExpr(typeFactory, expressionType, targetType, true);
    }

    private static RelDataType createTypeWithNullabilityFromExpr(
            RelDataTypeFactory typeFactory,
            RelDataType expressionType, RelDataType targetType, boolean nullable) {
        if (targetType.getSqlTypeName() == SqlTypeName.VARIANT) {
            // A variant can be cast from any other type, and it inherits
            // the nullability of the source.
            // Note that the order of this test and the next one is important.
            return typeFactory.createTypeWithNullability(targetType, expressionType.isNullable());
        }

        if (expressionType.getSqlTypeName() == SqlTypeName.VARIANT) {
            return typeFactory.createTypeWithNullability(targetType, nullable);
        }

        if (isCollection(expressionType)) {
            RelDataType expressionElementType = expressionType.getComponentType();
            RelDataType targetElementType = targetType.getComponentType();
            requireNonNull(expressionElementType, () -> "componentType of " + expressionType);
            requireNonNull(targetElementType, () -> "componentType of " + targetType);
            RelDataType newElementType =
                    createTypeWithNullabilityFromExpr(typeFactory, expressionElementType, targetElementType, true);
            return isArray(targetType)
                    ? SqlTypeUtil.createArrayType(typeFactory, newElementType, nullable)
                    : SqlTypeUtil.createMultisetType(typeFactory, newElementType, nullable);
        }

        if (isRow(expressionType)) {
            final int fieldCount = expressionType.getFieldCount();
            final List<RelDataType> typeList = new ArrayList<>(fieldCount);
            for (int i = 0; i < fieldCount; ++i) {
                RelDataType expressionElementType = expressionType.getFieldList().get(i).getType();
                RelDataType targetElementType = targetType.getFieldList().get(i).getType();
                typeList.add(createTypeWithNullabilityFromExpr(typeFactory, expressionElementType, targetElementType, false));
            }
            return typeFactory.createTypeWithNullability(
                    typeFactory.createStructType(typeList, targetType.getFieldNames()), nullable);
        }

        if (isMap(expressionType)) {
            RelDataType expressionKeyType =
                    requireNonNull(expressionType.getKeyType(), () -> "keyType of " + expressionType);
            RelDataType expressionValueType =
                    requireNonNull(expressionType.getValueType(), () -> "valueType of " + expressionType);
            RelDataType targetKeyType =
                    requireNonNull(targetType.getKeyType(), () -> "keyType of " + targetType);
            RelDataType targetValueType =
                    requireNonNull(targetType.getValueType(), () -> "valueType of " + targetType);

            RelDataType keyType =
                    createTypeWithNullabilityFromExpr(typeFactory, expressionKeyType, targetKeyType, false);
            RelDataType valueType =
                    createTypeWithNullabilityFromExpr(typeFactory, expressionValueType, targetValueType, true);
            return SqlTypeUtil.createMapType(typeFactory, keyType, valueType, nullable);
        }

        return typeFactory.createTypeWithNullability(targetType, nullable);
    }
}
