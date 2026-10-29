package evaluation

import com.google.gson.GsonBuilder
import java.io.File
import java.time.Duration
import java.time.Instant
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.Recording
import jdk.jfr.StackTrace
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile

@Name("qrevy.DecoderInvocation")
@Label("Measured recovery invocation")
@StackTrace(false)
internal class DecoderInvocation : Event() {
    @JvmField var scenario: String = ""
    @JvmField var seed: Int = 0
    @JvmField var iteration: Int = 0
}

/** JDK-only opt-in sampling; production classes contain no profiling hooks. */
internal class RecoveryProfile(private val output: File) : AutoCloseable {
    private val recording = Recording().apply {
        name = "QRevy synthetic recovery"
        enable(DecoderInvocation::class.java).withThreshold(Duration.ZERO).withoutStackTrace()
        enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1)).withStackTrace()
        enable("jdk.ObjectAllocationSample").with("throttle", "1000/s").withStackTrace()
        start()
    }

    override fun close() {
        try {
            recording.stop()
            val destination = File(output, "profile.jfr").toPath()
            recording.dump(destination)
        } finally { recording.close() }
        summarizeRecording(File(output, "profile.jfr"), File(output, "profile.json"))
    }
}

private data class Window(val thread: Long, val start: Instant, val end: Instant,
    val scenario: String, val seed: Int, val iteration: Int)

/** Half-open intervals prevent a boundary sample being charged twice. */
private fun windowAt(windows: List<Window>, time: Instant): Window? {
    var low = 0
    var high = windows.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (windows[mid].start <= time) low = mid + 1 else high = mid
    }
    return windows.getOrNull(low - 1)?.takeIf { time < it.end }
}

private const val CORE = "io.github.earthdiver.qrrepair.core."
private class SampleCounts {
    var samples = 0L
    var weight = 0L
    var truncated = 0L
    var missingStack = 0L
    val leaf = mutableMapOf<String, Long>()
    val inclusive = mutableMapOf<String, Long>()
    val callers = mutableMapOf<String, Long>()
    val allocationClasses = mutableMapOf<String, Long>()

    fun add(frames: List<String>, charge: Long, isTruncated: Boolean, allocatedClass: String? = null) {
        require(charge >= 0)
        samples++
        weight += charge
        if (isTruncated) truncated++
        if (frames.isEmpty()) missingStack++
        val core = frames.filter { it.startsWith(CORE) }
        val nearest = core.firstOrNull()?.removePrefix(CORE) ?: "<no core frame>"
        leaf[nearest] = (leaf[nearest] ?: 0) + charge
        // Recursive/repeated frames are counted once per sample in the inclusive table.
        for (method in core.map { it.removePrefix(CORE) }.distinct()) {
            inclusive[method] = (inclusive[method] ?: 0) + charge
        }
        val path = core.take(8).joinToString(" <- ") { it.removePrefix(CORE) }.ifEmpty { "<no core frame>" }
        callers[path] = (callers[path] ?: 0) + charge
        if (allocatedClass != null) allocationClasses[allocatedClass] = (allocationClasses[allocatedClass] ?: 0) + charge
    }

    fun report(): Map<String, Any> {
        fun ranked(values: Map<String, Long>) = values.entries.sortedWith(
            compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).map {
            mapOf("name" to it.key, "weight" to it.value, "percent" to if (weight == 0L) 0.0 else it.value * 100.0 / weight)
        }
        return mapOf("samples" to samples, "weight" to weight, "truncatedStacks" to truncated,
            "missingStacks" to missingStack, "nearestCoreMethod" to ranked(leaf),
            "inclusiveCoreMethods" to ranked(inclusive), "coreCallerPaths" to ranked(callers),
            "allocationClasses" to ranked(allocationClasses))
    }
}

private class ProfileGroup {
    var invocations = 0
    var totalDecodeNanos = 0L
    val cpu = SampleCounts()
    val allocation = SampleCounts()
    fun report() = mapOf("invocations" to invocations, "totalDecodeMs" to totalDecodeNanos / 1e6,
        "execution" to cpu.report(), "allocation" to allocation.report())
}

private fun visit(file: File, consume: (RecordedEvent) -> Unit) {
    RecordingFile(file.toPath()).use { recording -> while (recording.hasMoreEvents()) consume(recording.readEvent()) }
}

