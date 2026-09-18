package com.sce.api;

import com.sce.SystemController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * ApiServerTest
 *
 * TEMPORARY standalone regression verification harness for the REST API
 * foundation after Day 5.2 integration.
 *
 * This test verifies that ApiServer, HealthHandler, ApiResponse, ApiConfig,
 * and the shared SystemController integration work together correctly.
 *
 * It uses a real HTTP client against a real running HttpServer.
 *
 * ApiConfig(0) is used so the test binds to an OS-assigned ephemeral port
 * and does not collide with any locally running service.
 *
 * The same SystemController instance is supplied to ApiServer that would
 * be shared by the application. This verifies the new Day 5.2 dependency
 * relationship without creating a second evaluation pipeline.
 *
 * This test intentionally does NOT test POST /api/evaluate yet. That is
 * covered by the dedicated Day 5.2 integration verification.
 *
 * @author Smart Code Evaluator Team
 */
public class ApiServerTest {

    private static int passCount = 0;
    private static int failCount = 0;

    public static void main(String[] args) {

        System.out.println(
                "[ApiServerTest] Starting REST API regression verification..."
        );

        SystemController controller = new SystemController();
        ApiServer apiServer = new ApiServer(
                new ApiConfig(0),
                controller
        );

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        try {
            /*
             * ApiServer now depends on the shared SystemController,
             * so the controller must be started before the server.
             */
            controller.start();
            pass("Shared SystemController started successfully.");

            testServerStarts(apiServer);

            int port = apiServer.getPort();

            System.out.println(
                    "[ApiServerTest] Server bound to OS-assigned ephemeral port "
                            + port + "."
            );

            testHealthReturns200(client, port);
            testHealthBodyContainsStatusUp(client, port);
            testUnknownPathReturns404(client, port);
            testUnsupportedMethodReturns405(client, port);

        } catch (Exception e) {
            failCount++;

            System.err.println(
                    "[ApiServerTest] FAIL — unexpected exception during test execution:"
            );

            e.printStackTrace();

        } finally {

            testServerStopsCleanly(apiServer);

            try {
                controller.shutdown();
                pass("Shared SystemController shutdown completed.");
            } catch (Exception e) {
                fail(
                        "SystemController.shutdown() threw an unexpected exception: "
                                + e
                );
            }
        }

        System.out.println();
        System.out.println(
                "[ApiServerTest] "
                        + passCount
                        + " passed, "
                        + failCount
                        + " failed."
        );

        if (failCount == 0) {
            System.out.println(
                    "[ApiServerTest] ALL CHECKS PASSED."
            );
        } else {
            System.out.println(
                    "[ApiServerTest] SOME CHECKS FAILED."
            );
        }
    }

    private static void pass(String message) {
        passCount++;
        System.out.println(
                "[ApiServerTest] PASS — " + message
        );
    }

    private static void fail(String message) {
        failCount++;
        System.err.println(
                "[ApiServerTest] FAIL — " + message
        );
    }

    private static void testServerStarts(ApiServer apiServer)
            throws IOException {

        apiServer.start();

        if (apiServer.isRunning()) {
            pass(
                    "ApiServer.start() succeeded and isRunning() reports true."
            );
        } else {
            fail(
                    "ApiServer.start() completed but isRunning() reports false."
            );
        }
    }

    private static void testHealthReturns200(
            HttpClient client,
            int port
    ) throws Exception {

        HttpRequest request = HttpRequest.newBuilder(
                URI.create(
                        "http://localhost:" + port + "/api/health"
                )
        )
                .GET()
                .build();

        HttpResponse<String> response =
                client.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        if (response.statusCode() == 200) {
            pass(
                    "GET /api/health returned HTTP 200."
            );
        } else {
            fail(
                    "GET /api/health returned HTTP "
                            + response.statusCode()
                            + " instead of 200."
            );
        }
    }

    private static void testHealthBodyContainsStatusUp(
            HttpClient client,
            int port
    ) throws Exception {

        HttpRequest request = HttpRequest.newBuilder(
                URI.create(
                        "http://localhost:" + port + "/api/health"
                )
        )
                .GET()
                .build();

        HttpResponse<String> response =
                client.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        String body = response.body();

        boolean contentTypeOk =
                response.headers()
                        .firstValue("Content-Type")
                        .map(value ->
                                value.contains("application/json")
                        )
                        .orElse(false);

        boolean bodyOk =
                "{\"status\":\"UP\"}".equals(body);

        if (bodyOk && contentTypeOk) {
            pass(
                    "GET /api/health returned the expected JSON body "
                            + "and JSON content type: "
                            + body
            );
        } else {
            fail(
                    "GET /api/health body/content-type unexpected. "
                            + "body="
                            + body
                            + ", contentTypeOk="
                            + contentTypeOk
            );
        }
    }

    private static void testUnknownPathReturns404(
            HttpClient client,
            int port
    ) throws Exception {

        HttpRequest request = HttpRequest.newBuilder(
                URI.create(
                        "http://localhost:"
                                + port
                                + "/api/does-not-exist"
                )
        )
                .GET()
                .build();

        HttpResponse<String> response =
                client.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        if (response.statusCode() == 404) {
            pass(
                    "GET /api/does-not-exist correctly returned HTTP 404."
            );
        } else {
            fail(
                    "GET /api/does-not-exist returned HTTP "
                            + response.statusCode()
                            + " instead of 404."
            );
        }
    }

    private static void testUnsupportedMethodReturns405(
            HttpClient client,
            int port
    ) throws Exception {

        HttpRequest request = HttpRequest.newBuilder(
                URI.create(
                        "http://localhost:"
                                + port
                                + "/api/health"
                )
        )
                .POST(
                        HttpRequest.BodyPublishers.noBody()
                )
                .build();

        HttpResponse<String> response =
                client.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        if (response.statusCode() == 405) {
            pass(
                    "POST /api/health correctly returned HTTP 405."
            );
        } else {
            fail(
                    "POST /api/health returned HTTP "
                            + response.statusCode()
                            + " instead of 405."
            );
        }
    }

    private static void testServerStopsCleanly(
            ApiServer apiServer
    ) {

        try {
            apiServer.stop();

            if (!apiServer.isRunning()) {
                pass(
                        "ApiServer.stop() completed and isRunning() "
                                + "reports false."
                );
            } else {
                fail(
                        "ApiServer.stop() completed but isRunning() "
                                + "still reports true."
                );
            }

        } catch (Exception e) {
            fail(
                    "ApiServer.stop() threw an unexpected exception: "
                            + e
            );
        }
    }
}