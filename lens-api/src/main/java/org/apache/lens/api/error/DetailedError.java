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


import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableSet;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlSeeAlso;
import javax.xml.bind.annotation.XmlType;
import lombok.NonNull;

@XmlSeeAlso(SupportedQuerySubmitOperations.class)
public class DetailedError<PAYLOAD> extends LensError {

  @XmlElement
  private PAYLOAD payLoad;

  DetailedError() {

  }

  public DetailedError(final LensErrorCode code, final String message,@NonNull final PAYLOAD payload) {
    this(code,message,null,payload);
  }

  public DetailedError(final LensErrorCode code, final String message, final String stackTrace, @NonNull final PAYLOAD payload) {

    super(code, message, stackTrace);
    this.payLoad = payload;
    formatMessage(payload);
  }

}
