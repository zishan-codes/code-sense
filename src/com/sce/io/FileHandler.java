package com.sce.io;

import com.sce.core.EvaluationRequest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * FileHandler
 *
 * Owns the complete lifecycle of on-disk artifacts for a single
 * evaluation: workspace creation, source-file persistence, process
 * stream draining, and guaranteed cleanup.
 *
 * Threading contract: a NEW FileHandler instance (or at minimum a new
 * workspace via createWorkspace()) MUST be used per evaluation request.
 * This class holds no mutable instance state between calls, so a single
 * instance is safe to reuse across evaluations, but workspaces themselves
 * must never be shared between concurrent evaluations — each gets its
 * own isolated temp directory to prevent one student's submission from
 * colliding with, or reading, another's files.
 *
 * @author Smart Code Evaluator Team
 */
public class FileHandler {

    /** Prefix applied to every temp workspace so orphaned directories
     *  are easy to identify and purge manually if cleanup() is ever skipped
     *  (e.g., after a JVM crash). */
    private static final String WORKSPACE_PREFIX = "sce-eval-";

    /**
     * Creates a secure, isolated temporary directory to serve as the
     * workspace for one evaluation. Using Files.createTempDirectory
     * guarantees the directory name is unique and, on POSIX systems,
     * created with owner-only permissions by default, which is the
     * correct baseline for a workspace that may temporarily contain
     * untrusted student code.
     *
     * @return the Path to the newly created workspace directory
     * @throws IOException if the temp directory cannot be created —
     *                       typically disk-full, permission-denied, or
     *                       the OS temp location is unavailable. This is
     *                       surfaced as an "environment error" by the
     *                       caller, distinct from a student code error.
     */
    public Path createWorkspace() throws IOException {
        try {
            Path workspace = Files.createTempDirectory(WORKSPACE_PREFIX);
            return workspace;
        } catch (IOException e) {
            // Re-thrown with additional context so the AI UI Controller
            // can distinguish this from a compilation/runtime failure
            // and avoid forwarding a misleading "error" to the AI Connector.
            throw new IOException(
                "FileHandler: failed to create evaluation workspace. " +
                "Check disk space and OS temp-directory permissions.", e
            );
        }
    }

    /**
     * Writes the submitted source code to <workspace>/<ClassName>.java
     * using a buffered character stream. The filename is derived strictly
     * from EvaluationRequest.getClassName() to satisfy javac's requirement
     * that a public top-level class be declared in a file of the same name.
     *
     * try-with-resources guarantees the BufferedWriter (and the underlying
     * FileWriter/stream) is closed even if an exception is thrown mid-write,
     * preventing file-handle leaks across repeated evaluations.
     *
     * @param workspace the workspace directory previously returned by
     *                   createWorkspace(); must exist and be writable
     * @param req        the evaluation request holding className and sourceCode
     * @return the Path to the newly written .java source file
     * @throws IOException              if the file cannot be created or written
     * @throws IllegalArgumentException if workspace is null or does not exist
     */
    public Path writeSource(Path workspace, EvaluationRequest req) throws IOException {
        if (workspace == null || !Files.isDirectory(workspace)) {
            throw new IllegalArgumentException(
                "writeSource: workspace must be a valid, existing directory. " +
                "Call createWorkspace() first."
            );
        }
        if (req == null) {
            throw new IllegalArgumentException("writeSource: EvaluationRequest must not be null.");
        }

        Path sourceFile = workspace.resolve(req.getClassName() + ".java");

        try (BufferedWriter writer = Files.newBufferedWriter(sourceFile, StandardCharsets.UTF_8)) {
            writer.write(req.getSourceCode());
            writer.flush();
        } catch (IOException e) {
            throw new IOException(
                "FileHandler: failed to write source file '" + sourceFile +
                "'. The workspace may have been removed concurrently, or " +
                "disk space may be exhausted.", e
            );
        }

        return sourceFile;
    }

