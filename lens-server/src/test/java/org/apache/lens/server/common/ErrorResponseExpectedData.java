/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.lens.server.common;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import javax.ws.rs.core.Response;

import org.apache.lens.api.response.LensErrorTO;
import org.apache.lens.api.response.LensResponse;

import com.google.common.base.Optional;

public class ErrorResponseExpectedData<PAYLOAD> {

  private static final String MOCK_STACK_TRACE = "mock-stackTrace";

  private final Response.Status expectedStatus;
  private final int expectedCode;
  private final String expectedErrorMsg;
  private final Optional<PAYLOAD> expectedErrorPayload;

  public static <PAYLOAD>  ErrorResponseExpectedData composedOf(final Response.Status expectedStatus,
      final int expectedCode, final String expectedErrorMsg, final Optional<PAYLOAD> expectedErrorPayload) {

    return new ErrorResponseExpectedData(expectedStatus, expectedCode, expectedErrorMsg, expectedErrorPayload);
  }

  private ErrorResponseExpectedData(final Response.Status expectedStatus,
      final int expectedCode, final String expectedErrorMsg, final Optional<PAYLOAD> expectedErrorPayload) {

    this.expectedStatus = expectedStatus;
    this.expectedCode = expectedCode;
    this.expectedErrorMsg = expectedErrorMsg;
    this.expectedErrorPayload = expectedErrorPayload;
  }

  public void verify(final Response response) {

    /* Assert Equal Http Status Code */
    assertEquals(response.getStatus(), expectedStatus.getStatusCode());

    /* Prepare expected Payload */
    PAYLOAD expectedPayload = null;
    if (expectedErrorPayload.isPresent()) {
      expectedPayload = expectedErrorPayload.get();
    }

    /* Prepare expected LensErrorTO */
    final LensErrorTO<PAYLOAD> expectedLensErrorTO = LensErrorTO
        .composedOf(expectedCode, expectedErrorMsg, MOCK_STACK_TRACE, expectedPayload,
            null);

    LensResponse lensResponse = response.readEntity(LensResponse.class);

    /* Assert Equal LensErrorTO (excluding stack trace and payload ) */
    final LensErrorTO<PAYLOAD> actualLensErrorTO = lensResponse.getLensErrorTO();
    assertEquals(actualLensErrorTO, expectedLensErrorTO);

    /* Assert equality on payload */
    PAYLOAD actualPayload = actualLensErrorTO.getPayload();
    assertEquals(actualPayload, expectedPayload);

    /* Assert receipt of a valid stacktrace */
    assertTrue(lensResponse.isValidStackTracePresent(), "Received Lens Response:" + lensResponse);
  }

}
