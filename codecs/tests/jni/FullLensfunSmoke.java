import dev.filmroom.codec.NativeCodecBridge;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Real host JNI admission and cancellation, using only NativeCodecBridge.
 *
 * Arguments: final .so, isolated private root, GR III copy, Nikon Z f copy,
 * Canon RF135 copy. Inputs must already be inside the private root.
 * Supply -Dfilmroom.expectedSoSha256=... to bind this run to the approved build.
 * This class never copies/deletes inputs, uploads RAWs, or deletes an output to
 * conceal failed native cleanup. The existing CodecSmoke suite is unchanged.
 */
public final class FullLensfunSmoke {
    private static final String MEMORY = "{\"memoryBudgetMiB\":31,\"diskBudgetBytes\":2147483648}";
    private static final String DISK = "{\"memoryBudgetMiB\":256,\"diskBudgetBytes\":1}";
    private static final String NORMAL = "{\"memoryBudgetMiB\":256,\"diskBudgetBytes\":2147483648}";
    private static final long WORK_SECONDS = 40;
    private static final long DEADLINE_SECONDS = 45;
    private static final int HEAD_BYTES = 64 * 1024;

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String quote(String value) {
        if (value == null) return "null";
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') result.append('\\').append(c);
            else if (c == '\n') result.append("\\n");
            else if (c == '\r') result.append("\\r");
            else if (c == '\t') result.append("\\t");
            else if (c < 32) result.append(String.format("\\u%04x", (int) c));
            else result.append(c);
        }
        return result.append('"').toString();
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] block = new byte[64 * 1024];
        try (InputStream stream = Files.newInputStream(path)) {
            int count;
            while ((count = stream.read(block)) != -1) digest.update(block, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean hasCode(String response, String code) {
        return response != null && response.contains("\"ok\":false")
            && response.contains("\"outputValid\":false")
            && response.contains("\"code\":\"" + code + "\"");
    }

    private static final class DecodeWorker {
        final Thread thread;
        volatile String response;
        volatile Throwable failure;

        DecodeWorker(long handle, Path input, Path output, String budget, String name) {
            thread = new Thread(() -> {
                try {
                    response = NativeCodecBridge.nativeDecode(handle, input.toString(), output.toString(), budget);
                } catch (Throwable error) {
                    failure = error;
                }
            }, "full-lensfun-" + name);
            // A native hang fails the bounded join below. It must not make a
            // failed host regression keep the JVM alive beyond its deadline.
            thread.setDaemon(true);
        }
    }

    private static boolean joinUntil(Thread thread, long deadline) throws InterruptedException {
        while (thread.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            thread.join(Math.max(1, Math.min(1000, TimeUnit.NANOSECONDS.toMillis(remaining))));
        }
        return true;
    }

    /** A sparse/preallocated length is insufficient: observe a finite RGB pixel. */
    private static int nonzeroPixelInHead(Path path, ByteBuffer head) throws Exception {
        if (!exists(path)) return -1;
        head.clear();
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ)) {
            int count = file.read(head, 0);
            if (count < 12) return -1;
            head.flip();
            for (int position = 0; position + 12 <= count; position += 12) {
                float r = head.getFloat(position);
                float g = head.getFloat(position + 4);
                float b = head.getFloat(position + 8);
                if (Float.isFinite(r) && Float.isFinite(g) && Float.isFinite(b)
                    && (r != 0.0f || g != 0.0f || b != 0.0f)) return position;
            }
        } catch (NoSuchFileException removedByNative) {
            // A native failure can remove its output between exists and open.
        }
        return -1;
    }

    private static Throwable addFailure(Throwable previous, Throwable next) {
        if (previous == null) return next;
        previous.addSuppressed(next);
        return previous;
    }

    private static String check(Path root, Path input, String inputSha, String sample,
        String name, String budget, String expectedCode, boolean cancelAfterPixels,
        long workDeadline, long cleanupDeadline) throws Exception {
        Path results = root.resolve("results");
        Path output = results.resolve(sample + "-" + name + ".f32le");
        require(!exists(output), "preexisting_output_" + sample + "_" + name);
        require(sha256(input).equals(inputSha), "input_changed_before_" + sample);
        long startedAt = System.nanoTime();
        long handle = 0;
        DecodeWorker worker = null;
        boolean started = false;
        boolean joined = false;
        boolean destroyed = false;
        int firstPixelByteOffset = -1;
        long firstPixelMs = -1;
        long cancelMs = -1;
        String finalInputSha = null;
        Throwable failed = null;
        try {
            require(System.nanoTime() < workDeadline, "suite_work_deadline");
            handle = NativeCodecBridge.nativeCreate(root.toString());
            require(handle != 0, "native_create_failed");
            worker = new DecodeWorker(handle, input, output, budget, sample + "-" + name);
            worker.thread.start();
            started = true;
            if (cancelAfterPixels) {
                ByteBuffer head = ByteBuffer.allocate(HEAD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
                while (firstPixelByteOffset < 0) {
                    firstPixelByteOffset = nonzeroPixelInHead(output, head);
                    if (firstPixelByteOffset >= 0) break;
                    require(worker.thread.isAlive(), "decode_finished_before_nonzero_pixel_" + sample);
                    require(System.nanoTime() < workDeadline, "nonzero_pixel_deadline_" + sample);
                    Thread.sleep(1);
                }
                firstPixelMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                NativeCodecBridge.nativeCancel(handle);
                cancelMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            }
            require(joinUntil(worker.thread, workDeadline), "native_result_deadline_" + sample);
            require(worker.failure == null, "native_worker_exception_" + sample);
            require(hasCode(worker.response, expectedCode), "unexpected_native_result_" + sample + "_" + name);
            require(!exists(output), "native_output_not_removed_" + sample + "_" + name);
        } catch (Throwable error) {
            failed = error;
        } finally {
            // Each individual case owns one handle and one worker. Cleanup is
            // attempted on assertions, early completion, JNI errors and timeout.
            try {
                if (handle != 0) NativeCodecBridge.nativeCancel(handle);
                if (started) joined = joinUntil(worker.thread, cleanupDeadline);
                else joined = true;
            } catch (Throwable cleanupError) {
                failed = addFailure(failed, cleanupError);
            } finally {
                if (handle != 0) {
                    try {
                        NativeCodecBridge.nativeDestroy(handle);
                        destroyed = true;
                    } catch (Throwable destroyError) {
                        failed = addFailure(failed, destroyError);
                    }
                }
            }
            try {
                finalInputSha = sha256(input);
                require(joined, "worker_not_joined_" + sample + "_" + name);
                require(handle == 0 || destroyed, "handle_not_destroyed_" + sample + "_" + name);
                require(!exists(output), "output_survived_finally_" + sample + "_" + name);
                require(inputSha.equals(finalInputSha), "input_changed_after_" + sample + "_" + name);
            } catch (Throwable verificationError) {
                failed = addFailure(failed, verificationError);
            }
        }
        String response = worker == null ? null : worker.response;
        String record = "{\"ok\":" + (failed == null)
            + ",\"sample\":" + quote(sample) + ",\"case\":" + quote(name)
            + ",\"expectedCode\":" + quote(expectedCode) + ",\"budget\":" + budget
            + ",\"inputSha256Before\":" + quote(inputSha) + ",\"inputSha256After\":" + quote(finalInputSha)
            + ",\"inputUnchanged\":" + inputSha.equals(finalInputSha)
            + ",\"outputAbsent\":" + !exists(output) + ",\"workerJoined\":" + joined
            + ",\"handleDestroyed\":" + destroyed + ",\"firstNonzeroPixelByteOffset\":" + firstPixelByteOffset
            + ",\"firstNonzeroPixelMs\":" + firstPixelMs + ",\"cancelRequestedMs\":" + cancelMs
            + ",\"elapsedMs\":" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            + ",\"error\":" + quote(failed == null ? null : failed.toString())
            + ",\"nativeResponse\":" + (response == null ? "null" : response) + "}";
        Files.writeString(results.resolve(sample + "-" + name + "-result.json"), record + "\n");
        if (response != null) Files.writeString(results.resolve(sample + "-" + name + "-response.json"), response + "\n");
        if (failed != null) throw new AssertionError(sample + "/" + name + ": " + failed, failed);
        System.out.println(record);
        return record;
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 5, "usage_final_so_private_root_gr3_nef_rf135");
        long startedAt = System.nanoTime();
        long workDeadline = startedAt + TimeUnit.SECONDS.toNanos(WORK_SECONDS);
        long cleanupDeadline = startedAt + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
        Path library = Path.of(args[0]).toRealPath();
        String librarySha = sha256(library);
        String expectedLibrarySha = System.getProperty("filmroom.expectedSoSha256");
        require(expectedLibrarySha != null && expectedLibrarySha.matches("[0-9a-f]{64}")
            && expectedLibrarySha.equals(librarySha), "final_so_sha256_not_confirmed");
        Path root = Path.of(args[1]).toRealPath();
        require(Files.isDirectory(root), "private_root_not_directory");
        Path results = Files.createDirectories(root.resolve("results")).toRealPath();
        require(results.startsWith(root), "results_outside_private_root");
        Path[] inputs = new Path[3];
        String[] hashes = new String[3];
        String[] samples = {"ricoh-gr3", "nikon-zf", "canon-rf135"};
        for (int i = 0; i < inputs.length; i++) {
            inputs[i] = Path.of(args[i + 2]).toRealPath();
            require(inputs[i].startsWith(root) && Files.isRegularFile(inputs[i]), "input_not_private_copy");
            hashes[i] = sha256(inputs[i]);
        }
        // Never load an unconfirmed/old .so just to prepare or compile the suite.
        System.load(library.toString());
        List<String> records = new ArrayList<>();
        Throwable failure = null;
        try {
            for (int i = 0; i < inputs.length; i++) {
                records.add(check(root, inputs[i], hashes[i], samples[i], "memory31", MEMORY, "MEMORY_BUDGET", false, workDeadline, cleanupDeadline));
                records.add(check(root, inputs[i], hashes[i], samples[i], "disk1", DISK, "DISK_BUDGET", false, workDeadline, cleanupDeadline));
                records.add(check(root, inputs[i], hashes[i], samples[i], "cancel-after-pixels", NORMAL, "CANCELLED", true, workDeadline, cleanupDeadline));
            }
            require(records.size() == 9, "wrong_check_count");
            require(sha256(library).equals(librarySha), "library_changed_during_suite");
            require(System.nanoTime() <= cleanupDeadline, "suite_deadline_exceeded");
        } catch (Throwable error) {
            failure = error;
        }
        String summary = "{\"ok\":" + (failure == null) + ",\"suite\":\"host-jni-full-lensfun\""
            + ",\"checks\":" + records.size() + ",\"expectedChecks\":9,\"deadlineSeconds\":45"
            + ",\"elapsedMs\":" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            + ",\"soSha256\":" + quote(librarySha) + ",\"androidDeviceRuntimeTest\":false"
            + ",\"error\":" + quote(failure == null ? null : failure.toString())
            + ",\"cases\":[" + String.join(",", records) + "]}";
        Files.writeString(root.resolve("full-lensfun-jni-result.json"), summary + "\n");
        System.out.println(summary);
        if (failure != null) throw new AssertionError("FULL_LENSFUN_JNI_FAILED", failure);
    }
}
