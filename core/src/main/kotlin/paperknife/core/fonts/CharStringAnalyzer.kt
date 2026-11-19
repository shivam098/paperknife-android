package paperknife.core.fonts

import paperknife.core.support.Ref
import kotlin.math.floor

/**
 * Follows a Type 1 glyph program (Adobe Type 1 Font Format, chapter 6) far enough to tell what it
 * depends on: the subroutines it calls, directly or through other subroutines, and the glyphs an
 * accented glyph is built from (seac). Type 1 programs have no branches, so running them with the
 * operand values tracked finds exactly the subroutines a renderer runs. Hint replacement
 * ("subr# 1 3 callothersubr pop callsubr") is followed; a subroutine number that can't be known
 * fails the analysis.
 */
internal object CharStringAnalyzer {
    private const val MAX_DEPTH = 16
    private const val MAX_STACK = 24

    /** A limit on the instructions run, shared by the analyses of one font, so a crafted font can't take long. */
    internal class Budget(var steps: Int)

    /** The subroutines a glyph program calls and the StandardEncoding codes of the glyphs it is built from, as C#'s tuple. */
    internal data class Analysis(val subrs: Set<Int>, val components: List<Int>)

    /**
     * The subroutines [program] calls and the StandardEncoding codes of the glyphs it is built
     * from, or null when the program can't be followed with certainty.
     */
    fun analyze(program: ByteArray, subrs: Map<Int, ByteArray>, budget: Budget): Analysis? {
        val run = Run(subrs, budget)
        return if (run.execute(program, 0) == Outcome.Failed) null else Analysis(run.called, run.components)
    }

    private enum class Outcome {
        Return,
        End,
        Failed,
    }

    private class Run(private val subrs: Map<Int, ByteArray>, private val budget: Budget) {
        // Unknown values are null.
        private val stack = ArrayList<Double?>()
        private var knownResults = 0
        private var unknownResults: List<Double?>? = null
        private var nextUnknown = 0

        val called = LinkedHashSet<Int>()

        val components = ArrayList<Int>()

        fun execute(program: ByteArray, depth: Int): Outcome {
            if (depth > MAX_DEPTH) {
                return Outcome.Failed
            }

            var i = 0
            while (i < program.size) {
                if (--budget.steps < 0) {
                    return Outcome.Failed
                }

                val v = program[i++].toInt() and 0xFF
                if (v >= 32) {
                    val at = Ref(i)
                    val number = readNumber(program, v, at)
                    i = at.value
                    if (number == null || stack.size >= MAX_STACK) {
                        return Outcome.Failed
                    }

                    stack.add(number)
                    continue
                }

                when (v) {
                    10 -> { // callsubr
                        val index = Ref<Double?>(null)
                        if (!tryPop(index)) {
                            return Outcome.Failed
                        }

                        val n = index.value
                        if (n == null || !isIndex(n)) {
                            return Outcome.Failed
                        }

                        val subr = subrs[n.toInt()] ?: return Outcome.Failed
                        called.add(n.toInt())
                        val outcome = execute(subr, depth + 1)
                        if (outcome != Outcome.Return) {
                            return outcome
                        }
                    }
                    11 -> return Outcome.Return // return
                    14 -> return Outcome.End // endchar
                    12 -> {
                        if (i >= program.size) {
                            return Outcome.Failed
                        }

                        when (program[i++].toInt() and 0xFF) {
                            6 -> { // seac: asb adx ady bchar achar
                                val baseCode = if (stack.size >= 5) stack[stack.size - 2] else null
                                val accentCode = if (stack.size >= 5) stack[stack.size - 1] else null
                                if (baseCode == null || accentCode == null || !isCode(baseCode) || !isCode(accentCode)) {
                                    return Outcome.Failed
                                }

                                components.add(baseCode.toInt())
                                components.add(accentCode.toInt())
                                return Outcome.End
                            }
                            12 -> { // div
                                val divisor = Ref<Double?>(null)
                                val dividend = Ref<Double?>(null)
                                if (!tryPop(divisor) || !tryPop(dividend)) {
                                    return Outcome.Failed
                                }

                                val d = divisor.value
                                val x = dividend.value
                                stack.add(if (d != null && x != null && d != 0.0) x / d else null)
                            }
                            16 -> { // callothersubr: arg1 ... argn n othersubr#
                                val other = Ref<Double?>(null)
                                val count = Ref<Double?>(null)
                                if (!tryPop(other) || !tryPop(count)) {
                                    return Outcome.Failed
                                }

                                val o = other.value
                                val c = count.value
                                if (o == null || c == null || !isIndex(c) || c > stack.size || !callOtherSubr(o, c.toInt())) {
                                    return Outcome.Failed
                                }
                            }
                            17 -> { // pop: takes a result of the last callothersubr
                                val results = unknownResults
                                if (knownResults > 0) {
                                    // Already on the stack, as FreeType puts it there.
                                    knownResults--
                                } else if (results != null && nextUnknown < results.size && stack.size < MAX_STACK) {
                                    stack.add(results[nextUnknown++])
                                } else {
                                    return Outcome.Failed
                                }
                            }
                            0, 1, 2, 7, 33 -> stack.clear() // dotsection, vstem3, hstem3, sbw, setcurrentpoint
                            else -> return Outcome.Failed
                        }
                    }
                    // hstem, vstem, vmoveto, rlineto, hlineto, vlineto, rrcurveto, closepath, hsbw,
                    // rmoveto, hmoveto, vhcurveto, hvcurveto: they use the operands and clear the stack.
                    1, 3, 4, 5, 6, 7, 8, 9, 13, 21, 22, 30, 31 -> stack.clear()
                    else -> return Outcome.Failed
                }
            }

            return Outcome.Return
        }

