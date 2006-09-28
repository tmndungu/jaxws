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

package com.sun.xml.ws.transport.http;


import com.sun.istack.NotNull;
import com.sun.xml.ws.api.message.Message;
import com.sun.xml.ws.api.message.Packet;
import com.sun.xml.ws.api.message.ExceptionHasMessage;
import com.sun.xml.ws.api.pipe.ContentType;
import com.sun.xml.ws.api.server.Adapter;
import com.sun.xml.ws.api.server.DocumentAddressResolver;
import com.sun.xml.ws.api.server.PortAddressResolver;
import com.sun.xml.ws.api.server.SDDocument;
import com.sun.xml.ws.api.server.ServiceDefinition;
import com.sun.xml.ws.api.server.TransportBackChannel;
import com.sun.xml.ws.api.server.WSEndpoint;
import com.sun.xml.ws.resources.WsservletMessages;
import com.sun.xml.ws.util.PropertySet;
import com.sun.xml.ws.util.ByteArrayBuffer;

import java.io.ByteArrayOutputStream;

import javax.xml.ws.WebServiceException;
import javax.xml.ws.EndpointReference;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.util.*;
import java.util.Map.Entry;

/**
 * {@link Adapter} that receives messages in HTTP.
 *
 * <p>
 * This object also assigns unique query string (such as "xsd=1") to
 * each {@link SDDocument} so that they can be served by HTTP GET requests.
 *
 * @author Kohsuke Kawaguchi
 * @author Jitendra Kotamraju
 */
public class HttpAdapter extends Adapter<HttpAdapter.HttpToolkit> {

    /**
     * {@link SDDocument}s keyed by the query string like "?abc".
     * Used for serving documents via HTTP GET.
     *
     * Empty if the endpoint doesn't have {@link ServiceDefinition}.
     * Read-only.
     */
    public final Map<String,SDDocument> wsdls;

    /**
     * Reverse map of {@link #wsdls}. Read-only.
     */
    public final Map<SDDocument,String> revWsdls;

    public final HttpAdapterList<? extends HttpAdapter> owner;

    /**
     * Creates a lone {@link HttpAdapter} that does not know of any other
     * {@link HttpAdapter}s.
     *
     * This is convenient for creating an {@link HttpAdapter} for an environment
     * where they don't know each other (such as JavaSE deployment.)
     */
    public static HttpAdapter createAlone(WSEndpoint endpoint) {
        return new DummyList().createAdapter("","",endpoint);
    }

    protected HttpAdapter(WSEndpoint endpoint, HttpAdapterList<? extends HttpAdapter> owner) {
        super(endpoint);
        this.owner = owner;

        // fill in WSDL map
        ServiceDefinition sdef = this.endpoint.getServiceDefinition();
        if(sdef==null) {
            wsdls = Collections.emptyMap();
            revWsdls = Collections.emptyMap();
        } else {
            wsdls = new HashMap<String, SDDocument>();  // wsdl=1 --> Doc
            // Sort WSDL, Schema documents based on SystemId so that the same
            // document gets wsdl=x mapping
            Map<String, SDDocument> systemIds = new TreeMap<String, SDDocument>();
            for (SDDocument sdd : sdef) {
                if (sdd == sdef.getPrimary()) { // No sorting for Primary WSDL
                    wsdls.put("wsdl", sdd);     
                    wsdls.put("WSDL", sdd);
                } else {
                    systemIds.put(sdd.getURL().toString(), sdd);
                }
            }
            
            int wsdlnum = 1;
            int xsdnum = 1;
            for (Map.Entry<String, SDDocument> e : systemIds.entrySet()) {
                SDDocument sdd = e.getValue();
                if (sdd.isWSDL()) {
                    wsdls.put("wsdl="+(wsdlnum++),sdd);
                }
                if (sdd.isSchema()) {
                    wsdls.put("xsd="+(xsdnum++),sdd);
                }
            }

            revWsdls = new HashMap<SDDocument,String>();    // Doc --> wsdl=1
            for (Entry<String,SDDocument> e : wsdls.entrySet()) {
                revWsdls.put(e.getValue(),e.getKey());
            }
        }
    }


