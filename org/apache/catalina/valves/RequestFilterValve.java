/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.catalina.valves;


import java.io.IOException;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;

/**
 * Implementation of a Valve that performs filtering based on comparing the
 * appropriate request property (selected based on which subclass you choose
 * to configure into your Container's pipeline) against the regular expressions
 * configured for this Valve.
 * <p>
 * This valve is configured by setting the <code>allow</code> and/or
 * <code>deny</code> properties to a regular expressions (in the syntax
 * supported by {@link Pattern}) to which the appropriate request property will
 * be compared. Evaluation proceeds as follows:
 * <ul>
 * <li>The subclass extracts the request property to be filtered, and
 *     calls the common <code>process()</code> method.
 * <li>If there is a deny expression configured, the property will be compared
 *     to the expression. If a match is found, this request will be rejected
 *     with a "Forbidden" HTTP response.</li>
 * <li>If there is a allow expression configured, the property will be compared
 *     to each such expression.  If a match is found, this request will be
 *     allowed to pass through to the next Valve in the current pipeline.</li>
 * <li>If a deny expression was specified but no allow expression, allow this
 *     request to pass through (because none of the deny expressions matched
 *     it).
 * <li>The request will be rejected with a "Forbidden" HTTP response.</li>
 * </ul>
 * <p>
 * This Valve may be attached to any Container, depending on the granularity
 * of the filtering you wish to perform.
 *
 * @author Craig R. McClanahan
 * @version $Id: RequestFilterValve.java 1058357 2011-01-12 23:49:18Z markt $
 */

public abstract class RequestFilterValve
    extends ValveBase {

    //------------------------------------------------------ Constructor
    public RequestFilterValve() {
        super(true);
    }

    // ----------------------------------------------------- Class Variables


    /**
     * The descriptive information related to this implementation.
     */
    private static final String info =
        "org.apache.catalina.valves.RequestFilterValve/1.0";


    // ----------------------------------------------------- Instance Variables


    /**
     * The regular expression used to test for allowed requests.
     */
    protected Pattern allow = null;


    /**
     * The regular expression used to test for denied requests.
     */
    protected Pattern deny = null;


    // ------------------------------------------------------------- Properties


    /**
     * Return the regular expression used to test for allowed requests for this
     * Valve, if any; otherwise, return <code>null</code>.
     */
    public String getAllow() {
        if (allow == null) {
            return null;
        }
        return allow.toString();
    }


    /**
     * Set the regular expression used to test for allowed requests for this
     * Valve, if any.
     *
     * @param allow The new allow expression
     */
    public void setAllow(String allow) {
        if (allow == null || allow.length() == 0) {
            this.allow = null;
        } else {
            this.allow = Pattern.compile(allow);
        }
    }


    /**
     * Return the regular expression used to test for denied requests for this
     * Valve, if any; otherwise, return <code>null</code>.
     */
    public String getDeny() {
        if (deny == null) {
            return null;
        }
        return deny.toString();
    }


    /**
     * Set the regular expression used to test for denied requests for this
     * Valve, if any.
     *
     * @param deny The new deny expression
     */
    public void setDeny(String deny) {
        if (deny == null || deny.length() == 0) {
            this.deny = null;
        } else {
            this.deny = Pattern.compile(deny);
        }
    }


    /**
     * Return descriptive information about this Valve implementation.
     */
    @Override
    public String getInfo() {

        return (info);

    }


    // --------------------------------------------------------- Public Methods


    /**
     * Extract the desired request property, and pass it (along with the
     * specified request and response objects) to the protected
     * <code>process()</code> method to perform the actual filtering.
     * This method must be implemented by a concrete subclass.
     *
     * @param request The servlet request to be processed
     * @param response The servlet response to be created
     *
     * @exception IOException if an input/output error occurs
     * @exception ServletException if a servlet error occurs
     */
    @Override
    public abstract void invoke(Request request, Response response)
        throws IOException, ServletException;


    // ------------------------------------------------------ Protected Methods


    /**
     * Perform the filtering that has been configured for this Valve, matching
     * against the specified request property.
     *
     * @param property The request property on which to filter
     * @param request The servlet request to be processed
     * @param response The servlet response to be processed
     *
     * @exception IOException if an input/output error occurs
     * @exception ServletException if a servlet error occurs
     */
    protected void process(String property,
                           Request request, Response response)
        throws IOException, ServletException {

        // Check the deny patterns, if any
        if (deny != null && deny.matcher(property).matches()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        // Check the allow patterns, if any
        if (allow != null && allow.matcher(property).matches()) {
            getNext().invoke(request, response);
            return;
        }

        // Allow if denies specified but not allows
        if (deny != null && allow == null) {
            getNext().invoke(request, response);
            return;
        }

        // Deny this request
        response.sendError(HttpServletResponse.SC_FORBIDDEN);

    }
}
