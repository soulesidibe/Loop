#!/bin/sh
# The test run of the playground, a stand in so the cli test needs no Kotlin compiler: the test
# `divides by zero` fails until divide rejects a zero divisor.
if grep -q 'fun `divides by zero`' src/test/kotlin/calc/CalcTest.kt && ! grep -q 'require(b != 0)' src/main/kotlin/calc/Calc.kt; then
    echo "CalcTest > divides by zero FAILED: expected IllegalArgumentException, divide returned 0"
    exit 1
fi
echo "CalcTest passed"
