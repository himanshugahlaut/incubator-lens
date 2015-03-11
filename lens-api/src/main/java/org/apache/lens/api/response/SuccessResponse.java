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
package org.apache.lens.api.response;


import static com.google.common.base.Preconditions.checkArgument;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlSeeAlso;

import org.apache.lens.api.query.QuerySubmitResult;

import lombok.Getter;

@XmlRootElement
@Getter
@XmlSeeAlso({QuerySubmitResult.class})
public class SuccessResponse<DATA> extends LensResponse {

  @XmlElement
  private DATA data;

  SuccessResponse() {

  }

  public SuccessResponse(final String apiVersion, final String id, final DATA data) {
    super(apiVersion, id);
    checkArgument(data != null);
    this.data = data;
  }

}
