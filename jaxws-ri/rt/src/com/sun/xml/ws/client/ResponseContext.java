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
package com.sun.xml.ws.client;

import com.sun.xml.ws.api.message.Packet;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Implements "response context" on top of {@link Packet}.
 *
 * <p>
 * This class creates a read-only {@link Map} view that
 * gets exposed to client applications after an invocation
 * is complete.
 *
 * <p>
 * The design goal of this class is to make it efficient
 * to create a new {@link ResponseContext}, at the expense
 * of making some {@link Map} operations slower. This is
 * justified because the response context is mostly just
 * used to query a few known values, and operations like
 * enumeration isn't likely.
 *
 * <p>
 * Some of the {@link Map} methods requre this class to
 * build the complete {@link Set} of properties, but we
 * try to avoid that as much as possible.
 *
 *
 * <pre>
 * TODO: are we exposing all strongly-typed fields, or
 * do they have appliation/handler scope notion?
 * </pre>
 *
 * @author Kohsuke Kawaguchi
 */
@SuppressWarnings({"SuspiciousMethodCalls"})    // IDE doesn't like me calling Map methods with key typed as Object
public class ResponseContext extends AbstractMap<String,Object> {
    private final Packet packet;

    /**
     * Lazily computed.
     */
    private Set<Map.Entry<String,Object>> entrySet;

    /**
     * @param packet
     *      The {@link Packet} to wrap.
     */
    public ResponseContext(Packet packet) {
        this.packet = packet;
    }

    public boolean containsKey(Object key) {
        if(packet.supports(key))
            return packet.containsKey(key);    // strongly typed

        if(packet.invocationProperties.containsKey(key))
            // if handler-scope, hide it
            return !packet.getHandlerScopePropertyNames(true).contains(key);

        return false;
    }

    public Object get(Object key) {
        if(packet.supports(key))
            return packet.get(key);    // strongly typed

        if(packet.getHandlerScopePropertyNames(true).contains(key))
            return null;            // no such application-scope property

        return packet.invocationProperties.get(key);
    }

    public Object put(String key, Object value) {
        // response context is read-only
        throw new UnsupportedOperationException();
    }

    public Object remove(Object key) {
        // response context is read-only
        throw new UnsupportedOperationException();
    }

    public void putAll(Map<? extends String, ? extends Object> t) {
        // response context is read-only
        throw new UnsupportedOperationException();
    }

    public void clear() {
        // response context is read-only
        throw new UnsupportedOperationException();
    }

    public Set<Entry<String, Object>> entrySet() {
        if(entrySet==null) {
            // this is where the worst case happens. we have to clone the whole properties
            // to get this view.

            // use TreeSet so that toString() sort them nicely. It's easier for apps.
            Map<String,Object> r = new HashMap<String,Object>();

            // export application-scope properties
            r.putAll(packet.invocationProperties);

            // hide handler-scope properties
            r.keySet().removeAll(packet.getHandlerScopePropertyNames(true));

            // and all strongly typed ones
            r.putAll(packet.createMapView());

            entrySet = Collections.unmodifiableSet(r.entrySet());
        }

        return entrySet;
    }

}