    final class HttpToolkit extends Adapter.Toolkit implements TransportBackChannel {
        private WSHTTPConnection con;
        private boolean closed;

        public void handle(WSHTTPConnection con) throws IOException {
            this.con = con;
            this.closed = false;
            String ct = con.getRequestHeader("Content-Type");
            InputStream in = con.getInput();
            Packet packet = new Packet();
            packet.acceptableMimeTypes = con.getRequestHeader("Accept");
            packet.addSatellite(con);
            try {
                if(dump) {
                ByteArrayBuffer buf = new ByteArrayBuffer();
                buf.write(in);
                dump(buf, "HTTP request", con.getRequestHeaders());
                in = buf.newInputStream();
                }
                codec.decode(in, ct, packet);
                try {
                    packet = head.process(packet,con.getWebServiceContextDelegate(),this);
                } catch(Exception e) {
                    e.printStackTrace();
                    if (!closed) {
                        writeInternalServerError(con);
                    }
                    return;
                }
            } catch(ExceptionHasMessage e) {
                e.printStackTrace();
                packet.setMessage(e.getFaultMessage());
            }

            if (closed) {
                return;                 // Connection is already closed
            }

            Message responseMessage = packet.getMessage();
            if (responseMessage==null) {
                close();
            } else {
                if(con.getStatus()==0) {
                    // if the appliation didn't set the status code,
                    // set the default one.
                    con.setStatus( responseMessage.isFault()
                        ? HttpURLConnection.HTTP_INTERNAL_ERROR
                        : HttpURLConnection.HTTP_OK );
                }

                ContentType contentType = codec.getStaticContentType(packet);
                if (contentType != null) {
                    con.setContentTypeResponseHeader(contentType.getContentType());
                    OutputStream os = con.getOutput();
                    if(dump) {
                        ByteArrayBuffer buf = new ByteArrayBuffer();
                        codec.encode(packet, buf);
                        dump(buf, "HTTP response "+con.getStatus(), con.getResponseHeaders());
                        buf.writeTo(os);
                    } else {
                        codec.encode(packet, os);
                    }
                    os.close();
                } else {
                    ByteArrayBuffer buf = new ByteArrayBuffer();
                    contentType = codec.encode(packet, buf);
                    con.setContentTypeResponseHeader(contentType.getContentType());
                    if(dump) {
                        dump(buf, "HTTP response "+con.getStatus(), con.getResponseHeaders());
                    }
                    OutputStream os = con.getOutput();
                    buf.writeTo(os);
                    os.close();
                }
            }
        }

        public void close() {
            if(!closed) {
                closed = true;
                // close the response channel now
                con.setStatus(WSHTTPConnection.ONEWAY);
                try {
                    con.getOutput().close(); // no payload
                } catch (IOException e) {
                    throw new WebServiceException(e);
                }
            }
        }
    }

    protected HttpToolkit createToolkit() {
        return new HttpToolkit();
    }

    /**
     * Receives the incoming HTTP connection and dispatches
     * it to JAX-WS. This method returns when JAX-WS completes
     * processing the request and the whole reply is written
     * to {@link WSHTTPConnection}.
     *
     * <p>
     * This method is invoked by the lower-level HTTP stack,
     * and "connection" here is an HTTP connection.
     *
     * <p>
     * To populate a request {@link Packet} with more info,
     * define {@link PropertySet.Property properties} on
     * {@link WSHTTPConnection}.
     */
    public void handle(@NotNull WSHTTPConnection connection) throws IOException {
        HttpToolkit tk = pool.take();
        try {
            tk.handle(connection);
        } finally {
            pool.recycle(tk);
        }
    }

    /**
     * Returns true if the given query string is for metadata request.
     *
     * @param query
     *      String like "xsd=1" or "perhaps=some&amp;unrelated=query".
     *      Can be null.
     */
    public final boolean isMetadataQuery(String query) {
        // we intentionally return true even if documents don't exist,
        // so that they get 404.
        return query != null && (query.equals("WSDL") || query.startsWith("wsdl") || query.startsWith("xsd="));
    }

