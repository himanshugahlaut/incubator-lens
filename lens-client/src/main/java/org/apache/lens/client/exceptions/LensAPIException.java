package org.apache.lens.client.exceptions;

import static com.google.common.base.Preconditions.checkState;

import org.apache.lens.api.result.LensAPIResult;

public class LensAPIException extends RuntimeException {

  private LensAPIResult errorResult;

  public LensAPIException(final LensAPIResult lensAPIErrorResult) {
    checkState(lensAPIErrorResult.isErrorResult());
    this.errorResult = lensAPIErrorResult;
  }

  public int getLensAPIErrorCode() {
    return this.errorResult.getErrorCode();
  }

  public String getLensAPIErrorMessage() {
    return this.errorResult.getErrorMessage();
  }

  public String getLensAPIRequestId() {
    return this.errorResult.getId();
  }
}