        // As FreeType's t1decode.c: the results of the othersubrs it knows are put back on the
        // operand stack at once (the end point of a flex, the subroutine number for hint
        // replacement), and "pop" only consumes them; the arguments of others come back one per "pop".
        private fun callOtherSubr(number: Double, count: Int): Boolean {
            val arguments = ArrayList(stack.subList(stack.size - count, stack.size))
            stack.subList(stack.size - count, stack.size).clear()
            knownResults = 0
            unknownResults = null
            nextUnknown = 0
            return if (number == 0.0) { // end of flex
                if (count != 3) {
                    false
                } else {
                    stack.add(null)
                    stack.add(null)
                    knownResults = 2
                    true
                }
            } else if (number == 1.0 || number == 2.0) { // start of flex, a flex point
                count == 0
            } else if (number == 3.0) { // hint replacement
                if (count != 1) {
                    false
                } else {
                    stack.add(arguments[0])
                    knownResults = 1
                    true
                }
            } else {
                unknownResults = arguments
                true
            }
        }

        private fun tryPop(value: Ref<Double?>): Boolean {
            if (stack.isEmpty()) {
                value.value = null
                return false
            }

            value.value = stack.removeAt(stack.size - 1)
            return true
        }

        private fun isIndex(value: Double): Boolean = value >= 0 && value <= 65535 && value == floor(value)

        private fun isCode(value: Double): Boolean = value >= 0 && value <= 255 && value == floor(value)
    }

    /** A number in a Type 1 glyph program (section 6.2), or null when the program ends inside it. */
    fun readNumber(program: ByteArray, v: Int, i: Ref<Int>): Double? {
        when {
            v <= 246 -> return (v - 139).toDouble()
            v <= 250 && i.value < program.size -> return (((v - 247) * 256) + (program[i.value++].toInt() and 0xFF) + 108).toDouble()
            v <= 254 && i.value < program.size -> return (-((v - 251) * 256) - (program[i.value++].toInt() and 0xFF) - 108).toDouble()
            v == 255 && i.value + 4 <= program.size -> {
                val at = i.value
                val value = ((program[at].toInt() and 0xFF) shl 24) or ((program[at + 1].toInt() and 0xFF) shl 16) or
                    ((program[at + 2].toInt() and 0xFF) shl 8) or (program[at + 3].toInt() and 0xFF)
                i.value += 4
                return value.toDouble()
            }
            else -> return null
        }
    }
}
