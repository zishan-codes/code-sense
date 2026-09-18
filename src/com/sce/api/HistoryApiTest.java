package com.sce.api;

import com.sce.SystemController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HistoryApiTest
 *
 * TEMPORARY, standalone verification harness for Day 5.3's
 * /api/history and /api/history/{id} endpoints. Exercises a real,
 * running ApiServer bound to a real, running SystemController — not a
 * mock — using java.net.http.HttpClient to make genuine HTTP requests,
 * exactly as a real REST client would. Seeds real evaluation data
 * through the actual POST /api/evaluate endpoint (Day 5.2), so history
 * queries are backed by real SQLite rows produced by the real pipeline.
 *
 * Should be deleted once Day 5.3 is confirmed working.
 *
 * @author Smart Code Evaluator Team
 */
public class HistoryApiTest {

    private static int passCount = 0;
    private static int failCount = 0;

    public static void main(String[] args) {
        System.out.println("[HistoryApiTest] Starting Day 5.3 REST History API verification...");

        SystemController controller = new SystemController();
        controller.start();

        ApiServer apiServer = new ApiServer(new ApiConfig(0), controller);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        try {
            apiServer.start();
            int port = apiServer.getPort();
            System.out.println("[HistoryApiTest] Server bound to ephemeral port " + port + ".");

            // ---- Seed real data through the actual /api/evaluate pipeline ----
            String successId = submitEvaluation(client, port, "HistorySuccess",
                    "public class HistorySuccess {\n"
                            + "    public static void main(String[] args) {\n"
                            + "        System.out.println(\"ok\");\n"
                            + "    }\n"
                            + "}\n");
            System.out.println("[HistoryApiTest] Seeded SUCCESS evaluation: " + successId);

            String compileErrorId = submitEvaluation(client, port, "HistoryCompileError",
                    "public class HistoryCompileError {\n"
                            + "    public static void main(String[] args) {\n"
                            + "        this is not valid java\n"
                            + "    }\n"
                            + "}\n");
            System.out.println("[HistoryApiTest] Seeded COMPILE_ERROR evaluation: " + compileErrorId);

            // Deliberately contains a quote, a backslash, and (via two
            // println calls) an embedded newline in its captured stdout,
            // to exercise JSON escaping end-to-end.
            String specialCharsId = submitEvaluation(client, port, "HistorySpecialChars",
                    "public class HistorySpecialChars {\n"
                            + "    public static void main(String[] args) {\n"
                            + "        System.out.println(\"He said \\\"hi\\\" then a backslash \\\\ here\");\n"
                            + "        System.out.println(\"second line\");\n"
                            + "    }\n"
                            + "}\n");
            System.out.println("[HistoryApiTest] Seeded special-characters evaluation: " + specialCharsId);

            // ---- Run the Day 5.3 test matrix ----
            testGetRecentReturns200AndEvaluationsKey(client, port);
            testGetRecentRespectsLimitOne(client, port);
            testGetRecentLimitZeroRejected(client, port);
            testGetRecentLimitNegativeRejected(client, port);
            testGetRecentLimitNonNumericRejected(client, port);
            testGetRecentLimitAboveMaxRejected(client, port);
            testPostToHistoryReturns405(client, port);
            testGetByIdKnownReturnsMatchingRecord(client, port, successId);
            testGetByIdUnknownReturns404(client, port);
            testSourceCodeNeverPresent(client, port, successId);
            testJsonEscapingRoundTrips(client, port, specialCharsId);
            testHealthEndpointStillWorks(client, port);
            testEvaluateEndpointStillWorks(client, port);

        } catch (Exception e) {
            failCount++;
            System.err.println("[HistoryApiTest] FAIL — unexpected exception during test execution:");
            e.printStackTrace();
        } finally {
            apiServer.stop();
            controller.shutdown();
            System.out.println("[HistoryApiTest] Server and controller stopped.");
        }

        System.out.println();
        System.out.println("[HistoryApiTest] " + passCount + " passed, " + failCount + " failed.");
        if (failCount == 0) {
            System.out.println("[HistoryApiTest] ALL CHECKS PASSED.");
        } else {
            System.out.println("[HistoryApiTest] SOME CHECKS FAILED.");
        }
    }

