/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.xml.ws.encoding;

import com.sun.xml.stream.buffer.XMLStreamBuffer;
import com.sun.xml.ws.api.SOAPVersion;
import com.sun.xml.ws.api.message.Packet;
import com.sun.xml.ws.api.message.AttachmentSet;
import com.sun.xml.ws.api.pipe.ContentType;
import com.sun.xml.ws.message.stream.StreamHeader;
import com.sun.xml.ws.message.stream.StreamHeader12;

import javax.xml.stream.XMLStreamReader;
import java.util.Collections;
import java.util.List;
import java.io.InputStream;
import java.io.IOException;

/**
 * {@link StreamSOAPCodec} for SOAP 1.2.
 *
 * @author Paul.Sandoz@Sun.Com
 */
final class StreamSOAP12Codec extends StreamSOAPCodec {
    public static final String SOAP12_MIME_TYPE = "application/soap+xml";
    public static final String SOAP12_CONTENT_TYPE = SOAP12_MIME_TYPE+";charset=\"utf-8\"";

    private static final List<String> expectedContentTypes = Collections.singletonList(SOAP12_MIME_TYPE);

    /*package*/ StreamSOAP12Codec() {
        super(SOAPVersion.SOAP_12);
    }

    public String getMimeType() {
        return SOAP12_MIME_TYPE;
    }
    
    @Override
    protected final StreamHeader createHeader(XMLStreamReader reader, XMLStreamBuffer mark) {
        return new StreamHeader12(reader, mark);
    }

    public static final ContentTypeImpl defaultContentType =
            new ContentTypeImpl(SOAP12_CONTENT_TYPE);

    @Override
    protected ContentType getContentType(String soapAction) {
        // TODO: set accept header
        if (soapAction == null) {
            return defaultContentType;
        } else {
            return new ContentTypeImpl(SOAP12_CONTENT_TYPE + ";action=\""+soapAction+"\"");
        }
    }

    @Override
    public void decode(InputStream in, String contentType, Packet packet, AttachmentSet att ) throws IOException {
        com.sun.xml.ws.encoding.ContentType ct = new com.sun.xml.ws.encoding.ContentType(contentType);
        packet.soapAction = fixQuotesAroundSoapAction(ct.getParameter("action"));
        super.decode(in,contentType,packet,att);
    }

    private String fixQuotesAroundSoapAction(String soapAction) {
        if(soapAction != null && (!soapAction.startsWith("\"") || !soapAction.endsWith("\"")) ) {
            String fixedSoapAction = soapAction;
            if(!soapAction.startsWith("\""))
                fixedSoapAction = "\"" + fixedSoapAction;
            if(!soapAction.endsWith("\""))
                fixedSoapAction = fixedSoapAction + "\"";
            return fixedSoapAction;
        }
        return soapAction;
    }
    
    protected List<String> getExpectedContentTypes() {
        return expectedContentTypes;
    }
}