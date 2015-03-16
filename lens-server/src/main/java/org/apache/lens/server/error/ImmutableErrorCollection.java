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
package org.apache.lens.server.error;

import static com.google.common.base.Preconditions.checkArgument;

import org.apache.lens.api.error.LensError;
import org.apache.lens.api.error.LensErrorCode;

import org.apache.commons.collections.MapUtils;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

public class ImmutableErrorCollection implements ErrorCollection {

  private final ImmutableMap<LensErrorCode, LensError> errors;

  public ImmutableErrorCollection(final ImmutableMap<LensErrorCode, LensError> errors) {

    Preconditions.checkArgument(MapUtils.isNotEmpty(errors));

    /* Map should have a mapping for every LensErrorCode */
    for (LensErrorCode errorEnum : LensErrorCode.values()) {
      checkArgument(errors.containsKey(errorEnum));
    }

    this.errors = errors;
  }

  @Override public RuntimeException createLensServerException(final LensErrorCode errorCode) {

    final LensError lensError = getLensError(errorCode);
    RuntimeException lensServerException = null;

    switch (errorCode) {
    case INVALID_SESSION_ID:
      lensServerException = new LensBadRequestException(lensError);
    default:
      lensServerException = new UnsupportedOperationException(
          "This should never happen. " + "Please handle all possible error codes in switch block");
    }

    return lensServerException;
  }

  //@Override
  private String getErrorMessage(final LensErrorCode errorCode) {
    return getLensError(errorCode).getMessage();
  }

  //@Override
  private LensError getLensError(final LensErrorCode errorCode) {
    checkArgument(errorCode != null);
    return errors.get(errorCode);
  }

}