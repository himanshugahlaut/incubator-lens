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

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlEnumValue;
import javax.xml.bind.annotation.XmlType;

@XmlType
@XmlEnum(Integer.class)
public enum LensErrorCode {

  @XmlEnumValue("1001")
  USER_NAME_NOT_PROVIDED(1001),
  @XmlEnumValue("1002")
  INVAID_USERNAME_OR_PASSWORD(1002),
  @XmlEnumValue("1003")
  SESSION_ID_NOT_PROVIDED(1003);

  public int getValue() {
    return this.code;
  }

  public static LensErrorCode getLensErrorCode(final int integerCode) {

    for (LensErrorCode lensErrorCode : LensErrorCode.values()) {
      if (integerCode == lensErrorCode.getValue()) {
        return lensErrorCode;
      }
    }

    throw new IllegalArgumentException("No valid enum constant found for input integer code: " + integerCode);
  }

  private LensErrorCode(final int code) {
    this.code = code;
  }

  private final int code;

}
