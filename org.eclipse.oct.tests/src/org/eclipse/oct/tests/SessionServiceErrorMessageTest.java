/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.oct.internal.SessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link SessionService#describeConnectionError}. Covers
 * the shape actually seen when the OCT server is unreachable: an
 * {@link ExecutionException} whose message carries the Node.js
 * {@code TypeError: fetch failed} text surfaced by the RPC layer.
 */
class SessionServiceErrorMessageTest {

	private static final String SERVER_URL = "https://oct.example.com";

	@Test
	@DisplayName("fetch failed is reported as an unreachable server")
	void fetchFailedIsReportedAsUnreachable() {
		Exception e = new ExecutionException(
				new RuntimeException("Request room/createRoom failed with message: Failed to create room: TypeError: fetch failed"));
		assertEquals("Server " + SERVER_URL + " is not reachable.",
				SessionService.describeConnectionError(e, SERVER_URL));
	}

	@Test
	@DisplayName("connection refused nested in a cause is reported as an unreachable server")
	void connectionRefusedInCauseIsReportedAsUnreachable() {
		Exception e = new RuntimeException("wrapped", new RuntimeException("connect ECONNREFUSED 127.0.0.1:8100"));
		assertEquals("Server " + SERVER_URL + " is not reachable.",
				SessionService.describeConnectionError(e, SERVER_URL));
	}

	@Test
	@DisplayName("timeout is reported as no response in time")
	void timeoutIsReportedAsNoResponse() {
		assertEquals("Server " + SERVER_URL + " did not respond in time.",
				SessionService.describeConnectionError(new TimeoutException(), SERVER_URL));
	}

	@Test
	@DisplayName("unrelated errors fall back to the raw exception message")
	void unrelatedErrorFallsBackToRawMessage() {
		Exception e = new RuntimeException("Room is full");
		assertEquals("Error: Room is full", SessionService.describeConnectionError(e, SERVER_URL));
	}
}
