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
package org.apache.lens.cube.parse;

import static org.apache.lens.cube.parse.CubeTestSetup.TWO_DAYS_RANGE;

import static org.testng.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.lens.cube.error.ConflictingFields;
import org.apache.lens.cube.error.FieldsCannotBeQueriedTogetherException;
import org.apache.lens.server.api.error.LensException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.ql.parse.ParseException;
import org.apache.hadoop.hive.ql.parse.SemanticException;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class FieldsCannotBeQueriedTogetherTest extends TestQueryRewrite {

  private Configuration conf = new Configuration();

  @BeforeClass
  public void initialiezConf() {
    conf.setBoolean(CubeQueryConfUtil.ENABLE_SELECT_TO_GROUPBY, true);
  }

  @Test
  public void testQueryWithDimensionAndMeasure() throws SemanticException, ParseException, LensException {

    try {
      /* If all the queried dimensions are present in a derived cube, and one of the queried measure is not present in
      the same derived cube, then query shall be disallowed.

      dim2 and msr1 are not present in the same derived cube, hence query shall be disallowed with appropriate
      exception. */

      rewrite("select dim2, SUM(msr1) from basecube where " + TWO_DAYS_RANGE, conf);
    } catch(FieldsCannotBeQueriedTogetherException actualException) {

      FieldsCannotBeQueriedTogetherException expectedException = createExpectedException(Arrays.asList("dim2", "msr1"));
      assertEquals(actualException, expectedException);
    }
  }

  @Test
  public void testQueryWithReferencedDimensionAttributeAndMeasure() throws SemanticException, ParseException,
      LensException {

    try {
      /* If the source column for a referenced dimension attribute and the queried measure are not present in the same
      derived cube, then query shall be disallowed.

      cityState.name is a referenced dimension attribute queryable through chain source column cityid. cityid and msr1
      are not present in the same derived cube, hence query shall be disallowed with appropriate exception. */

      rewrite("select cityState.name, SUM(msr1) from basecube where " + TWO_DAYS_RANGE, conf);
    } catch(FieldsCannotBeQueriedTogetherException actualException) {

      FieldsCannotBeQueriedTogetherException expectedException = createExpectedException(Arrays.asList("citystate.name",
          "msr1"));
      assertEquals(actualException, expectedException);
    }
  }

  @Test
  public void testQueryWithMeasureAndReferencedDimensionAttributeInFilter() throws SemanticException, ParseException,
      LensException {

    try {
      /* If the source column for a referenced dimension attribute used in filter and the queried measure are not
      present in the same derived cube, then query shall be disallowed.

      cityState.name is a referenced dimension attribute used in where clause(filter). It is queryable through chain
      source column cityid. cityid and msr1 are not present in the same derived cube, hence query shall be disallowed
      with appropriate exception. */

      rewrite("select SUM(msr1) from basecube where cityState.name = 'foo' and " + TWO_DAYS_RANGE, conf);
    } catch(FieldsCannotBeQueriedTogetherException actualException) {

      FieldsCannotBeQueriedTogetherException expectedException = createExpectedException(Arrays.asList("citystate.name",
          "msr1"));
      assertEquals(actualException, expectedException);
    }
  }

  @Test
  public void testQueryWithOnlyMeasure() throws ParseException, SemanticException, LensException {

    /* A query which contains only measure should pass, if the measure is present in some derived cube.
    msr1 is present in one of the derived cubes, hence query shall pass without any exception. */

    rewrite("select SUM(msr1) from basecube where " + TWO_DAYS_RANGE, conf);
  }

  @Test
  public void testQueryWithMeasureAndReferencedDimAttributeInCaseStatement() throws ParseException,
      SemanticException, LensException {

    /* A query which contains referenced dim attribute in case statement and a measure is allowed even if
    the source column of referenced dim attribute and the queried measure are not present in the same derived cube.

    cityState.name is queryable through source column basecube.cityid. basecube.cityid and msr1 are not present in the
    same derived cube. However since cityState.name is only present in the case statement, the query is allowed.
    */

    rewrite("select SUM(CASE WHEN cityState.name ='foo' THEN msr1 END) from basecube where " + TWO_DAYS_RANGE, conf);
  }

  private FieldsCannotBeQueriedTogetherException createExpectedException(final List<String> conflictingFields) {

    SortedSet<String> expectedFields = new TreeSet<String>(conflictingFields);
    return new FieldsCannotBeQueriedTogetherException(new ConflictingFields(expectedFields));
  }
}