    private static void pass(String message) {
        passCount++;
        System.out.println("[HistoryApiTest] PASS — " + message);
    }

    private static void fail(String message) {
        failCount++;
        System.err.println("[HistoryApiTest] FAIL — " + message);
    }

    /**
     * Submits one evaluation via the real POST /api/evaluate endpoint and
     * returns the evaluationId from the response, extracted via
     * JsonUtil (package-private, accessible here since this test lives
     * in the same com.sce.api package).
     */
    private static String submitEvaluation(HttpClient client, int port, String className, String sourceCode)
            throws Exception {
        String requestBody = "{\"className\":\"" + JsonUtil.escapeJsonString(className)
                + "\",\"sourceCode\":\"" + JsonUtil.escapeJsonString(sourceCode) + "\"}";

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/evaluate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException("Seeding evaluation failed with HTTP " + response.statusCode()
                    + ": " + response.body());
        }

        String id = JsonUtil.extractStringField(response.body(), "evaluationId");
        if (id == null) {
            throw new IllegalStateException("Seeding evaluation response had no evaluationId: " + response.body());
        }
        return id;
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }

    private static void testGetRecentReturns200AndEvaluationsKey(HttpClient client, int port) throws Exception {
        HttpResponse<String> response = get(client, "http://localhost:" + port + "/api/history");
        if (response.statusCode() == 200 && response.body().contains("\"evaluations\"")) {
            pass("GET /api/history returns HTTP 200 with an \"evaluations\" key.");
        } else {
            fail("GET /api/history returned HTTP " + response.statusCode() + ", body=" + response.body());
        }
    }

    private static void testGetRecentRespectsLimitOne(HttpClient client, int port) throws Exception {
        HttpResponse<String> response = get(client, "http://localhost:" + port + "/api/history?limit=1");
        int count = countOccurrences(response.body(), "\"evaluationId\":\"");
        if (response.statusCode() == 200 && count <= 1) {
            pass("GET /api/history?limit=1 returns HTTP 200 with at most 1 evaluation (found " + count + ").");
        } else {
            fail("GET /api/history?limit=1 returned status=" + response.statusCode() + ", recordCount=" + count);
        }
    }

    private static void testGetRecentLimitZeroRejected(HttpClient client, int port) throws Exception {
        HttpResponse<String> response = get(client, "http://localhost:" + port + "/api/history?limit=0");
        if (response.statusCode() == 400) {
            pass("GET /api/history?limit=0 correctly returns HTTP 400.");
        } else {
            fail("GET /api/history?limit=0 returned HTTP " + response.statusCode() + " instead of 400.");
        }
    }

    private static void testGetRecentLimitNegativeRejected(HttpClient client, int port) throws Exception {
        HttpResponse<String> response = get(client, "http://localhost:" + port + "/api/history?limit=-1");
        if (response.statusCode() == 400) {
            pass("GET /api/history?limit=-1 correctly returns HTTP 400.");
        } else {
            fail("GET /api/history?limit=-1 returned HTTP " + response.statusCode() + " instead of 400.");
        }
    }

    private static void testGetRecentLimitNonNumericRejected(HttpClient client, int port) throws Exception {
        HttpResponse<String> response = get(client, "http://localhost:" + port + "/api/history?limit=abc");
        if (response.statusCode() == 400) {
            pass("GET /api/history?limit=abc correctly returns HTTP 400.");
        } else {
            fail("GET /api/history?limit=abc returned HTTP " + response.statusCode() + " instead of 400.");
        }
    }

    private static void testGetRecentLimitAboveMaxRejected(HttpClient client, int port) throws Exception {
        HttpResponse<String> response = get(client, "http://localhost:" + port + "/api/history?limit=101");
        if (response.statusCode() == 400) {
            pass("GET /api/history?limit=101 correctly returns HTTP 400 (max is 100).");
        } else {
            fail("GET /api/history?limit=101 returned HTTP " + response.statusCode() + " instead of 400.");
        }
    }

    private static void testPostToHistoryReturns405(HttpClient client, int port) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/history"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 405) {
            pass("POST /api/history correctly returns HTTP 405.");
        } else {
            fail("POST /api/history returned HTTP " + response.statusCode() + " instead of 405.");
        }
    }

    private static void testGetByIdKnownReturnsMatchingRecord(HttpClient client, int port, String knownId)
            throws Exception {
        HttpResponse<String> response = get(client, "http://localhost:" + port + "/api/history/" + knownId);
        String returnedId = JsonUtil.extractStringField(response.body(), "evaluationId");
        if (response.statusCode() == 200 && knownId.equals(returnedId)) {
            pass("GET /api/history/" + knownId + " returns HTTP 200 with the matching evaluationId.");
        } else {
            fail("GET /api/history/" + knownId + " returned status=" + response.statusCode()
                    + ", returnedId=" + returnedId);
        }
    }

    private static void testGetByIdUnknownReturns404(HttpClient client, int port) throws Exception {
        HttpResponse<String> response = get(client,
                "http://localhost:" + port + "/api/history/00000000-0000-0000-0000-000000000000");
        if (response.statusCode() == 404) {
            pass("GET /api/history/{unknown-id} correctly returns HTTP 404.");
        } else {
            fail("GET /api/history/{unknown-id} returned HTTP " + response.statusCode() + " instead of 404.");
        }
    }

    private static void testSourceCodeNeverPresent(HttpClient client, int port, String knownId) throws Exception {
        HttpResponse<String> byIdResponse = get(client, "http://localhost:" + port + "/api/history/" + knownId);
        HttpResponse<String> listResponse = get(client, "http://localhost:" + port + "/api/history?limit=5");

        boolean absent = !byIdResponse.body().contains("\"sourceCode\"")
                && !listResponse.body().contains("\"sourceCode\"");

        if (absent) {
            pass("sourceCode is absent from both the by-ID and list history responses.");
        } else {
            fail("sourceCode was unexpectedly present in a history response.");
        }
    }

    /**
     * Verifies correct JSON escaping via round-trip decoding: fetches the
     * evaluation whose stdout deliberately contains a quote, a backslash,
     * and an embedded newline, and confirms JsonUtil.extractStringField
     * (the same regex-based extraction a real consumer would use) can
     * correctly recover the original literal characters. A broken
     * escaping implementation would either fail this extraction entirely
     * or return a truncated/corrupted value.
     */
    private static void testJsonEscapingRoundTrips(HttpClient client, int port, String specialCharsId)
            throws Exception {
        HttpResponse<String> response = get(client, "http://localhost:" + port + "/api/history/" + specialCharsId);
        String decodedStdout = JsonUtil.extractStringField(response.body(), "stdout");

        boolean ok = response.statusCode() == 200
                && decodedStdout != null
                && decodedStdout.contains("\"")
                && decodedStdout.contains("\\")
                && decodedStdout.contains("\n");

        if (ok) {
            pass("JSON escaping round-trips correctly: decoded stdout contains a literal quote, "
                    + "backslash, and newline, extracted from a well-formed JSON response.");
        } else {
            fail("JSON escaping round-trip failed. status=" + response.statusCode()
                    + ", decodedStdout=" + decodedStdout);
        }
    }

    private static void testHealthEndpointStillWorks(HttpClient client, int port) throws Exception {
        HttpResponse<String> response = get(client, "http://localhost:" + port + "/api/health");
        if (response.statusCode() == 200 && response.body().contains("\"UP\"")) {
            pass("GET /api/health still works (Day 5.1 unaffected).");
        } else {
            fail("GET /api/health returned unexpected status/body: " + response.statusCode()
                    + " " + response.body());
        }
    }

    private static void testEvaluateEndpointStillWorks(HttpClient client, int port) throws Exception {
        String id = submitEvaluation(client, port, "StillWorksCheck",
                "public class StillWorksCheck {\n"
                        + "    public static void main(String[] args) {\n"
                        + "        System.out.println(\"still working\");\n"
                        + "    }\n"
                        + "}\n");
        if (id != null && !id.isEmpty()) {
            pass("POST /api/evaluate still works (Day 5.2 unaffected), evaluationId=" + id);
        } else {
            fail("POST /api/evaluate did not return a valid evaluationId.");
        }
    }
}
