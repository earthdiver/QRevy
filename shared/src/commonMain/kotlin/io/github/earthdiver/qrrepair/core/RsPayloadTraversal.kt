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
        data class ValueGroup(val position: Int, val width: Int, val maximum: Int)
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
        fun exactMatches(index: Int, word: IntArray): Boolean = word.indices.all {
            (word[it] xor sources[index].block.codewords[it]) and sources[index].block.unknownMasks[it].inv() and 255 == 0
        }

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
                var minimum = 0
                var maximumPossible = 0
                var undecided = -1
                repeat(width) { offset ->
                    val known = value(position + offset)
                    minimum = minimum * 2 + (known ?: 0)
                    maximumPossible = maximumPossible * 2 + (known ?: 1)
                    if (known == null && undecided < 0) undecided = position + offset
                }
                if (minimum > maximum) return
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

        fun values(): Sequence<Unit> = sequence {
            // Iterative group traversal keeps long numeric payloads off the call stack.
            val iterators = mutableListOf<Iterator<Unit>>()
            var index = 0
            while (true) {
                cancellation.check()
                if (index == pendingValues.size) {
                    yield(Unit)
                    if (index == 0) break
                    index--
                }
                if (index == iterators.size) {
                    val group = pendingValues[index]
                    iterators += range(group.position, group.width, group.maximum).iterator()
                }
                if (iterators[index].hasNext()) {
                    iterators[index].next()
                    index++
                } else {
                    iterators.removeAt(index)
                    if (index == 0 || limited) break
                    index--
                }
            }
        }

        suspend fun SequenceScope<List<PartialCodewordSolver.Result>>.emit() {
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
                for (ignored in values()) {
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
                try { if ((position until totalBits).all { constrain(it, 0) }) emit() }
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
                            if (valid) emit()
                        } finally { restore(mark) }
                    }
                    1, 2, 4, 8, 13 -> {
                        val mark = marks()
                        try {
                            val countStart = position + if (mode == 13) 8 else 4
                            if (countStart > totalBits) continue
                            if (mode == 13 && !(0 until 4).all { constrain(position + 4 + it, if (it == 3) 1 else 0) }) continue
                            val group = if (version <= 9) 0 else if (version <= 26) 1 else 2
                            val width = when (mode) {
                                1 -> intArrayOf(10, 12, 14)[group]
                                2 -> intArrayOf(9, 11, 13)[group]
                                4 -> intArrayOf(8, 16, 16)[group]
                                else -> intArrayOf(8, 10, 12)[group]
                            }
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
                                val length = when (mode) {
                                    1 -> count / 3 * 10 + intArrayOf(0, 4, 7)[count % 3]
                                    2 -> count / 2 * 11 + count % 2 * 6
                                    4 -> count * 8
                                    else -> count * 13
                                }
                                val valueStart = pendingValues.size
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
                    3 -> if (depth == 0 && totalBits - position >= 20) emit()
                    5 -> if (depth == 0) emit()
                    7 -> if (totalBits - position >= 12) emit()
                    9 -> if (depth == 0 && totalBits - position >= 12) emit()
                }
            }
        }

        suspend fun SequenceScope<List<PartialCodewordSolver.Result>>.choose(index: Int) {
            cancellation.check()
            if (index == sources.size) { parse(0, 0); return }
            val source = sources[index]
            val space = source.space?.takeIf { source.dimensionLimit > 0 || it.dimension == 0 }
            for (candidate in source.fixed) {
                // Exact-space coverage preserves an equivalent ordinary derivation on emission.
                if (space != null && space.dimension <= source.dimensionLimit && exactMatches(index, candidate.corrected)) continue
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
