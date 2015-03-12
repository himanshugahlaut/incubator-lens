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
/*
 *
 */
package org.apache.lens.api.query;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The Class QueryCost.
 */
@XmlRootElement
/**
 * Instantiates a new query cost.
 *
 * @param estExecTimeMillis
 *          the estimated exec time millis
 * @param estResrcUsage
 *          the estimated resource usage
 */
@AllArgsConstructor
/**
 * Instantiates a new query cost.
 */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QueryCost extends QuerySubmitResult implements Comparable<QueryCost> {

  /**
   * The estimated exec time millis.
   */
  @XmlElement
  @Getter
  private long estExecTimeMillis;

  /**
   * The estimated resource usage.
   */
  @XmlElement
  @Getter
  private double estResrcUsage;

  /*
   * (non-Javadoc)
   *
   * @see java.lang.Comparable#compareTo(java.lang.Object)
   */
  @Override
  public int compareTo(QueryCost other) {
    if (estExecTimeMillis == other.estExecTimeMillis) {
      if (estResrcUsage == other.estResrcUsage) {
        return 0;
      } else {
        return (int) (estResrcUsage - other.estResrcUsage);
      }
    } else {
      return (int) (estExecTimeMillis - other.estExecTimeMillis);
    }
  }
}
