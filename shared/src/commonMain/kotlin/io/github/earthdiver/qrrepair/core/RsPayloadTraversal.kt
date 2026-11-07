package io.github.earthdiver.qrrepair.core

/** A bounded error-correction list alongside the symbolic exact RS solution space. */
class RsCandidateSource(
    val block: RsBlockWork,
    val fixed: List<PartialCodewordSolver.Result>,
    val space: RsBitConstraintSolver.Space?,
    val dimensionLimit: Int,
) {
    private class Key(val words: IntArray) {
        override fun hashCode() = words.contentHashCode()
        override fun equals(other: Any?) = other is Key && words.contentEquals(other.words)
    }
    private val equivalents by lazy { fixed.associateBy { Key(it.corrected) } }
    fun equivalent(words: IntArray): PartialCodewordSolver.Result? = equivalents[Key(words)]
}

/** Streams RS solutions after applying QR header and value conditions to their free variables. */
class RsPayloadTraversal(
    private val sources: List<RsCandidateSource>,
    private val version: Int,
    private val maxNodes: Int,
    private val cancellation: RecoveryCancellation = RecoveryCancellation.NONE,
    private val assumeUtf8ByteSegments: Boolean = false,
    private val onUtf8Rejection: () -> Unit = {},
) {
    private class TraversalLimit : RuntimeException()
    private var branches = 0
    var nodes: Int = 0
        private set
    var limited: Boolean = false
        private set
    var dimensionLimitedBlock: Int? = null
        private set
    var materialized: Int = 0
        private set

    fun combinations(acceptPrefix: (List<PartialCodewordSolver.Result>) -> Boolean): Sequence<List<PartialCodewordSolver.Result>> = sequence {
        val restrictions = arrayOfNulls<RsBitConstraintSolver.Space.Restriction>(sources.size)
        val fixed = arrayOfNulls<PartialCodewordSolver.Result>(sources.size)
        val enumeratedDimensions = IntArray(sources.size)
        val byteBlocks = sources.flatMapIndexed { index, source -> List(source.block.numDataCodewords) { index } }.toIntArray()
        val byteWords = sources.flatMap { source -> (0 until source.block.numDataCodewords).toList() }.toIntArray()
        val totalBits = byteBlocks.size * 8
        val prefix = ArrayList<PartialCodewordSolver.Result>(sources.size)
        data class ValueGroup(val position: Int, val width: Int, val maximum: Int, val utf8Remaining: Int = -1)
        val pendingValues = mutableListOf<ValueGroup>()

        fun step(): Boolean {
            cancellation.check()
            if (nodes >= maxNodes) { limited = true; return false }
            nodes++
            return true
        }
        fun branchStep(): Boolean {
            // Leave most of the node budget for completed candidates, rather than spending
            // it on speculative headers or repeated interval subdivisions.
            if (branches >= 4096) { limited = true; return false }
            branches++
            return step()
        }
        fun bit(position: Int) = RsBitConstraintSolver.Bit(byteWords[position / 8], 7 - position % 8)
        fun value(position: Int): Int? {
            if (!step()) throw TraversalLimit()
            val index = byteBlocks[position / 8]
            return restrictions[index]?.value(bit(position)) ?: fixed[index]?.let { (it.corrected[byteWords[position / 8]] shr (7 - position % 8)) and 1 }
        }
        fun constrain(position: Int, expected: Int): Boolean {
            if (!step()) throw TraversalLimit()
            val index = byteBlocks[position / 8]
            return restrictions[index]?.constrain(bit(position), expected) ?: (value(position) == expected)
        }
        fun marks() = IntArray(sources.size) { restrictions[it]?.checkpoint ?: 0 }
        fun restore(marks: IntArray) { restrictions.forEachIndexed { i, r -> r?.restore(marks[i]) } }

        suspend fun SequenceScope<List<PartialCodewordSolver.Result>>.emitBlocks(index: Int = 0) {
            cancellation.check()
            if (index == sources.size) { yield(prefix.toList()); return }
            val source = sources[index]
            val branch = restrictions[index]
            if (branch != null && branch.remaining > source.dimensionLimit) {
                if (dimensionLimitedBlock == null) dimensionLimitedBlock = index
                return
            }
            val candidates = if (branch == null) sequenceOf(requireNotNull(fixed[index])) else {
                val erased = source.block.unknownMasks.indices.filter { source.block.unknownMasks[it] != 0 }.toIntArray()
                branch.assignments(source.dimensionLimit, cancellation).map { word ->
                    materialized++
                    val exact = PartialCodewordSolver.Result(word, erased, enumeratedDimensions[index], 0, intArrayOf(), 0, bitConstraintSolved = true)
                    source.equivalent(word)?.let { ordinary ->
                        ordinary.copy(alternativeDerivations = (ordinary.alternativeDerivations + exact.derivation()).distinct())
                    } ?: exact
                }
            }
            for (candidate in candidates) {
                if (!step()) return
                prefix += candidate
                if (acceptPrefix(prefix)) emitBlocks(index + 1)
                prefix.removeAt(prefix.lastIndex)
                if (limited) return
            }
        }

        fun field(position: Int, width: Int, maximum: Int): Sequence<Int> = sequence {
            if (position + width > totalBits) return@sequence
            suspend fun SequenceScope<Int>.read(offset: Int, accumulated: Int) {
                cancellation.check()
                if ((accumulated.toLong() shl (width - offset)) > maximum) return
                if (offset == width) { yield(accumulated); return }
                val location = position + offset
                val known = value(location)
                if (known != null) { read(offset + 1, accumulated * 2 + known); return }
                val restriction = requireNotNull(restrictions[byteBlocks[location / 8]])
                val checkpoint = restriction.checkpoint
                for (next in 0..1) {
                    if (!branchStep()) return
                    try {
                        if (constrain(location, next)) read(offset + 1, accumulated * 2 + next)
                    } finally { restriction.restore(checkpoint) }
                    if (limited) return
                }
            }
            read(0, 0)
        }

        // An interval is a union of binary prefixes. Keep each accepted prefix symbolic:
        // e.g. numeric values below 1000 need no enumeration of all 1000 values here.
        fun range(position: Int, width: Int, maximum: Int): Sequence<Unit> = sequence {
            suspend fun SequenceScope<Unit>.visit() {
                cancellation.check()
                var minimumPossible = 0
                var maximumPossible = 0
                var undecided = -1
                repeat(width) { offset ->
                    val known = value(position + offset)
                    minimumPossible = minimumPossible * 2 + (known ?: 0)
                    maximumPossible = maximumPossible * 2 + (known ?: 1)
                    if (known == null && undecided < 0) undecided = position + offset
                }
                if (minimumPossible > maximum) return
                if (maximumPossible <= maximum) { yield(Unit); return }
                val restriction = requireNotNull(restrictions[byteBlocks[undecided / 8]])
                val checkpoint = restriction.checkpoint
                for (next in 0..1) {
                    if (!branchStep()) return
                    try { if (constrain(undecided, next)) visit() }
                    finally { restriction.restore(checkpoint) }
                    if (limited) return
                }
            }
            visit()
        }

        fun groupStates(group: ValueGroup, state: Utf8State): Sequence<Utf8State> = sequence {
            if (group.utf8Remaining < 0) {
                if (state != Utf8State.BOUNDARY) { onUtf8Rejection(); return@sequence }
                for (ignored in range(group.position, group.width, group.maximum)) yield(Utf8State.BOUNDARY)
                return@sequence
            }
            // Inspect the byte's significant bits only. Once a whole interval has the same
            // transition, leave all remaining bits free in the affine RS solution space.
            suspend fun SequenceScope<Utf8State>.visit(offset: Int, prefix: Int) {
                val shift = 8 - offset
                val low = prefix shl shift
                val high = low + (1 shl shift) - 1
                var intersects = false
                var whole: Utf8State? = null
                state.transitions(low, high, group.utf8Remaining) { first, last, next ->
                    intersects = true
                    if (low >= first && high <= last) whole = next
                }
                if (!intersects) { onUtf8Rejection(); return }
                whole?.let { yield(it); return }
                val p = group.position + offset
                val known = value(p)
                if (known != null) { visit(offset + 1, prefix * 2 + known); return }
                val restriction = requireNotNull(restrictions[byteBlocks[p / 8]])
                val checkpoint = restriction.checkpoint
                for (next in 0..1) {
                    if (!branchStep()) return
                    try { if (constrain(p, next)) visit(offset + 1, prefix * 2 + next) }
                    finally { restriction.restore(checkpoint) }
                    if (limited) return
                }
            }
            visit(0, 0)
        }

        fun values(requireUtf8Boundary: Boolean): Sequence<Unit> = sequence {
            // Iterative group traversal keeps long numeric payloads off the call stack.
            val iterators = mutableListOf<Iterator<Utf8State>>()
            val states = Array(pendingValues.size + 1) { Utf8State.BOUNDARY }
            var index = 0
            while (true) {
                cancellation.check()
                if (index == pendingValues.size) {
                    if (!requireUtf8Boundary || states[index] == Utf8State.BOUNDARY) yield(Unit)
                    else onUtf8Rejection()
                    if (index == 0) break
                    index--
                }
                if (index == iterators.size) {
                    val group = pendingValues[index]
                    iterators += groupStates(group, states[index]).iterator()
                }
                if (iterators[index].hasNext()) {
                    states[index + 1] = iterators[index].next()
                    index++
                } else {
                    iterators.removeAt(index)
                    if (index == 0 || limited) break
                    index--
                }
            }
        }

        suspend fun SequenceScope<List<PartialCodewordSolver.Result>>.emit(requireUtf8Boundary: Boolean = false) {
            // Apply the dimension bound to the whole header branch. Interval subdivision
            // must not admit only a tiny boundary slice of an otherwise oversized space.
            for (index in sources.indices) {
                val branch = restrictions[index] ?: continue
                enumeratedDimensions[index] = branch.remaining
                if (branch.remaining > sources[index].dimensionLimit) {
                    if (dimensionLimitedBlock == null) dimensionLimitedBlock = index
                    return
                }
            }
            val mark = marks()
            try {
                for (ignored in values(requireUtf8Boundary)) {
                    emitBlocks()
                    if (limited) return
                }
            } finally { restore(mark) }
        }

        suspend fun SequenceScope<List<PartialCodewordSolver.Result>>.parse(position: Int, depth: Int) {
            cancellation.check()
            // Deep/control-prefixed streams retain the unstructured route. This bound limits
            // optimization stack depth, not the QR formats accepted by final validation.
            if (depth >= 16) { emit(); return }
            if (totalBits - position < 4) {
                val mark = marks()
                try { if ((position until totalBits).all { constrain(it, 0) }) emit(requireUtf8Boundary = true) }
                finally { restore(mark) }
                return
            }
            for (mode in field(position, 4, 15)) {
                if (limited) return
                when (mode) {
                    0 -> {
                        val mark = marks()
                        try {
                            val aligned = minOf(totalBits, ((position + 4 + 7) / 8) * 8)
                            var valid = true
                            for (p in position + 4 until totalBits) {
                                cancellation.check()
                                val expected = if (p < aligned) 0 else {
                                    val pad = if ((p - aligned) / 8 % 2 == 0) 0xEC else 0x11
                                    (pad shr (7 - p % 8)) and 1
                                }
                                if (!constrain(p, expected)) { valid = false; break }
                            }
                            if (valid) emit(requireUtf8Boundary = true)
                        } finally { restore(mark) }
                    }
                    1, 2, 4, 8, 13 -> {
                        val mark = marks()
                        try {
                            val countStart = position + if (mode == 13) 8 else 4
                            if (countStart > totalBits) continue
                            if (mode == 13 && !(0 until 4).all { constrain(position + 4 + it, if (it == 3) 1 else 0) }) continue
                            val width = requireNotNull(QrPayloadLayout.characterCountBits(mode, version.coerceAtLeast(1)))
                            val body = countStart + width
                            if (body > totalBits) continue
                            val remaining = totalBits - body
                            val maximum = when (mode) {
                                1 -> remaining / 10 * 3 + if (remaining % 10 >= 7) 2 else if (remaining % 10 >= 4) 1 else 0
                                2 -> remaining / 11 * 2 + if (remaining % 11 >= 6) 1 else 0
                                4 -> remaining / 8
                                else -> remaining / 13
                            }
                            for (count in field(countStart, width, maximum)) {
                                val length = requireNotNull(QrPayloadLayout.payloadBits(mode, count))
                                val valueStart = pendingValues.size
                                if (mode == 4 && assumeUtf8ByteSegments) {
                                    repeat(count) { i -> pendingValues += ValueGroup(body + i * 8, 8, 255, 3) }
                                }
                                // Default Kanji/Hanzi interpretation ends an assumed UTF-8 Byte run.
                                if (assumeUtf8ByteSegments && count > 0 && (mode == 8 || mode == 13)) {
                                    pendingValues += ValueGroup(body, 0, 0)
                                }
                                if (mode == 1 || mode == 2) {
                                    val size = if (mode == 1) 3 else 2
                                    val packedWidth = if (mode == 1) 10 else 11
                                    for (index in 0 until (count + size - 1) / size) {
                                        val characters = minOf(size, count - index * size)
                                        val bits = if (mode == 1) intArrayOf(0, 4, 7, 10)[characters] else if (characters == 2) 11 else 6
                                        val maximumValue = if (mode == 1) intArrayOf(0, 9, 99, 999)[characters] else if (characters == 2) 2024 else 44
                                        pendingValues += ValueGroup(body + index * packedWidth, bits, maximumValue)
                                    }
                                }
                                try { parse(body + length, depth + 1) }
                                finally { while (pendingValues.size > valueStart) pendingValues.removeAt(pendingValues.lastIndex) }
                                if (limited) return
                            }
                        } finally { restore(mark) }
                    }
                    3 -> if (depth == 0 && totalBits - position >= 20) {
                        // A character may cross symbol boundaries; the ordinary validator keeps
                        // the possible incoming/outgoing UTF-8 states for Structured Append.
                        emit()
                    }
                    5, 9 -> if (depth == 0) {
                        val body = position + if (mode == 5) 4 else 12
                        if (body <= totalBits) {
                            if (assumeUtf8ByteSegments) parse(body, depth + 1) else emit()
                        }
                    }
                    // ECI governs all following Byte segments; the ordinary validator handles them.
                    7 -> if (totalBits - position >= 12) emit()
                }
            }
        }

        suspend fun SequenceScope<List<PartialCodewordSolver.Result>>.choose(index: Int) {
            cancellation.check()
            if (index == sources.size) { parse(0, 0); return }
            val source = sources[index]
            val space = source.space?.takeIf { source.dimensionLimit > 0 || it.dimension == 0 }
            for (candidate in source.fixed) {
                // A later symbolic branch may never be reached within the traversal budget.
                // Validate this route now; the caller merges candidates after validation.
                if (!step()) return
                fixed[index] = candidate
                choose(index + 1)
                if (limited) return
            }
            fixed[index] = null
            if (space != null) {
                if (!step()) return
                restrictions[index] = space.restriction()
                choose(index + 1)
                restrictions[index] = null
            }
        }
        try { choose(0) } catch (_: TraversalLimit) { limited = true }
    }
}
