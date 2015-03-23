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
package org.apache.lens.api.error;

import org.apache.commons.lang.StringUtils;

import static com.google.common.base.Preconditions.checkArgument;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlSeeAlso;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;

@EqualsAndHashCode(exclude = {"stackTrace"})
@Getter
@XmlSeeAlso(DetailedError.class)
public class LensError {

  @XmlElement(name = "code")
  private LensErrorCode lensErrorCode;

  @XmlElement
  private String message;

  @XmlElement
  private String stackTrace;

  LensError() {

  }
  public LensError(final LensErrorCode lensErrorCode, final String message) {
    this(lensErrorCode,message,null);
  }

  public LensError(@NonNull final LensErrorCode lensErrorCode, final String message, final String stackTrace) {

    checkArgument(lensErrorCode != null);
    checkArgument(StringUtils.isNotBlank(message));

    this.lensErrorCode = lensErrorCode;
    this.message = message;
    this.stackTrace = stackTrace;
  }

  public void formatMessage(final Object... args) {
    this.message = String.format(message,args);
  }
}
