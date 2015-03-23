package org.apache.lens.server.error.assembler;

import org.apache.lens.server.error.model.LensSessionIdNotProvidedException;

import com.sun.jersey.api.client.ClientResponse;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class LensSessionIdNotProvidedExceptionMapper implements ExceptionMapper<LensSessionIdNotProvidedException> {

  @Override
  public Response toResponse(LensSessionIdNotProvidedException exception) {
    return Response.status(ClientResponse.Status.BAD_REQUEST)
        .entity(exception.getResponse()).build();
  }
}
