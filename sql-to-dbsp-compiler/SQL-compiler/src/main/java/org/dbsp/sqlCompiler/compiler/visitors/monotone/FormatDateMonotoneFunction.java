package org.dbsp.sqlCompiler.compiler.visitors.monotone;

import org.dbsp.sqlCompiler.ir.expression.DBSPExpression;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPStringLiteral;

import javax.annotation.Nullable;
import java.util.Set;

/** FORMAT_DATE(format, d) or FORMAT_TIMESTAMP(format, ts) when the format is a constant that
 * prints only the year, month and day as fixed-width digits, most significant first, e.g.
 * '%Y%m%d'.  Years are between 1 and 9999, so the result has at most 8 digits, and its
 * lexicographic order, its numeric order and the order of the dates agree. */
final class FormatDateMonotoneFunction extends MonotoneFunction {
    static final Set<String> FORMATS = Set.of("%Y", "%Y%m", "%Y%m%d");

    FormatDateMonotoneFunction() {
        super("format_(date|timestamp)__#", NEVER_FAILS);
    }

    @Override
    @Nullable
    DBSPExpression waterline(MonotoneFunctionCall call) {
        Argument format = call.argument(0);
        Argument value = call.argument(1);
        if (!value.monotone() || !format.constant())
            return null;
        DBSPStringLiteral literal = format.expression().as(DBSPStringLiteral.class);
        if (literal == null || literal.value == null || !FORMATS.contains(literal.value.toJavaString()))
            return null;
        return call.withWaterline(1, value.waterline());
    }
}
