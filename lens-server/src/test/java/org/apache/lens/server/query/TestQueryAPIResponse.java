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

import com.google.common.base.Optional;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.lens.api.LensConf;
import org.apache.lens.api.error.LensError;
import org.apache.lens.api.error.LensErrorCode;
import org.apache.lens.api.query.EstimateResult;
import org.apache.lens.api.query.QueryCost;
import org.apache.lens.api.query.QueryHandle;
import org.apache.lens.api.response.ErrorResponse;
import org.apache.lens.api.response.SuccessResponse;
import org.apache.lens.server.LensServices;
import org.apache.lens.server.error.assembler.LensSessionIdNotProvidedExceptionMapper;
import org.apache.lens.server.error.model.LensSessionIdNotProvidedException;

import org.apache.hadoop.hive.conf.HiveConf;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.test.JerseyTestNg;
import org.glassfish.jersey.test.TestProperties;
import org.glassfish.jersey.test.inmemory.InMemoryTestContainerFactory;
import org.glassfish.jersey.test.spi.TestContainerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

@Test
public class TestQueryAPIResponse extends JerseyTestNg.ContainerPerMethodTest {


  @Override
  protected Application configure() {

    enable(TestProperties.LOG_TRAFFIC);
    enable(TestProperties.DUMP_ENTITY);

    return new ResourceConfig(QueryServiceResource.class, MultiPartFeature.class,
        LensSessionIdNotProvidedExceptionMapper.class);

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
  public void testErrorResponseWhenSessionIdNotProvided() throws InterruptedException {

    /* Setup */
    new LensServices("test").initializeErrorCollection();

    /* Prepare estimate request without a session id */
    final WebTarget target = target("queryapi/queries");
    final FormDataMultiPart mp = createFormDataMultiPart(Optional.<String>absent(),"select ID from nothing","estimate",
        new LensConf());

    /* Execute request */
    final ErrorResponse response = target.request(MediaType.APPLICATION_XML)
            .post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE)).readEntity(ErrorResponse.class);

    /* Validate returned error in response */
    final LensError expectedLensError = new LensError(LensErrorCode.SESSION_ID_NOT_PROVIDED,"Session id not provided. "
        + "Please provide a session id.");
    assertFalse(response.isLensErrorEqual(expectedLensError));

  }

  private FormDataMultiPart createFormDataMultiPart(final Optional<String> sessionId, final String query, final String operation, final LensConf lensConf) {

    final FormDataMultiPart mp = new FormDataMultiPart();
    if (sessionId.isPresent()) {
      mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), sessionId.get(), MediaType.APPLICATION_XML_TYPE));
    }
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(), query));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), operation));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), lensConf,
        MediaType.APPLICATION_XML_TYPE));
    return mp;

  }

}
