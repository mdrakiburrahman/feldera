package org.dbsp.sqlCompiler.compiler.frontend;

import org.apache.calcite.rel.type.RelDataType;
import org.dbsp.sqlCompiler.compiler.errors.CompilationError;
import org.dbsp.sqlCompiler.compiler.frontend.calciteObject.CalciteObject;
import org.dbsp.sqlCompiler.ir.expression.DBSPApplyExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPArrayExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPTupleExpression;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPBoolLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPStringLiteral;
import org.dbsp.sqlCompiler.ir.type.DBSPType;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeCode;
import org.dbsp.sqlCompiler.ir.type.derived.DBSPTypeTuple;
import org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeAny;
import org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeNull;
import org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeString;
import org.dbsp.sqlCompiler.ir.type.user.DBSPTypeArray;
import org.dbsp.sqlCompiler.ir.type.user.DBSPTypeMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Generate type-directed readers and writers: a SQL ROW is a Rust tuple, but a Spark JSON object. */
class SparkJsonCompiler {
    private static final DBSPTypeString TEXT = DBSPTypeString.varchar(true);
    private static final DBSPType ANY = DBSPTypeAny.getDefault();

    static DBSPExpression namedStruct(CalciteObject node, DBSPType type, List<DBSPExpression> ops) {
        List<DBSPExpression> values = new ArrayList<>();
        for (int i = 1; i < ops.size(); i += 2)
            values.add(ops.get(i));
        return new DBSPTupleExpression(node, type.to(DBSPTypeTuple.class), values);
    }

    static DBSPExpression getJsonObject(CalciteObject node, DBSPExpression text, DBSPExpression path) {
        if (text.type.is(DBSPTypeNull.class) || path.type.is(DBSPTypeNull.class))
            return TEXT.none();
        return new DBSPApplyExpression(node, "spark_get_json_object", TEXT, text.someIfNeeded(), path.someIfNeeded());
    }

    static DBSPExpression fromJson(CalciteObject node, DBSPType type, RelDataType schema, DBSPExpression text) {
        if (text.type.is(DBSPTypeNull.class))
            return type.none();
        var json = ANY.var();
        return new DBSPApplyExpression(node, "spark_from_json", type, text.someIfNeeded(),
                read(node, type, schema, json, true).closure(json));
    }

    private static DBSPExpression read(CalciteObject node, DBSPType type, RelDataType schema,
                                       DBSPExpression json, boolean root) {
        var item = ANY.var();
        if (type instanceof DBSPTypeTuple tuple) {
            List<DBSPExpression> fields = new ArrayList<>();
            for (var field : schema.getFieldList()) {
                var member = new DBSPApplyExpression(node, "spark_json_member", ANY, item,
                        new DBSPStringLiteral(field.getName()));
                fields.add(read(node, tuple.getFieldType(field.getIndex()), field.getType(), member, false));
            }
            var row = new DBSPTupleExpression(node, tuple.withMayBeNull(false).to(DBSPTypeTuple.class), fields);
            return new DBSPApplyExpression(node, "spark_json_read_object", type, json,
                    new DBSPBoolLiteral(root), row.closure(item));
        } else if (type instanceof DBSPTypeArray array) {
            var element = read(node, array.getElementType(), Objects.requireNonNull(schema.getComponentType()), item, false);
            return new DBSPApplyExpression(node, "spark_json_read_array", type, json,
                    new DBSPBoolLiteral(root && array.getElementType().is(DBSPTypeTuple.class)), element.closure(item));
        } else if (type instanceof DBSPTypeMap map) {
            var value = read(node, map.getValueType(), Objects.requireNonNull(schema.getValueType()), item, false);
            return new DBSPApplyExpression(node, "spark_json_read_map", type, json, value.closure(item));
        }
        return new DBSPApplyExpression(node, type.is(DBSPTypeString.class) ? "spark_json_text" : "spark_json_scalar",
                type, json);
    }

    static DBSPExpression write(CalciteObject node, RelDataType schema, DBSPExpression value) {
        DBSPType type = value.getType();
        if (type instanceof DBSPTypeTuple tuple) {
            var row = tuple.withMayBeNull(false).var();
            List<DBSPExpression> fields = new ArrayList<>();
            for (var field : schema.getFieldList()) {
                var json = write(node, field.getType(), row.field(field.getIndex()).applyCloneIfNeeded());
                fields.add(new DBSPApplyExpression(node, "spark_json_field", TEXT,
                        new DBSPStringLiteral(field.getName()), json));
            }
            var members = new DBSPArrayExpression(node, new DBSPTypeArray(TEXT, false), fields);
            return new DBSPApplyExpression(node, "spark_json_write_object", TEXT, value.someIfNeeded(), members.closure(row));
        } else if (type instanceof DBSPTypeArray array) {
            var item = array.getElementType().var();
            var json = write(node, Objects.requireNonNull(schema.getComponentType()), item);
            return new DBSPApplyExpression(node, "spark_json_write_array", TEXT, value.someIfNeeded(), json.closure(item));
        } else if (type instanceof DBSPTypeMap map) {
            if (!map.getKeyType().is(DBSPTypeString.class) || map.getKeyType().mayBeNull)
                throw new CompilationError("TO_JSON requires non-null STRING map keys", node);
            var item = map.getValueType().var();
            var json = write(node, Objects.requireNonNull(schema.getValueType()), item);
            return new DBSPApplyExpression(node, "spark_json_write_map", TEXT, value.someIfNeeded(), json.closure(item));
        }
        return switch (type.code) {
            case NULL -> TEXT.none();
            case BOOL, INT8, INT16, INT32, INT64, UINT8, UINT16, UINT32, UINT64, STRING ->
                    new DBSPApplyExpression(node, "spark_json_write_scalar", TEXT, value.someIfNeeded());
            case DATE -> new DBSPApplyExpression(node, "spark_json_write_date", TEXT, value.someIfNeeded());
            case TIMESTAMP, TIMESTAMP_TZ -> new DBSPApplyExpression(node,
                    type.code == DBSPTypeCode.TIMESTAMP ? "spark_json_write_timestamp" : "spark_json_write_timestamp_tz",
                    TEXT, value.someIfNeeded());
            case DECIMAL -> new DBSPApplyExpression(node, "spark_json_write_decimal", TEXT, value.someIfNeeded());
            case DOUBLE, REAL -> new DBSPApplyExpression(node,
                    type.code == DBSPTypeCode.DOUBLE ? "spark_json_write_f64" : "spark_json_write_f32",
                    TEXT, value.someIfNeeded());
            default -> throw new CompilationError("Unsupported TO_JSON element type: " + type.asSqlString(), node);
        };
    }
}
