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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import org.apache.lens.api.error.LensError;
import org.apache.lens.api.error.LensErrorCode;

import org.apache.commons.collections.MapUtils;

import com.google.common.collect.ImmutableMap;
import lombok.NonNull;

public class ImmutableErrorCollection implements ErrorCollection {

  private final ImmutableMap<LensErrorCode, LensError> errors;

  public ImmutableErrorCollection(final ImmutableMap<LensErrorCode, LensError> errors) {

    checkArgument(MapUtils.isNotEmpty(errors));

    /* Map should have a mapping for every LensErrorCode */
    for (LensErrorCode errorEnum : LensErrorCode.values()) {
      checkState(errors.containsKey(errorEnum),"Map does not contain mapping for lensErrorCode: %s",
          errorEnum.getValue());
    }

    this.errors = errors;
  }

  @Override
  public LensError getLensError(@NonNull final LensErrorCode errorCode) {
    return errors.get(errorCode);
  }

}