private fun summarizeRecording(file: File, output: File) {
    val windows = mutableListOf<Window>()
    visit(file) { event -> if (event.eventType.name == "qrevy.DecoderInvocation") {
        windows += Window(event.thread.javaThreadId, event.startTime, event.endTime,
            event.getString("scenario"), event.getInt("seed"), event.getInt("iteration"))
    } }
    require(windows.isNotEmpty()) { "Recording contains no measured decoder windows" }
    val threads = windows.groupBy { it.thread }.mapValues { (_, group) -> group.sortedBy { it.start } }
    threads.values.forEach { group ->
        require(group.zipWithNext().all { (a, b) -> a.end <= b.start }) { "Overlapping decoder windows" }
    }
    val total = ProfileGroup()
    val groups = linkedMapOf<String, ProfileGroup>()
    for (window in windows) {
        for (group in listOf(total, groups.getOrPut("${window.scenario}/seed-${window.seed}") { ProfileGroup() })) {
            group.invocations++
            group.totalDecodeNanos += Duration.between(window.start, window.end).toNanos()
        }
    }
    val excluded = mutableMapOf<String, Int>()
    visit(file) { event ->
        val type = event.eventType.name
        if (type != "jdk.ExecutionSample" && type != "jdk.ObjectAllocationSample") return@visit
        val thread = if (type == "jdk.ExecutionSample") event.getThread("sampledThread") else event.thread
        val window = thread?.let { threads[it.javaThreadId] }?.let { windowAt(it, event.startTime) }
        if (window == null) { excluded[type] = (excluded[type] ?: 0) + 1; return@visit }
        val stack = event.stackTrace
        val frames = stack?.frames?.map { "${it.method.type.name}.${it.method.name}" }.orEmpty()
        for (group in listOf(total, groups.getValue("${window.scenario}/seed-${window.seed}"))) {
            if (type == "jdk.ExecutionSample") group.cpu.add(frames, 1, stack?.isTruncated == true)
            else group.allocation.add(frames, event.getLong("weight"), stack?.isTruncated == true,
                event.getClass("objectClass").name)
        }
    }
    output.writeText(GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
        "schemaVersion" to 1, "executionPeriodMillis" to 1, "allocationThrottlePerSecond" to 1000,
        "scope" to "Samples on the decoder thread within measured invocation windows only; warmup, image work and result serialization excluded",
        "executionMeaning" to "Java execution sample share, not exact elapsed-time share, call counts or speedup guarantee",
        "allocationMeaning" to "JFR sample weights estimate allocation pressure, not exact allocated bytes or retained heap; weights can span window boundaries",
        "nearestMeaning" to "Nearest core frame including work in its library callees; mutually exclusive",
        "inclusiveMeaning" to "Any matching stack frame, deduplicated within each sample; rows overlap and must not be summed",
        "excludedSamples" to excluded, "total" to total.report(),
        "byFixture" to groups.toSortedMap().mapValues { it.value.report() })) + "\n")
}

internal fun recoveryProfileSelfTest() {
    val start = Instant.parse("2026-01-01T00:00:00Z")
    val first = Window(1, start, start.plusSeconds(1), "mixed", 0, 0)
    val second = Window(1, start.plusSeconds(2), start.plusSeconds(3), "mixed", 0, 1)
    val windows = listOf(first, second)
    check(windowAt(windows, start.minusNanos(1)) == null)
    check(windowAt(windows, start) == first)
    check(windowAt(windows, first.end) == null)
    check(windowAt(windows, second.start) == second)
    check(windowAt(windows, second.end) == null)
    val counts = SampleCounts()
    counts.add(listOf("java.util.Arrays.copyOf", "${CORE}Solver.run", "${CORE}Solver.run", "${CORE}Engine.run"), 9, false, "int[]")
    counts.add(emptyList(), 1, true)
    check(counts.weight == 10L && counts.samples == 2L && counts.truncated == 1L && counts.missingStack == 1L)
    check(counts.inclusive["Solver.run"] == 9L && counts.inclusive["Engine.run"] == 9L)
    check(counts.leaf.values.sum() == 10L && counts.allocationClasses["int[]"] == 9L)
}
