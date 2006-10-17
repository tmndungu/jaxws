/*
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License).  You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the license at
 * https://glassfish.dev.java.net/public/CDDLv1.0.html.
 * See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at https://glassfish.dev.java.net/public/CDDLv1.0.html.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * you own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * Copyright 2006 Sun Microsystems Inc. All Rights Reserved
 */

package com.sun.xml.ws.addressing;

import java.net.URI;
import java.util.List;
import java.util.Map;

import javax.xml.ws.handler.MessageContext;
import javax.xml.ws.WebServiceException;

import com.sun.istack.NotNull;
import com.sun.xml.ws.api.EndpointAddress;
import com.sun.xml.ws.api.WSBinding;
import com.sun.xml.ws.api.addressing.AddressingVersion;
import com.sun.xml.ws.api.addressing.WSEndpointReference;
import com.sun.xml.ws.api.message.HeaderList;
import com.sun.xml.ws.api.message.Packet;
import com.sun.xml.ws.api.model.wsdl.WSDLPort;
import com.sun.xml.ws.api.model.wsdl.WSDLBoundOperation;
import com.sun.xml.ws.api.model.wsdl.WSDLOperation;
import com.sun.xml.ws.api.pipe.NextAction;
import com.sun.xml.ws.api.pipe.Tube;
import com.sun.xml.ws.api.pipe.TubeCloner;
import com.sun.xml.ws.transport.http.client.HttpTransportPipe;
import com.sun.xml.ws.addressing.model.ActionNotSupportedException;
import com.sun.xml.ws.addressing.model.MapRequiredException;
import com.sun.xml.ws.resources.AddressingMessages;
import com.sun.xml.ws.binding.BindingImpl;

/**
 * @author Arun Gupta
 */
public class WsaServerTube extends WsaTube {
    public WsaServerTube(WSDLPort wsdlPort, WSBinding binding, Tube next) {
        super(wsdlPort, binding, next);
    }

    public WsaServerTube(WsaServerTube that, TubeCloner cloner) {
        super(that, cloner);
    }

    public WsaServerTube copy(TubeCloner cloner) {
        return new WsaServerTube(this, cloner);
    }

    public @NotNull NextAction processRequest(Packet request) {
        if(wsdlPort == null) {
            // Addressing is not enabled
            return doInvoke(next,request);
        }

        // Store request ReplyTo and FaultTo in requestPacket.invocationProperties
        // so that they can be used after responsePacket is received.
        // These properties are used if a fault is thrown from the subsequent Pipe/Tubes.
        AddressingVersion av = binding.getAddressingVersion();
        String replyTo = null;
        String faultTo = null;
        if (request.getMessage() != null) {
            HeaderList hl = request.getMessage().getHeaders();
            if (hl != null) {
                WSEndpointReference epr = hl.getReplyTo(av, binding.getSOAPVersion());
                if (epr != null)
                    replyTo = epr.getAddress();
                epr = hl.getFaultTo(av, binding.getSOAPVersion());
                if (epr != null)
                    faultTo = epr.getAddress();
            }
        }
        request.invocationProperties.put(REQUEST_REPLY_TO, replyTo);
        request.invocationProperties.put(REQUEST_FAULT_TO, faultTo);

        // close the transportBackChannel if it cannot be used for sending
        // back either normal or fault replies

        // if there is no FaultTo
        if (faultTo == null) {
            // default to ReplyTo

            // if ReplyTo is non-anonymous or None URI
            if (replyTo != null && (!replyTo.equals(av.getAnonymousUri()) || replyTo.equals(av.getNoneUri()))) {
                // close the transport back channel
                if (request.transportBackChannel != null) {
                    request.transportBackChannel.close();
                }
            }
        } else {
            // if both FaultTo and ReplyTo are non-anonymous
            if ((!faultTo.equals(av.getAnonymousUri()) && replyTo != null && !replyTo.equals(av.getAnonymousUri())) ||

                    // or if both FaultTo and ReplyTo are none
                    (faultTo.equals(av.getNoneUri()) && replyTo != null && replyTo.equals(av.getNoneUri()))
                    ) {
                if (request.transportBackChannel != null) {
                    request.transportBackChannel.close();
                }
            }
        }

        Packet p = validateInboundHeaders(request);

        // if one-way message and WS-A header processing fault has occurred,
        // then do no further processing
        if (p.getMessage() == null)
            return doReturnWith(p);

        // if there is a header processing fault
        if (p.getMessage().isFault()) {
            return doReturnWith(processFault(p, false));
        }

        if (replyTo != null) {
            // none ReplyTo
            if (replyTo.equals(av.getNoneUri()) &&
                    ((faultTo == null) ||
                            (!faultTo.equals(av.getAnonymousUri())))) {
                return doInvoke(next,p);
            }

            // non-anonymous ReplyTo
            if (!replyTo.equals(av.getAnonymousUri()) &&
                    ((faultTo == null) ||
                            (!faultTo.equals(av.getAnonymousUri())))) {
                return doReturnWith(processNonAnonymousReply(p, replyTo, true));
            }
        }

        return doInvoke(next,p);
    }

    public @NotNull NextAction processResponse(Packet response) {
        if (response.getMessage() != null && response.getMessage().isFault()) {
            response = processFault(response, false);
        }
        return doReturnWith(response);
    }

