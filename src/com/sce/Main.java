package com.sce;

import com.sce.api.ApiServer;

/**
 * Main
 *
 * Day 5.2 integration entry point for starting the shared evaluation
 * controller together with the REST API.
 *
 * The application remains alive until interrupted so that the REST API
 * can be tested manually from another terminal.
 *
 * @author Smart Code Evaluator Team
 */
public class Main {

    public static void main(String[] args) {

        SystemController controller = new SystemController();
        ApiServer apiServer = new ApiServer(controller);

        try {
            // Start the existing evaluation worker.
            controller.start();

            // Start REST API using the SAME SystemController instance.
            apiServer.start();

            System.out.println(
                    "[Main] REST API is running at http://localhost:"
                            + apiServer.getPort()
                            + "/api"
            );

            System.out.println(
                    "[Main] Evaluation endpoint: POST http://localhost:"
                            + apiServer.getPort()
                            + "/api/evaluate"
            );

            System.out.println(
                    "[Main] Health endpoint: GET http://localhost:"
                            + apiServer.getPort()
                            + "/api/health"
            );

            System.out.println(
                    "[Main] REST API is ready for manual testing."
            );

            System.out.println(
                    "[Main] Press Ctrl+C to stop the application."
            );

            // Keep the application alive for manual REST API testing.
            while (true) {
                Thread.sleep(1000);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            System.out.println(
                    "[Main] Main thread interrupted. Shutting down..."
            );

        } catch (Exception e) {
            System.err.println(
                    "[Main] Application startup failed: "
                            + e.getMessage()
            );

        } finally {
            apiServer.stop();
            controller.shutdown();
        }
    }
}