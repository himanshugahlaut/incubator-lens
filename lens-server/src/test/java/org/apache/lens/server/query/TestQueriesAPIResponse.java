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
package org.apache.lens.server.query;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.UUID;

import com.google.common.base.Optional;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;

import org.apache.lens.api.LensConf;
import org.apache.lens.api.LensSessionHandle;
import org.apache.lens.api.error.LensError;
import org.apache.lens.api.error.LensErrorCode;
import org.apache.lens.api.response.ErrorResponse;
import org.apache.lens.server.LensServices;
import org.apache.lens.server.error.assembler.LensRuntimeExceptionMapper;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTestNg;
import org.glassfish.jersey.test.TestProperties;
import org.glassfish.jersey.test.inmemory.InMemoryTestContainerFactory;
import org.glassfish.jersey.test.spi.TestContainerFactory;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

@Test
public class TestQueriesAPIResponse extends JerseyTestNg.ContainerPerClassTest {

  private static final String MOCK_LENS_SERVICES_NAME = "test-lens-services";
  private static final String PATH = "queryapi/queries";

  private static final Optional<String> OPTIONAL_MOCK_QUERY = Optional.of("mock-query");
  private static final Optional<String> OPTIONAL_ABSENT_QUERY = Optional.<String> absent();

  private static final Optional<LensSessionHandle> OPTIONAL_MOCK_LENS_SESSION_HANDLE =
      Optional.of(new LensSessionHandle(UUID.randomUUID(),UUID.randomUUID()));
  private static final Optional<LensSessionHandle> OPTIONAL_ABSENT_LENS_SESSION_HANDLE =
      Optional.<LensSessionHandle> absent();

  private static final Optional<String> OPTIONAL_ESTIMATE_OPERATION = Optional.of("estimate");
  private static final Optional<String> OPTIONAL_INVALID_OPERATION = Optional.of("invalid-operation");
  private static final Optional<String> OPTIONAL_ABSENT_OPERATION = Optional.<String> absent();

  @BeforeClass
  public void setUp() throws Exception {
    super.setUp();

    /* Setup will initialize errorCollection static variable in LensServices with contents from lens-errors.properties
    file. When Jersey container creates an instance of resource class, resource class constructor will invoke
    a method in LensServices to get a reference to this static variable */

    new LensServices(MOCK_LENS_SERVICES_NAME).initializeErrorCollection();
  }
  @Override
  protected Application configure() {

    enable(TestProperties.LOG_TRAFFIC);
    enable(TestProperties.DUMP_ENTITY);

    return new ResourceConfig(QueryServiceResource.class, MultiPartFeature.class,
        LensRuntimeExceptionMapper.class);

  }

  @Override
  protected void configureClient(ClientConfig config) {
    config.register(MultiPartFeature.class);
  }

  @Override
  protected TestContainerFactory getTestContainerFactory() {
    return new InMemoryTestContainerFactory();
  }

  @Test
  public void testErrorResponseWhenSessionIdIsAbsent() {

    testErrorResponse(OPTIONAL_ABSENT_LENS_SESSION_HANDLE, OPTIONAL_MOCK_QUERY, OPTIONAL_ESTIMATE_OPERATION,
        LensErrorCode.SESSION_ID_NOT_PROVIDED, "Session id not provided. Please provide a session id.");
  }

  @Test
  public void testErrorResponseWhenQueryIsAbsent() {

    testErrorResponse(OPTIONAL_MOCK_LENS_SESSION_HANDLE, OPTIONAL_ABSENT_QUERY, OPTIONAL_ESTIMATE_OPERATION,
        LensErrorCode.NULL_OR_EMPTY_OR_BLANK_QUERY, "Query is not provided, or it is empty or blank. "
            + "Please provide a valid query.");

  }

  @Test
  public void testErrorResponseWhenInvalidOperationIsSubmitted() {

    testErrorResponse(OPTIONAL_MOCK_LENS_SESSION_HANDLE, OPTIONAL_MOCK_QUERY, OPTIONAL_INVALID_OPERATION,
        LensErrorCode.UNSUPPORTED_QUERY_SUBMIT_OPERATION, "Provided Operation is not supported. Supported Operations "
            + "are: [estimate, execute, explain, execute_with_timeout]");
  }

  private void testErrorResponse(final Optional<LensSessionHandle> lensSessionHandle, final Optional<String> query,
      final Optional<String> operation, final LensErrorCode expectedCode, final String expectedErrorMsg) {

    /* Prepare a request with given input */
    final WebTarget target = target(PATH);
    final FormDataMultiPart mp = createFormDataMultiPart(lensSessionHandle, query, operation,
        new LensConf());

    /* Execute request */
    final ErrorResponse response = target.request(MediaType.APPLICATION_XML)
            .post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE)).readEntity(ErrorResponse.class);

    /* Validate returned error in response */
    final LensError expectedLensError = new LensError(expectedCode,expectedErrorMsg);
    assertTrue(response.isLensErrorEqual(expectedLensError));

  }

  private FormDataMultiPart createFormDataMultiPart(final Optional<LensSessionHandle> sessionId, final Optional<String> query,
      final Optional<String> operation, final LensConf lensConf) {

    final FormDataMultiPart mp = new FormDataMultiPart();

    if (sessionId.isPresent()) {
      mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), sessionId.get(),
          MediaType.APPLICATION_XML_TYPE));
    }

    if (query.isPresent()) {
      mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(), query.get()));
    }
    if (operation.isPresent()) {
      mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), operation.get()));
    }
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), lensConf,
        MediaType.APPLICATION_XML_TYPE));
    return mp;

  }

}
