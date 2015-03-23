package org.apache.lens.api.error;

import static org.apache.lens.api.query.SubmitOp.*;

import java.util.LinkedList;
import java.util.List;

import org.apache.lens.api.query.SubmitOp;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import javax.xml.bind.annotation.*;

public class SupportedQuerySubmitOperations {

  private static final String SEP = ", ";

  @XmlElement(name = "supportedOperation")
  private List<String> supportedOps = new LinkedList<String>();

  public SupportedQuerySubmitOperations() {

    ImmutableSet<SubmitOp> supportedOps = Sets.immutableEnumSet(ESTIMATE,EXECUTE,EXPLAIN,EXECUTE_WITH_TIMEOUT);

    for (SubmitOp supportedOp : supportedOps) {
      this.supportedOps.add(supportedOp.toString().toLowerCase());
    }
  }

  public String toString() {

    StringBuilder sb = new StringBuilder();
    String sep = "";
    for (String op : supportedOps) {
      sb.append(sep).append(op);
      sep = SEP;
    }
    return sb.toString();
  }

}
