package org.dbsp.sqlCompiler.compiler.frontend.calciteCompiler.functions;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlOperandTypeChecker;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeTransforms;
import org.dbsp.sqlCompiler.compiler.errors.CompilationError;
import org.dbsp.sqlCompiler.compiler.frontend.calciteObject.CalciteObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

class SparkJsonFunctions extends CustomFunctions.NonOptimizedFunction {
    private SparkJsonFunctions(String name, SqlReturnTypeInference inference, SqlOperandTypeChecker operands,
                               SqlFunctionCategory category) {
        super(name, inference, operands, category, "json#" + name.toLowerCase(Locale.ROOT), FunctionDocumentation.NO_FILE);
    }

    static final SparkJsonFunctions FROM_JSON = new SparkJsonFunctions(
            "FROM_JSON", b -> new Schema(b).parse(), OperandTypes.STRING_STRING,
            SqlFunctionCategory.STRING);
    static final SparkJsonFunctions NAMED_STRUCT = new SparkJsonFunctions(
            "NAMED_STRUCT", SparkJsonFunctions::namedStruct, OperandTypes.VARIADIC,
            SqlFunctionCategory.SYSTEM);
    static final SparkJsonFunctions GET_JSON_OBJECT = new SparkJsonFunctions(
            "GET_JSON_OBJECT", ReturnTypes.VARCHAR.andThen(SqlTypeTransforms.FORCE_NULLABLE), OperandTypes.STRING_STRING,
            SqlFunctionCategory.STRING);

    private static CompilationError error(SqlOperatorBinding binding, String message) {
        return new CompilationError(binding.getOperator().getName() + ": " + message,
                binding instanceof SqlCallBinding c ? CalciteObject.create(c.getCall()) : CalciteObject.EMPTY);
    }

    private static String literal(SqlOperatorBinding binding, int index, String what) {
        if (binding.isOperandLiteral(index, false) &&
                SqlTypeName.CHAR_TYPES.contains(binding.getOperandType(index).getSqlTypeName())) {
            String s = binding.getOperandLiteralValue(index, String.class);
            if (s != null)
                return s;
        }
        throw error(binding, what + " must be a non-null string literal");
    }

    private static void checkFieldName(SqlOperatorBinding binding, Set<String> seen, String name) {
        if (!seen.add(name.toLowerCase(Locale.ROOT)))
            throw error(binding, "Duplicate or ambiguous field name: " + name);
    }

    private static RelDataType namedStruct(SqlOperatorBinding binding) {
        int n = binding.getOperandCount();
        if (n == 0 || n % 2 != 0)
            throw error(binding, "Expected one or more field name/value pairs");
        var result = binding.getTypeFactory().builder();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < n; i += 2) {
            String name = literal(binding, i, "Field name");
            checkFieldName(binding, seen, name);
            result.add(name, binding.getOperandType(i + 1));
        }
        return result.build();
    }

    /** Spark DDL, not SQL source: preserve the spelling of JSON keys independently of SQL casing. */
    private static class Schema {
        final SqlOperatorBinding binding;
        final RelDataTypeFactory factory;
        final String text;
        int pos;

        Schema(SqlOperatorBinding binding) {
            this.binding = binding;
            this.factory = binding.getTypeFactory();
            this.text = literal(binding, 1, "Schema");
        }

        void space() {
            while (this.pos < this.text.length() && Character.isWhitespace(this.text.charAt(this.pos)))
                this.pos++;
        }

        boolean take(char c) {
            this.space();
            if (this.pos >= this.text.length() || this.text.charAt(this.pos) != c)
                return false;
            this.pos++;
            return true;
        }

        void expect(char c) {
            if (!this.take(c))
                throw error(this.binding, "Expected '" + c + "' at schema offset " + this.pos);
        }

        String word() {
            this.space();
            int start = this.pos;
            while (this.pos < this.text.length()) {
                char c = this.text.charAt(this.pos);
                if (!Character.isLetterOrDigit(c) && c != '_')
                    break;
                this.pos++;
            }
            if (start == this.pos)
                throw error(this.binding, "Expected a name at schema offset " + start);
            return this.text.substring(start, this.pos);
        }

        String fieldName() {
            if (!this.take('`'))
                return this.word();
            int start = this.pos - 1;
            StringBuilder name = new StringBuilder();
            while (this.pos < this.text.length()) {
                char c = this.text.charAt(this.pos++);
                if (c == '`') {
                    if (this.pos == this.text.length() || this.text.charAt(this.pos) != '`')
                        return name.toString();
                    this.pos++;
                }
                name.append(c);
            }
            throw error(this.binding, "Unterminated quoted field name at schema offset " + start);
        }

        RelDataType nullable(RelDataType type) {
            return this.factory.createTypeWithNullability(type, true);
        }

        RelDataType fields(boolean nested, int depth) {
            var builder = this.factory.builder();
            Set<String> seen = new HashSet<>();
            do {
                String name = this.fieldName();
                if (depth == 0 && name.equals("_corrupt_record"))
                    throw error(this.binding, "Corrupt-record capture is not supported");
                checkFieldName(this.binding, seen, name);
                if (nested)
                    this.expect(':');
                else
                    this.take(':');
                builder.add(name, this.type(depth + 1));
            } while (this.take(','));
            return this.nullable(builder.build());
        }

        RelDataType type(int depth) {
            if (depth > 64)
                throw error(this.binding, "Schema nesting exceeds 64 levels");
            String name = this.word().toUpperCase(Locale.ROOT);
            if (name.equals("STRUCT") || name.equals("ARRAY") || name.equals("MAP")) {
                this.expect('<');
                RelDataType type;
                if (name.equals("STRUCT")) {
                    type = this.fields(true, depth);
                } else if (name.equals("MAP")) {
                    if (!this.word().equalsIgnoreCase("string"))
                        throw error(this.binding, "Only STRING keys are supported in MAP schemas");
                    this.expect(',');
                    type = this.factory.createMapType(this.factory.createSqlType(SqlTypeName.VARCHAR), this.type(depth + 1));
                } else {
                    type = this.factory.createArrayType(this.type(depth + 1), -1);
                }
                this.expect('>');
                return this.nullable(type);
            }
            SqlTypeName scalar = switch (name) {
                case "STRING" -> SqlTypeName.VARCHAR;
                case "BOOLEAN" -> SqlTypeName.BOOLEAN;
                case "BYTE", "TINYINT" -> SqlTypeName.TINYINT;
                case "SHORT", "SMALLINT" -> SqlTypeName.SMALLINT;
                case "INT", "INTEGER" -> SqlTypeName.INTEGER;
                case "LONG", "BIGINT" -> SqlTypeName.BIGINT;
                default -> throw error(this.binding, "Unsupported schema type: " + name);
            };
            return this.nullable(this.factory.createSqlType(scalar));
        }

        RelDataType parse() {
            this.fieldName();
            boolean complex = this.take('<');
            this.pos = 0;
            RelDataType result = complex ? this.type(0) : this.fields(false, 0);
            this.space();
            if (this.pos != this.text.length())
                throw error(this.binding, "Unexpected schema text at offset " + this.pos);
            return result;
        }
    }
}