    /**
     * Process none and non-anonymous Fault endpoints
     *
     * @param responsePacket packet
     * @param endpointInvoked true if endpoint has been invoked, false otherwise
     * @return response packet received from endpoint
     */
    private Packet processFault(Packet responsePacket, final boolean endpointInvoked) {
        if (responsePacket.getMessage() == null)
            return responsePacket;

        HeaderList hl = responsePacket.getMessage().getHeaders();
        if (hl == null)
            return responsePacket;

        AddressingVersion av = binding.getAddressingVersion();
        String replyTo = (String)responsePacket.invocationProperties.get(REQUEST_REPLY_TO);
        String faultTo = (String)responsePacket.invocationProperties.get(REQUEST_FAULT_TO);

        if (faultTo == null) {
            // default FaultTo is ReplyTo

            if (replyTo != null) {
                // if none, then fault message is not sent back
                if (replyTo.equals(av.getNoneUri())) {
                    if (endpointInvoked) {
                        return responsePacket.createServerResponse(responsePacket.getMessage(), wsdlPort, binding);
                    }
                } else if (!replyTo.equals(av.getAnonymousUri())) {
                    // non-anonymous default FaultTo
                    return processNonAnonymousReply(responsePacket, replyTo, endpointInvoked);
                }
            }
        } else {
            // explicit FaultTo

            // if none, then fault message is not sent back
            if (faultTo.equals(av.getNoneUri())) {
                if (endpointInvoked) {
                    responsePacket.setMessage(null);
                    return responsePacket;
                }
            } else if (!faultTo.equals(av.getAnonymousUri())) {
                // non-anonymous FaultTo
                return processNonAnonymousReply(responsePacket, faultTo, endpointInvoked);
            }
        }

        return responsePacket;
    }

    /**
     * Send response to non-anonymous address
     *
     * @param packet packet
     * @param uri endpoint address
     * @param invokeEndpoint true if endpoint has been invoked, false otherwise
     * @return response received from the non-anonymous endpoint
     */
    private Packet processNonAnonymousReply(final Packet packet, final String uri, final boolean invokeEndpoint) {
        if (packet.transportBackChannel != null) {
            System.out.println(AddressingMessages.NON_ANONYMOUS_RESPONSE());
            packet.transportBackChannel.close();
        }
        Packet response = packet;
        if (invokeEndpoint) {
            //TODO:
            //response = next.process(packet);
        }

        // TODO: Use TransportFactory to create the appropriate Pipe ?
        if (!uri.startsWith("http")) {
            System.out.println(AddressingMessages.NON_ANONYMOUS_UNKNOWN_PROTOCOL(uri.substring(0, uri.indexOf("://"))));
            return packet;
        }

        System.out.println(AddressingMessages.NON_ANONYMOUS_RESPONSE_SENDING(uri));
        //ToDO should we use ServierTubeAssemblerContext's codec ??
        HttpTransportPipe tPipe = new HttpTransportPipe(((BindingImpl)binding).createCodec());
        response.endpointAddress = new EndpointAddress(URI.create(uri));
        response = tPipe.process(response);

        if (response != null) {
            Map<String, List<String>> reqHeaders = (Map<String, List<String>>) response.invocationProperties.get(MessageContext.HTTP_REQUEST_HEADERS);
            if (reqHeaders != null) {
                for (String key : reqHeaders.keySet()) {
                    System.out.print("[" + key + "]: ");
                    for (String value : reqHeaders.get(key)) {
                        System.out.print(value + " ");
                    }
                    System.out.println();
                }
            } else {
                System.out.println(AddressingMessages.NON_ANONYMOUS_RESPONSE_NULL_HEADERS(uri.toString()));
            }
        } else {
            System.out.printf(AddressingMessages.NON_ANONYMOUS_RESPONSE_NULL_MESSAGE(uri));
        }

        return response;
    }

    @Override
    public void validateAction(Packet packet) {
        //There may not be a WSDL operation.  There may not even be a WSDL.
        //For instance this may be a RM CreateSequence message.
        WSDLBoundOperation wbo = getWSDLBoundOperation(packet);

        WSDLOperation op = null;

        if (wbo != null) {
            op = wbo.getOperation();
        }

        if (wbo == null || op == null) {
            return;
        }

        String gotA = packet.getMessage().getHeaders().getAction(binding.getAddressingVersion(), binding.getSOAPVersion());

        if (gotA == null)
            throw new WebServiceException(AddressingMessages.VALIDATION_SERVER_NULL_ACTION());

        String expected = helper.getInputAction(packet);
        String soapAction = helper.getSOAPAction(packet);
        if (helper.isInputActionDefault(packet) && (soapAction != null && !soapAction.equals("")))
            expected = soapAction;

        if (expected != null && !gotA.equals(expected)) {
            throw new ActionNotSupportedException(gotA);
        }
    }

    @Override
    protected void checkMandatoryHeaders(boolean foundAction, boolean foundTo) {
        // if no wsa:Action header is found
        if (!foundAction)
            throw new MapRequiredException(binding.getAddressingVersion().actionTag);

        // if no wsa:To header is found
        // TODO: should we throw a fault for missing To since it's optional ?
        if (!foundTo)
            throw new MapRequiredException(binding.getAddressingVersion().toTag);
    }

    private static final String REQUEST_REPLY_TO = "request.replyTo";
    private static final String REQUEST_FAULT_TO = "request.faultTo";
}
