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

import org.apache.lens.api.response.LensError;

import org.apache.commons.collections.MapUtils;

import com.google.common.collect.ImmutableMap;

public class ImmutableErrorCollection implements ErrorCollection {
  
  private final ImmutableMap<LensErrorEnum,LensError> errors;

  public ImmutableErrorCollection(final ImmutableMap<LensErrorEnum, LensError> errors) {

    checkArgument(MapUtils.isNotEmpty(errors));

    /* Map should have an entry for all LensErrorEnum */
    for (LensErrorEnum errorEnum : LensErrorEnum.values()) {
      checkArgument(errors.containsKey(errorEnum));
    }

    this.errors = errors;
  }

  public String getErrorMessage(final LensErrorEnum errorEnum) {
    return getLensError(errorEnum).getMessage();
  }

  private LensError getLensError(final LensErrorEnum errorEnum) {
    return errors.get(errorEnum);
  }
}
