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

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.lens.api.error.LensError;
import org.apache.lens.api.error.LensErrorCode;

import com.google.common.collect.ImmutableMap;

public class ErrorCollectionFactory {

  public final ErrorCollection create(final Properties properties) throws IOException {

    Map<LensErrorCode, LensError> errorCollection = new HashMap<LensErrorCode, LensError>();

    for (Map.Entry<Object, Object> entry : properties.entrySet()) {

      String errorCode = (String) entry.getKey();
      String errorMsg = (String) entry.getValue();

      LensErrorCode lensErrorCode = LensErrorCode.valueOf(errorCode);
      LensError lensError = new LensError(lensErrorCode, errorMsg);
      errorCollection.put(lensErrorCode, lensError);
    }

    ImmutableMap immutableMap = ImmutableMap.copyOf(errorCollection);
    return new ImmutableErrorCollection(immutableMap);

  }

  public final ErrorCollection create(final String configFile) throws IOException {

    InputStream in = this.getClass().getClassLoader().getResourceAsStream(configFile);
    Properties props = new Properties();
    props.load(in);

    return create(props);

  }
}

