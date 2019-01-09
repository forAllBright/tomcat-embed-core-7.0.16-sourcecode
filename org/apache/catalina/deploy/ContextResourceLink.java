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


package org.apache.catalina.deploy;


/**
 * Representation of a resource link for a web application, as
 * represented in a <code>&lt;ResourceLink&gt;</code> element in the
 * server configuration file.
 *
 * @author Remy Maucherat
 * @author Peter Rossbach (Peter Rossbach (pero@apache.org))
 * @version $Id: ContextResourceLink.java 939305 2010-04-29 13:43:39Z kkolinko $
 */

public class ContextResourceLink extends ResourceBase {

    private static final long serialVersionUID = 1L;

    // ------------------------------------------------------------- Properties

   /**
     * The global name of this resource.
     */
    private String global = null;
    /**
     * The factory to be used for creating the object
     */
    private String factory = null;

    public String getGlobal() {
        return (this.global);
    }

    public void setGlobal(String global) {
        this.global = global;
    }

    public String getFactory() {
        return factory;
    }

    public void setFactory(String factory) {
        this.factory = factory;
    }
    // --------------------------------------------------------- Public Methods


    /**
     * Return a String representation of this object.
     */
    @Override
    public String toString() {

        StringBuilder sb = new StringBuilder("ContextResourceLink[");
        sb.append("name=");
        sb.append(getName());
        if (getType() != null) {
            sb.append(", type=");
            sb.append(getType());
        }
        if (getGlobal() != null) {
            sb.append(", global=");
            sb.append(getGlobal());
        }
        sb.append("]");
        return (sb.toString());

    }

}
