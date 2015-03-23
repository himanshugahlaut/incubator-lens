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

import org.apache.lens.api.error.LensError;
import org.apache.lens.api.error.LensErrorCode;
import org.apache.lens.api.response.ErrorResponse;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
public class LensSessionIdNotProvidedException extends RuntimeException {

  private final LensErrorCode code = LensErrorCode.SESSION_ID_NOT_PROVIDED;
  private ErrorResponse response;

  public void buildResponse(final String apiVersion, final String id, final LensError lensError) {
    response = new ErrorResponse(apiVersion, id, lensError);
  }

}
