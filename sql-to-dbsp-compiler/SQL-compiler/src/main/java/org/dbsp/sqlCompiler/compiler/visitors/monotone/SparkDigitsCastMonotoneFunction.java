package org.dbsp.sqlCompiler.compiler.visitors.monotone;

import org.dbsp.sqlCompiler.ir.expression.DBSPCastExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPExpression;

import javax.annotation.Nullable;

/** The Spark cast of a string to an integer that holds every number of 8 digits.  The only
 * monotone strings are the dates printed by {@link FormatDateMonotoneFunction}, which always
 * parse, so the waterline is the plain cast of the waterline. */
final class SparkDigitsCastMonotoneFunction extends MonotoneFunction {
    SparkDigitsCastMonotoneFunction() {
        super("spark_cast_to_i(32|64|128)_s#", NEVER_FAILS);
    }

    @Override
    @Nullable
    DBSPExpression waterline(MonotoneFunctionCall call) {
        Argument value = call.argument(0);
        if (!value.monotone())
            return null;
        return value.waterline().cast(call.call().getNode(), call.call().getType().withMayBeNull(false),
                DBSPCastExpression.CastType.SqlUnsafe);
    }
}
