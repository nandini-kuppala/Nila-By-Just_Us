package com.nila.ml

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** Which compute unit actually ended up executing the graph. */
enum class Accelerator { NNAPI, GPU, CPU }

data class LatencyStats(
    val accelerator: Accelerator,
    val samples: Int,
    val meanMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
) {
    override fun toString() =
        "$accelerator  mean ${"%.2f".format(meanMs)}ms  " +
            "p50 ${"%.2f".format(p50Ms)}ms  p95 ${"%.2f".format(p95Ms)}ms  (n=$samples)"
}

/**
 * A thin wrapper over Interpreter that picks an accelerator and measures itself.
 *
 * Delegate order is NNAPI, then GPU, then CPU. On a Snapdragon device NNAPI is
 * what routes the graph to the Hexagon NPU; on anything else it degrades to
 * whatever the vendor exposes. Each option is *tried and verified with a real
 * inference* rather than assumed, because a delegate that constructs
 * successfully can still fail at first invoke -- and discovering that at 3am in
 * a foreground service is the wrong time.
 *
 * The latency ring is not decoration. It is the number that goes on the
 * technical-depth slide, and it is measured on the device that will be demoed
 * rather than quoted from a datasheet.
 */
class TfliteRunner private constructor(
    private val interpreter: Interpreter,
    private val nnapi: NnApiDelegate?,
    private val gpu: GpuDelegate?,
    val accelerator: Accelerator,
    val inputShape: IntArray,
    val outputShape: IntArray,
) : Closeable {

    private val timings = ArrayDeque<Double>()
    private val maxTimings = 200

    val outputSize: Int get() = outputShape.fold(1) { a, b -> a * b }

    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(inputShape.fold(4) { a, b -> a * b })
            .order(ByteOrder.nativeOrder())

    private val outputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(outputSize * 4).order(ByteOrder.nativeOrder())

    /**
     * Whether the native interpreter is still alive.
     *
     * Guarded by the same monitor as [run] and [close], because the thing this
     * prevents is not a logic error -- it is a native abort. TensorFlow Lite's
     * `Interpreter.close()` frees memory the JNI layer holds no reference count
     * for, so closing it twice, or closing it underneath a running inference,
     * takes the process out with SIGABRT. `runCatching` cannot catch that: it
     * is not a Java exception, it is the runtime dying.
     */
    private var closed = false

    /** Runs the graph on [input] and writes probabilities into [out]. */
    @Synchronized
    fun run(input: FloatArray, out: FloatArray) {
        require(out.size >= outputSize) { "output array too small" }
        // A window that arrives after the engine was closed is normal at the
        // moment monitoring stops -- there is one in flight nearly every time.
        // Before this it reached a freed interpreter.
        if (closed) return

        inputBuffer.rewind()
        inputBuffer.asFloatBuffer().put(input)
        inputBuffer.rewind()
        outputBuffer.rewind()

        val t0 = SystemClock.elapsedRealtimeNanos()
        interpreter.run(inputBuffer, outputBuffer)
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0

        outputBuffer.rewind()
        outputBuffer.asFloatBuffer().get(out, 0, outputSize)

        timings.addLast(ms)
        if (timings.size > maxTimings) timings.removeFirst()
    }

    fun run(input: FloatArray): FloatArray =
        FloatArray(outputSize).also { run(input, it) }

    @Synchronized
    fun latency(): LatencyStats {
        if (timings.isEmpty()) return LatencyStats(accelerator, 0, 0.0, 0.0, 0.0)
        val sorted = timings.sorted()
        return LatencyStats(
            accelerator = accelerator,
            samples = sorted.size,
            meanMs = sorted.average(),
            p50Ms = sorted[sorted.size / 2],
            p95Ms = sorted[((sorted.size * 95) / 100).coerceAtMost(sorted.size - 1)],
        )
    }

    /**
     * Free the interpreter, once, and never while it is running.
     *
     * `@Synchronized` on the same monitor as [run] is the important half: it
     * makes closing wait for an inference in flight instead of freeing the
     * memory underneath it. The flag is the other half -- two callers closing
     * the same engine was a real crash, not a hypothetical one. Stopping the
     * demo cancelled the simulation coroutine and closed the engine from the
     * main thread while the coroutine's own `finally` closed it again from a
     * worker, and the second free aborted the process.
     */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { interpreter.close() }
        runCatching { nnapi?.close() }
        runCatching { gpu?.close() }
    }

    companion object {
        private const val TAG = "TfliteRunner"

        fun fromAsset(
            context: Context,
            assetName: String,
            preferred: Accelerator = Accelerator.NNAPI,
            threads: Int = 2,
        ): TfliteRunner {
            val model = loadAsset(context, assetName)

            // NPU first, then GPU, and the CPU only because a monitor that
            // cannot load its detector is worse than one running it slowly.
            // Landing on CPU is a fault to be surfaced, not a quiet default:
            // it is reported on the Insights screen, which is where the
            // accelerator this device actually chose is on display.
            val order = when (preferred) {
                Accelerator.NNAPI -> listOf(Accelerator.NNAPI, Accelerator.GPU, Accelerator.CPU)
                Accelerator.GPU -> listOf(Accelerator.GPU, Accelerator.NNAPI, Accelerator.CPU)
                Accelerator.CPU -> listOf(Accelerator.CPU)
            }

            for (target in order) {
                try {
                    return build(model, target, threads)
                } catch (t: Throwable) {
                    Log.w(TAG, "$assetName: $target unavailable (${t.message}); falling back")
                }
            }
            error("no accelerator could run $assetName")
        }

        private fun build(
            model: MappedByteBuffer,
            target: Accelerator,
            threads: Int,
        ): TfliteRunner {
            var nnapi: NnApiDelegate? = null
            var gpu: GpuDelegate? = null
            val options = Interpreter.Options()
            options.numThreads = threads

            when (target) {
                Accelerator.NNAPI -> {
                    val d = NnApiDelegate()
                    nnapi = d
                    options.addDelegate(d)
                }
                Accelerator.GPU -> {
                    // `CompatibilityList` is a shipped allow-list, and it does
                    // not know hardware newer than the build of TFLite it came
                    // in. On a Snapdragon 8 Elite Gen 5 it answers "no" for a
                    // GPU that works perfectly, which is how this device ended
                    // up on the CPU with the delegate never attempted.
                    //
                    // So its answer is used for *tuning* and not for
                    // permission: known device, take its options; unknown
                    // device, try the defaults. Either way the real inference
                    // below is what decides, which is the rule every other
                    // option here is already held to.
                    val compat = runCatching { CompatibilityList() }.getOrNull()
                    val known = compat?.isDelegateSupportedOnThisDevice == true
                    val d = if (known) {
                        GpuDelegate(compat!!.bestOptionsForThisDevice)
                    } else {
                        Log.i(TAG, "GPU not in the compatibility list; trying anyway")
                        GpuDelegate()
                    }
                    gpu = d
                    options.addDelegate(d)
                }
                Accelerator.CPU -> options.setUseXNNPACK(true)
            }

            fun cleanup() {
                runCatching { nnapi?.close() }
                runCatching { gpu?.close() }
            }

            val interpreter = try {
                Interpreter(model, options)
            } catch (t: Throwable) {
                cleanup(); throw t
            }

            val inShape = interpreter.getInputTensor(0).shape()
            val outShape = interpreter.getOutputTensor(0).shape()

            // Smoke-test with a real invoke. A delegate can construct fine and
            // then throw on first run; finding that out here beats discovering
            // it inside a foreground service at 3am.
            try {
                val probe = ByteBuffer
                    .allocateDirect(inShape.fold(4) { a, b -> a * b })
                    .order(ByteOrder.nativeOrder())
                val out = ByteBuffer
                    .allocateDirect(outShape.fold(4) { a, b -> a * b })
                    .order(ByteOrder.nativeOrder())
                interpreter.run(probe, out)
            } catch (t: Throwable) {
                runCatching { interpreter.close() }
                cleanup(); throw t
            }

            Log.i(TAG, "$target  in=${inShape.toList()} out=${outShape.toList()}")
            return TfliteRunner(interpreter, nnapi, gpu, target, inShape, outShape)
        }

        private fun loadAsset(context: Context, name: String): MappedByteBuffer =
            context.assets.openFd(name).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    stream.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        fd.startOffset,
                        fd.declaredLength,
                    )
                }
            }
    }
}