    /**
     * Reads the entirety of a process's InputStream (stdout or stderr)
     * into a single String, using a buffered character stream to avoid
     * excessive syscall overhead on large outputs.
     *
     * IMPORTANT (concurrency note): when draining BOTH stdout and stderr
     * from the same Process, this method must be invoked on two SEPARATE
     * threads — one per stream. Reading both sequentially on a single
     * thread risks the classic ProcessBuilder pipe-buffer deadlock, where
     * the child process blocks writing to a full OS pipe buffer while the
     * parent is still blocked reading the other stream. The Concurrency
     * Engine is responsible for enforcing this two-thread pattern; this
     * method itself is single-stream and side-effect-free with respect
     * to threading.
     *
     * @param is the InputStream to fully drain (e.g., process.getInputStream()
     *            or process.getErrorStream()); must not be null
     * @return the complete captured text, with line breaks preserved as '\n'
     * @throws IOException              if an I/O error occurs while reading
     * @throws IllegalArgumentException if is is null
     */
    public String readStream(InputStream is) throws IOException {
        if (is == null) {
            throw new IllegalArgumentException("readStream: InputStream must not be null.");
        }

        StringBuilder captured = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (!firstLine) {
                    captured.append('\n');
                }
                captured.append(line);
                firstLine = false;
            }
        } catch (IOException e) {
            throw new IOException(
                "FileHandler: failed while draining a process stream. " +
                "The child process may have been terminated forcibly " +
                "(e.g., by the watchdog thread) mid-read.", e
            );
        }

        return captured.toString();
    }

    /**
     * Recursively deletes the given workspace directory and all of its
     * contents. This method is designed to be called from a 'finally'
     * block by the caller so that temp-directory cleanup happens
     * unconditionally — regardless of whether the evaluation succeeded,
     * threw an exception, or was forcibly terminated by the watchdog
     * thread. Failing to clean up would leak a new temp directory on
     * every single evaluation, eventually exhausting disk space.
     *
     * Deletion order matters: Files.walk() returns the root first and
     * descendants after, but a directory cannot be deleted while it
     * still contains files. Sorting in REVERSE order guarantees every
     * file and subdirectory is deleted before its parent.
     *
     * This method deliberately does NOT throw on individual delete
     * failures (e.g., a file locked by an OS-level anti-virus scan) —
     * it logs and continues, because a cleanup failure must never mask
     * or replace the original evaluation Result being returned to the
     * caller. It DOES throw if the walk itself cannot begin at all,
     * since that indicates workspace was invalid to begin with.
     *
     * @param workspace the workspace directory to remove; if null or
     *                   already non-existent, this method returns silently
     */
    public void cleanup(Path workspace) {
        if (workspace == null || !Files.exists(workspace)) {
            // Nothing to clean up — not an error condition. This can
            // legitimately happen if createWorkspace() itself failed
            // before a workspace was ever created.
            return;
        }

        try (Stream<Path> walk = Files.walk(workspace)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        // Deliberately swallowed and logged rather than propagated:
                        // a single locked file should not prevent the rest of the
                        // workspace from being cleaned, and must never cause the
                        // caller's evaluation Result to be lost or overwritten.
                        System.err.println(
                            "FileHandler.cleanup: could not delete '" + path +
                            "' — " + e.getMessage() +
                            ". This artifact may require manual removal."
                        );
                    }
                });
        } catch (IOException e) {
            // Thrown only if Files.walk() itself cannot traverse the
            // workspace root (e.g., permission revoked mid-run). Wrapped
            // as unchecked so cleanup() can still be safely called from
            // a finally block without forcing every caller to add a
            // second try/catch around cleanup itself.
            throw new UncheckedIOException(
                "FileHandler: failed to walk workspace '" + workspace +
                "' for cleanup. Manual removal of this directory may be required.",
                e
            );
        }
    }
}