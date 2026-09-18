package com.sce.api;

/**
 * ApiConfig
 *
 * Immutable configuration for the REST API foundation. This is the
 * single point through which the API's listening port is configured —
 * no class in com.sce.api reads a port number from anywhere else, and
 * no static/global mutable configuration exists anywhere in this
 * package. Future Day 5 stages that need additional configuration
 * (e.g. request size limits, timeouts) should extend this class with
 * additional final fields, not introduce a second configuration
 * mechanism.
 *
 * PORT RESOLUTION:
 *   - The explicit constructor, ApiConfig(int port), always uses exactly
 *     the port given to it (0-65535, 0 meaning "OS-assigned ephemeral
 *     port"). It never consults the environment. This is what every
 *     existing test in this project uses (e.g. new ApiConfig(0) for an
 *     ephemeral test port, or a fixed port for local verification), and
 *     its behavior is completely unchanged by the addition below.
 *   - The no-arg constructor, ApiConfig(), is what the real application
 *     uses at startup (via ApiServer's own no-arg SystemController
 *     constructor). It now resolves the port as follows:
 *       1. If the environment variable PORT is set and contains a valid
 *          integer in the range 1-65535, that port is used. This is the
 *          convention most cloud hosting platforms (which inject a PORT
 *          environment variable at deploy time) expect an application
 *          to honor.
 *       2. Otherwise — PORT is absent, blank, non-numeric, zero,
 *          negative, or greater than 65535 — the port falls back safely
 *          to DEFAULT_PORT (8080), which is exactly the existing local
 *          development behavior this project has always had. A local
 *          run with no PORT environment variable set is therefore
 *          completely unaffected by this change.
 *
 * PORT 0 CONVENTION: passing 0 explicitly requests that the OS assign
 * any free ephemeral port, which java.net.httpserver.HttpServer
 * supports natively. This is used by test harnesses to avoid any risk
 * of port collision with a developer's locally running services. PORT=0
 * from the environment is deliberately NOT honored as an ephemeral-port
 * request (see resolution rule 1 above, which requires 1-65535) — an
 * accidental PORT=0 in a cloud environment falls back to 8080 rather
 * than silently binding to an unpredictable ephemeral port in
 * production.
 *
 * @author Smart Code Evaluator Team
 */
public final class ApiConfig {

    /** Default development port when no explicit port is supplied and
     *  no valid PORT environment variable is present. */
    public static final int DEFAULT_PORT = 8080;

    /** Name of the environment variable consulted by the no-arg
     *  constructor for cloud deployment port assignment. */
    private static final String PORT_ENV_VAR = "PORT";

    private final int port;

    /**
     * Constructs an ApiConfig using the deployment-aware default port:
     * the value of the PORT environment variable if it is present and a
     * valid integer in [1, 65535], otherwise DEFAULT_PORT (8080). This
     * preserves exact existing local-development behavior (port 8080)
     * when no PORT environment variable is set, while allowing a cloud
     * host that injects PORT to be honored automatically.
     */
    public ApiConfig() {
        this(resolvePort());
    }

    /**
     * Constructs an ApiConfig with an explicit port. Never consults the
     * environment — this constructor's behavior is unchanged from
     * before this class supported cloud deployment.
     *
     * @param port the TCP port the API server should bind to; must be in
     *              the range 0-65535, where 0 requests an OS-assigned
     *              ephemeral port
     * @throws IllegalArgumentException if port is outside [0, 65535]
     */
    public ApiConfig(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException(
                "ApiConfig: port must be between 0 and 65535 (was " + port + ")."
            );
        }
        this.port = port;
    }

    /**
     * Resolves the port for the no-arg constructor: the PORT environment
     * variable if present and valid, otherwise DEFAULT_PORT. Never
     * throws — any invalid or missing environment value safely falls
     * back to the local default rather than failing application startup.
     *
     * @return the resolved port to use
     */
    private static int resolvePort() {
        String envValue = System.getenv(PORT_ENV_VAR);
        if (envValue == null) {
            return DEFAULT_PORT;
        }

        String trimmed = envValue.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT_PORT;
        }

        int parsed;
        try {
            parsed = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }

        if (parsed < 1 || parsed > 65535) {
            return DEFAULT_PORT;
        }

        return parsed;
    }

    /** @return the configured port (0 meaning "OS-assigned ephemeral port"). */
    public int getPort() {
        return port;
    }

    @Override
    public String toString() {
        return "ApiConfig{port=" + port + '}';
    }
}