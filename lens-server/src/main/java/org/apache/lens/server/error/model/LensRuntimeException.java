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
package org.apache.lens.server.error.model;

import static jersey.repackaged.com.google.common.base.Preconditions.checkArgument;

import org.apache.lens.api.error.LensError;
import org.apache.lens.api.error.LensErrorCode;
import org.apache.lens.api.response.ErrorResponse;

import org.apache.commons.lang3.StringUtils;

import lombok.*;

public class LensRuntimeException extends RuntimeException {

  private final LensErrorCode code;
  private String apiVersion;
  private String id;
  private LensError lensError;
  private ErrorResponse response;

  public LensRuntimeException(@NonNull final LensErrorCode code) {
    this.code = code;
  }

  public LensRuntimeException setApiVersion(final String apiVersion) {

    checkArgument(StringUtils.isNotBlank(apiVersion));

    this.apiVersion = apiVersion;
    this.response = null;
    return this;
  }

  public LensRuntimeException setId(final String id) {

    checkArgument(StringUtils.isNotBlank(apiVersion));

    this.id = id;
    this.response = null;
    return this;
  }

  public LensRuntimeException setLensError(@NonNull final LensError lensError) {

    this.lensError = lensError;
    this.response = null;
    return this;
  }

  public ErrorResponse getResponse() {

    if (response == null) {
      response = new ErrorResponse(apiVersion,id,lensError);
    }
    return response;
  }

}
