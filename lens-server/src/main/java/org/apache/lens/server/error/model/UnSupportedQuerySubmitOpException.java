package org.apache.lens.server.error.model;

import static org.apache.lens.api.query.SubmitOp.*;
import static org.apache.lens.api.error.LensErrorCode.UNSUPPORTED_QUERY_SUBMIT_OPERATION;

import org.apache.lens.api.query.SubmitOp;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

public class UnSupportedQuerySubmitOpException extends LensRuntimeException {

  private final ImmutableSet<SubmitOp> supportedOps =
      Sets.immutableEnumSet(ESTIMATE,EXECUTE,EXPLAIN,EXECUTE_WITH_TIMEOUT);

  public UnSupportedQuerySubmitOpException() {
    super(UNSUPPORTED_QUERY_SUBMIT_OPERATION);
  }
}