    /**
     * Sends out the WSDL (and other referenced documents)
     * in response to the GET requests to URLs like "?wsdl" or "?xsd=2".
     *
     * @param con
     *      The connection to which the data will be sent. Must not be null.
     * @param baseAddress
     *      The requested base URL (such as "http://myhost:2045/foo/bar").
     *      Used to reference other resoures. Must not be null.
     * @param queryString
     *      The query string given by the client (which indicates
     *      what document to serve.) Can be null (but it causes an 404 not found.)
     */
    public void publishWSDL(WSHTTPConnection con, final String baseAddress, String queryString) throws IOException {
        // Workaround for a bug in http server. Read and close InputStream
        // TODO remove once the bug is fixed in http server
        InputStream in = con.getInput();
        while(in.read() != -1);
        in.close();

        SDDocument doc = wsdls.get(queryString);
        if (doc == null) {
            writeNotFoundErrorPage(con,"Invalid Request");
            return;
        }

        con.setStatus(HttpURLConnection.HTTP_OK);
        con.setContentTypeResponseHeader("text/xml");

        OutputStream os = con.getOutput();
        final PortAddressResolver portAddressResolver = owner.createPortAddressResolver(baseAddress);
        final String address = portAddressResolver.getAddressFor(endpoint.getPort().getName().getLocalPart());
        assert address != null;
        DocumentAddressResolver resolver = new DocumentAddressResolver() {
            public String getRelativeAddressFor(SDDocument current, SDDocument referenced) {
                // the map on endpoint should account for all SDDocument
                assert revWsdls.containsKey(referenced);
                return address+'?'+ revWsdls.get(referenced);
            }
        };

        doc.writeTo(portAddressResolver, resolver, os);
        os.close();
    }

    private void writeNotFoundErrorPage(WSHTTPConnection con, String message) throws IOException {
        con.setStatus(HttpURLConnection.HTTP_NOT_FOUND);
        con.setContentTypeResponseHeader("text/html; charset=UTF-8");

        PrintWriter out = new PrintWriter(new OutputStreamWriter(con.getOutput(),"UTF-8"));
        out.println("<html>");
        out.println("<head><title>");
        out.println(WsservletMessages.SERVLET_HTML_TITLE());
        out.println("</title></head>");
        out.println("<body>");
        out.println(WsservletMessages.SERVLET_HTML_NOT_FOUND(message));
        out.println("</body>");
        out.println("</html>");
        out.close();
    }

    private void writeInternalServerError(WSHTTPConnection con) throws IOException {
        con.setStatus(HttpURLConnection.HTTP_INTERNAL_ERROR);
        con.getOutput().close();        // Sets the status code
    }

    private static final class DummyList extends HttpAdapterList<HttpAdapter> {
        @Override
        protected HttpAdapter createHttpAdapter(String name, String urlPattern, WSEndpoint<?> endpoint) {
            return new HttpAdapter(endpoint, this);
        }
    }

    private void dump(ByteArrayBuffer buf, String caption, Map<String, List<String>> headers) throws IOException {
        System.out.println("---["+caption +"]---");
        for (Entry<String,List<String>> header : headers.entrySet()) {
            if(header.getValue().isEmpty()) {
                // I don't think this is legal, but let's just dump it,
                // as the point of the dump is to uncover problems.
                System.out.println(header.getValue());
            } else {
                for (String value : header.getValue()) {
                    System.out.println(header.getKey()+": "+value);
                }
            }
        }

        buf.writeTo(System.out);
        System.out.println("--------------------");
    }

    /**
     * Dumps what goes across HTTP transport.
     */
    private static final boolean dump;

    static {
        boolean b;
        try {
            b = Boolean.getBoolean(HttpAdapter.class.getName()+".dump");
        } catch( Throwable t ) {
            b = false;
        }
        dump = b;
    }
}
