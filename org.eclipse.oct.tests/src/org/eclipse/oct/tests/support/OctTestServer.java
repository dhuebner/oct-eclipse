/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests.support;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JVM-wide singleton that spawns the real {@code open-collaboration-server}
 * Node.js process for the duration of the test run.
 *
 * <p>Uses simple login (no OAuth) and a fixed dev JWT key so tests are
 * deterministic. The server listens on {@code localhost:8100} to match the
 * TypeScript integration test {@code service.process.test.ts}.
 *
 * <p>Started lazily on first {@link #ensureStarted()} call and reused across
 * every test class. Torn down via a JVM shutdown hook, so tycho-surefire can
 * run the entire test module against a single server instance.
 */
public final class OctTestServer {

	private static final Logger LOG = Logger.getLogger(OctTestServer.class.getName());

	// Bind explicitly on 127.0.0.1 (IPv4) — Node's default `localhost` binding on
	// macOS resolves to IPv6-only, which Java's HTTP stack refuses to connect to.
	private static final String HOST = "127.0.0.1";
	private static final int PORT = 8100;
	private static final String URL = "http://" + HOST + ":" + PORT;
	private static final String READY_MARKER = "listening on";
	private static final long STARTUP_TIMEOUT_SEC = 60;

	private static OctTestServer instance;

	private final Process process;

	private OctTestServer(Process process) {
		this.process = process;
	}

	public static synchronized OctTestServer ensureStarted() {
		if (instance != null && instance.process.isAlive()) {
			return instance;
		}
		try {
			instance = start();
			Runtime.getRuntime().addShutdownHook(new Thread(OctTestServer::stopQuietly, "oct-test-server-shutdown"));
			return instance;
		} catch (IOException | InterruptedException e) {
			throw new IllegalStateException("Failed to start OCT test server", e);
		}
	}

	public String serverUrl() {
		return URL;
	}

	public boolean isAlive() {
		return process.isAlive();
	}

	private static OctTestServer start() throws IOException, InterruptedException {
		Path serverScript = resolveServerScript();
		ProcessBuilder pb = new ProcessBuilder("node", serverScript.toString(), "--hostname=" + HOST);
		pb.environment().put("OCT_ACTIVATE_SIMPLE_LOGIN", "true");
		pb.environment().put("OCT_JWT_PRIVATE_KEY", "oct-eclipse-tests-key");
		pb.redirectErrorStream(true);
		LOG.info("Starting OCT server: node " + serverScript + " --hostname=" + HOST);
		Process p = pb.start();

		CountDownLatch ready = new CountDownLatch(1);
		Thread reader = new Thread(() -> {
			try (BufferedReader br = new BufferedReader(
					new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = br.readLine()) != null) {
					LOG.fine("[oct-server] " + line);
					if (line.contains(READY_MARKER)) {
						ready.countDown();
					}
				}
			} catch (IOException e) {
				LOG.log(Level.WARNING, "OCT server stream ended", e);
			}
		}, "oct-test-server-stdout");
		reader.setDaemon(true);
		reader.start();

		if (!ready.await(STARTUP_TIMEOUT_SEC, TimeUnit.SECONDS)) {
			p.destroyForcibly();
			throw new IllegalStateException(
					"OCT server did not report '" + READY_MARKER + "' within " + STARTUP_TIMEOUT_SEC + "s");
		}
		LOG.info("OCT server is ready at " + URL);
		return new OctTestServer(p);
	}

	private static Path resolveServerScript() {
		String projectPath = System.getProperty("oct.project.path");
		if (projectPath == null || projectPath.isBlank()) {
			// Fall back to the layout used by the parent pom: sibling checkout of
			// open-collaboration-tools next to oct-eclipse.
			projectPath = new File(System.getProperty("user.dir"))
					.toPath()
					.resolve("../../open-collaboration-tools")
					.toAbsolutePath()
					.normalize()
					.toString();
		}
		Path script = Path.of(projectPath, "packages/open-collaboration-server/bin/server");
		if (!Files.isRegularFile(script)) {
			throw new IllegalStateException("open-collaboration-server entry point not found: " + script
					+ ". Set -Doct.project.path to the open-collaboration-tools checkout and run 'npm install && npm run build' there.");
		}
		return script;
	}

	private static synchronized void stopQuietly() {
		if (instance == null) {
			return;
		}
		try {
			instance.process.destroy();
			if (!instance.process.waitFor(5, TimeUnit.SECONDS)) {
				instance.process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			instance.process.destroyForcibly();
		} finally {
			instance = null;
		}
	}
}
