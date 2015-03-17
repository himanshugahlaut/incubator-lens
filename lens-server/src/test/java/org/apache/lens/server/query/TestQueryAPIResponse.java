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

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;

import org.apache.lens.api.LensConf;
import org.apache.lens.api.query.EstimateResult;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTestNg;
import org.testng.Assert;
import org.testng.annotations.Test;

@Test
public class TestQueryAPIResponse extends JerseyTestNg.ContainerPerMethodTest {


  /*@BeforeTest
  public void setUp() throws Exception {
    super.setUp();
    Wiser wiser = new Wiser();
    wiser.setHostname("localhost");
    wiser.setPort(25000);
  }*/

  @Override
  protected Application configure() {
    return new ResourceConfig(QueryServiceResource.class, MultiPartFeature.class);
  }

  @Override
  protected void configureClient(ClientConfig config) {
    config.register(MultiPartFeature.class);
  }

  @Test
  public void test() {

    final WebTarget target = target("queryapi/queries");

    // estimate native query
    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(
        new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(), "", MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(), "select ID from test_table"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(), "estimate"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("conf").fileName("conf").build(), new LensConf(),
        MediaType.APPLICATION_XML_TYPE));

    final EstimateResult result = target.request()
        .post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), EstimateResult.class);

    Assert.assertNotNull(result);
    Assert.assertFalse(result.isError());
    Assert.assertNotNull(result.getCost());
    Assert.assertEquals(result.getCost().getEstimatedExecTimeMillis(), 1L);
    Assert.assertEquals(result.getCost().getEstimatedResourceUsage(), 1.0);

  }
}
