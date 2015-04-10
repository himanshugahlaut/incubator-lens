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
package org.apache.lens.api;

import static org.apache.lens.api.error.LensCommonErrorCode.INTERNAL_SERVER_ERROR;

import static com.google.common.base.Preconditions.checkArgument;

import org.apache.lens.api.error.ErrorCollection;
import org.apache.lens.api.error.LensError;
import org.apache.lens.api.response.LensErrorTO;
import org.apache.lens.api.response.LensResponse;

import org.apache.commons.lang.exception.ExceptionUtils;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;

/**
 * The Class LensException.
 */
@SuppressWarnings("serial")
@EqualsAndHashCode(callSuper = false)
public class LensException extends Exception {

  private static final int DEFAULT_LENS_EXCEPTION_ERROR_CODE = INTERNAL_SERVER_ERROR.getValue();

  @Getter
  private final int errorCode;
  private Object[] errorMsgFormattingArgs = new Object[0];

  @Getter
  private LensResponse lensResponse;

  /*
   * The lensError instance initialized and cached by {@link#getLensErrorConf()}
   * */
  private LensError lensError;

  /**
   * Constructs a new Lens Exception.
   *
   * @see Exception#Exception(String)
   */
  public LensException(String msg) {
    super(msg);
    errorCode = DEFAULT_LENS_EXCEPTION_ERROR_CODE;
  }

  /**
   * Constructs a new Lens Exception.
   *
   * @see Exception#Exception(String, Throwable)
   */
  public LensException(String msg, Throwable cause) {
    super(msg, cause);
    errorCode = DEFAULT_LENS_EXCEPTION_ERROR_CODE;
  }

  /**
   * Constructs a new Lens Exception.
   *
   * @see Exception#Exception()
   */
  public LensException() {
    super();
    errorCode = DEFAULT_LENS_EXCEPTION_ERROR_CODE;
  }

  /**
   * Constructs a new Lens Exception.
   *
   * @see Exception#Exception(Throwable)
   */
  public LensException(Throwable cause) {
    super(cause);
    errorCode = DEFAULT_LENS_EXCEPTION_ERROR_CODE;
  }

  /**
   * Constructs a new Lens Exception with error code.
   *
   * @see Exception#Exception()
   */
  public LensException(final int errorcode) {
    super();
    checkArgument(errorcode > 0);
    errorCode = errorcode;
  }

  /**
   * Constructs a new Lens Exception with error code and cause.
   *
   * @see Exception#Exception(Throwable)
   */
  public LensException(final int errorcode, final Throwable cause) {
    super(cause);
    checkArgument(errorcode > 0);
    errorCode = errorcode;
  }

  /**
   * Constructs a new Lens Exception with error code, cause and error msg formatting arguments.
   *
   * @see Exception#Exception(Throwable)
   */
  public LensException(final int errorcode, final Throwable cause, @NonNull final Object... errorMsgFormattingArgs) {
    super(cause);
    checkArgument(errorcode > 0);
    this.errorCode = errorcode;
    this.errorMsgFormattingArgs = errorMsgFormattingArgs;
  }

  public final void buildLensErrorResponse(final ErrorCollection errorCollection,
      final String apiVersion, final String id) {

    final LensError lensError = getLensErrorConf(errorCollection);
    final LensErrorTO lensErrorTO = getLensErrorTO(errorCollection);
    lensResponse = LensResponse.composedOf(apiVersion, id, lensErrorTO, lensError.getHttpStatusCode());

  }

  public LensErrorTO getLensErrorTO(final ErrorCollection errorCollection) {

    final LensError lensError = getLensErrorConf(errorCollection);
    final String formattedErrorMsg = getFormattedErrorMsg(lensError);
    final String stackTrace = getStackTraceString();

    return createLensErrorTO(errorCollection, formattedErrorMsg, stackTrace);
  }

  protected String getFormattedErrorMsg(LensError lensError) {

    return lensError.getFormattedErrorMsg(errorMsgFormattingArgs);
  }

  protected LensErrorTO createLensErrorTO(final ErrorCollection errorCollection, final String errorMsg,
      final String stackTrace) {

    return LensErrorTO.composedOf(errorCode, errorMsg, stackTrace, null, null);
  }

  private String getStackTraceString() {
    return ExceptionUtils.getStackTrace(this);
  }

  private LensError getLensErrorConf(final ErrorCollection errorCollection) {

    if (lensError == null) {
      lensError = errorCollection.getLensError(errorCode);
    }
    return lensError;
  }

}
