/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.coyote.http11;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Locale;

import org.apache.coyote.ActionCode;
import org.apache.coyote.Request;
import org.apache.coyote.RequestInfo;
import org.apache.coyote.Response;
import org.apache.coyote.http11.filters.BufferedInputFilter;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.jni.Address;
import org.apache.tomcat.jni.SSL;
import org.apache.tomcat.jni.SSLSocket;
import org.apache.tomcat.jni.Sockaddr;
import org.apache.tomcat.jni.Socket;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.HexUtils;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.AprEndpoint;
import org.apache.tomcat.util.net.SSLSupport;
import org.apache.tomcat.util.net.SocketStatus;
import org.apache.tomcat.util.net.SocketWrapper;


/**
 * Processes HTTP requests.
 *
 * @author Remy Maucherat
 */
public class Http11AprProcessor extends AbstractHttp11Processor {


    private static final Log log = LogFactory.getLog(Http11AprProcessor.class);
    @Override
    protected Log getLog() {
        return log;
    }

    // ----------------------------------------------------------- Constructors


    public Http11AprProcessor(int headerBufferSize, AprEndpoint endpoint,
            int maxTrailerSize) {

        this.endpoint = endpoint;
        
        request = new Request();
        inputBuffer = new InternalAprInputBuffer(request, headerBufferSize);
        request.setInputBuffer(inputBuffer);

        response = new Response();
        response.setHook(this);
        outputBuffer = new InternalAprOutputBuffer(response, headerBufferSize);
        response.setOutputBuffer(outputBuffer);
        request.setResponse(response);

        initializeFilters(maxTrailerSize);

        // Cause loading of HexUtils
        HexUtils.load();
    }


    // ----------------------------------------------------- Instance Variables


    /**
     * Input.
     */
    protected InternalAprInputBuffer inputBuffer = null;


    /**
     * Output.
     */
    protected InternalAprOutputBuffer outputBuffer = null;


    /**
     * Sendfile data.
     */
    protected AprEndpoint.SendfileData sendfileData = null;


    /**
     * Comet used.
     */
    protected boolean comet = false;


    /**
     * Socket associated with the current connection.
     */
    protected SocketWrapper<Long> socket = null;


    /**
     * When client certificate information is presented in a form other than
     * instances of {@link java.security.cert.X509Certificate} it needs to be
     * converted before it can be used and this property controls which JSSE
     * provider is used to perform the conversion. For example it is used with
     * the AJP connectors, the HTTP APR connector and with the
     * {@link org.apache.catalina.valves.SSLValve}. If not specified, the
     * default provider will be used. 
     */
    protected String clientCertProvider = null;
    public String getClientCertProvider() { return clientCertProvider; }
    public void setClientCertProvider(String s) { this.clientCertProvider = s; }


    // --------------------------------------------------------- Public Methods


    /**
     * Process pipelined HTTP requests using the specified input and output
     * streams.
     *
     * @throws IOException error during an I/O operation
     */
    public SocketState event(SocketStatus status)
        throws IOException {
        
        RequestInfo rp = request.getRequestProcessor();
        
        try {
            rp.setStage(org.apache.coyote.Constants.STAGE_SERVICE);
            error = !adapter.event(request, response, status);
        } catch (InterruptedIOException e) {
            error = true;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(sm.getString("http11processor.request.process"), t);
            // 500 - Internal Server Error
            response.setStatus(500);
            adapter.log(request, response, 0);
            error = true;
        }
        
        rp.setStage(org.apache.coyote.Constants.STAGE_ENDED);

        if (error) {
            inputBuffer.nextRequest();
            outputBuffer.nextRequest();
            return SocketState.CLOSED;
        } else if (!comet) {
            inputBuffer.nextRequest();
            outputBuffer.nextRequest();
            return SocketState.OPEN;
        } else {
            return SocketState.LONG;
        }
    }
    
    /**
     * Process pipelined HTTP requests using the specified input and output
     * streams.
     *
     * @throws IOException error during an I/O operation
     */
    public SocketState process(SocketWrapper<Long> socket)
        throws IOException {
        RequestInfo rp = request.getRequestProcessor();
        rp.setStage(org.apache.coyote.Constants.STAGE_PARSE);

        // Set the remote address
        remoteAddr = null;
        remoteHost = null;
        localAddr = null;
        localName = null;
        remotePort = -1;
        localPort = -1;

        // Setting up the socket
        this.socket = socket;
        long socketRef = socket.getSocket().longValue();
        inputBuffer.setSocket(socketRef);
        outputBuffer.setSocket(socketRef);

        // Error flag
        error = false;
        comet = false;
        keepAlive = true;

        int keepAliveLeft = maxKeepAliveRequests;
        long soTimeout = endpoint.getSoTimeout();
        
        boolean keptAlive = false;
        boolean openSocket = false;

        while (!error && keepAlive && !comet && !isAsync() && !endpoint.isPaused()) {

            // Parsing the request header
            try {
                if( !disableUploadTimeout && keptAlive && soTimeout > 0 ) {
                    Socket.timeoutSet(socketRef, soTimeout * 1000);
                }
                if (!inputBuffer.parseRequestLine(keptAlive)) {
                    // This means that no data is available right now
                    // (long keepalive), so that the processor should be recycled
                    // and the method should return true
                    openSocket = true;
                    // Add the socket to the poller
                    ((AprEndpoint)endpoint).getPoller().add(socketRef);
                    if (endpoint.isPaused()) {
                        // 503 - Service unavailable
                        response.setStatus(503);
                        adapter.log(request, response, 0);
                        error = true;
                    } else {
                        break;
                    }
                }
                if (!endpoint.isPaused()) {
                    request.setStartTime(System.currentTimeMillis());
                    keptAlive = true;
                    if (!disableUploadTimeout) {
                        Socket.timeoutSet(socketRef,
                                connectionUploadTimeout * 1000);
                    }
                    inputBuffer.parseHeaders();
                }
            } catch (IOException e) {
                error = true;
                break;
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("http11processor.header.parse"), t);
                }
                // 400 - Bad Request
                response.setStatus(400);
                adapter.log(request, response, 0);
                error = true;
            }

            if (!error) {
                // Setting up filters, and parse some request headers
                rp.setStage(org.apache.coyote.Constants.STAGE_PREPARE);
                try {
                    prepareRequest();
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("http11processor.request.prepare"), t);
                    }
                    // 400 - Internal Server Error
                    response.setStatus(400);
                    adapter.log(request, response, 0);
                    error = true;
                }
            }

            if (maxKeepAliveRequests > 0 && --keepAliveLeft == 0)
                keepAlive = false;

            // Process the request in the adapter
            if (!error) {
                try {
                    rp.setStage(org.apache.coyote.Constants.STAGE_SERVICE);
                    adapter.service(request, response);
                    // Handle when the response was committed before a serious
                    // error occurred.  Throwing a ServletException should both
                    // set the status to 500 and set the errorException.
                    // If we fail here, then the response is likely already
                    // committed, so we can't try and set headers.
                    if(keepAlive && !error) { // Avoid checking twice.
                        error = response.getErrorException() != null ||
                                (!isAsync() &&
                                statusDropsConnection(response.getStatus()));
                    }
                } catch (InterruptedIOException e) {
                    error = true;
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    log.error(sm.getString("http11processor.request.process"), t);
                    // 500 - Internal Server Error
                    response.setStatus(500);
                    adapter.log(request, response, 0);
                    error = true;
                }
            }

            // Finish the handling of the request
            if (!comet && !isAsync()) {
                // If we know we are closing the connection, don't drain input.
                // This way uploading a 100GB file doesn't tie up the thread 
                // if the servlet has rejected it.
                if(error)
                    inputBuffer.setSwallowInput(false);
                endRequest();
            }

            // If there was an error, make sure the request is counted as
            // and error, and update the statistics counter
            if (error) {
                response.setStatus(500);
            }
            request.updateCounters();

            if (!comet && !isAsync()) {
                // Next request
                inputBuffer.nextRequest();
                outputBuffer.nextRequest();
            }
            
            // Do sendfile as needed: add socket to sendfile and end
            if (sendfileData != null && !error) {
                sendfileData.socket = socketRef;
                sendfileData.keepAlive = keepAlive;
                if (!((AprEndpoint)endpoint).getSendfile().add(sendfileData)) {
                    openSocket = true;
                    break;
                }
            }
            
            rp.setStage(org.apache.coyote.Constants.STAGE_KEEPALIVE);

        }

        rp.setStage(org.apache.coyote.Constants.STAGE_ENDED);

        if (error || endpoint.isPaused()) {
            return SocketState.CLOSED;
        } else if (comet  || isAsync()) {
            return SocketState.LONG;
        } else {
            return (openSocket) ? SocketState.OPEN : SocketState.CLOSED;
        }
        
    }

    /* Copied from the AjpProcessor.java */
    public SocketState asyncDispatch(SocketWrapper<Long> socket,
            SocketStatus status) {

        // Setting up the socket
        this.socket = socket;
        long socketRef = socket.getSocket().longValue();
        inputBuffer.setSocket(socketRef);
        outputBuffer.setSocket(socketRef);

        RequestInfo rp = request.getRequestProcessor();
        try {
            rp.setStage(org.apache.coyote.Constants.STAGE_SERVICE);
            error = !adapter.asyncDispatch(request, response, status);
        } catch (InterruptedIOException e) {
            error = true;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(sm.getString("http11processor.request.process"), t);
            // 500 - Internal Server Error
            response.setStatus(500);
            adapter.log(request, response, 0);
            error = true;
        }

        rp.setStage(org.apache.coyote.Constants.STAGE_ENDED);

        if (error) {
            return SocketState.CLOSED;
        } else if (isAsync()) {
            return SocketState.LONG;
        } else {
            if (!keepAlive) {
                return SocketState.CLOSED;
            } else {
                return SocketState.OPEN;
            }
        }
    }


    @Override
    public void recycleInternal() {
        this.socket = null;
    }
    

    // ----------------------------------------------------- ActionHook Methods


    /**
     * Send an action to the connector.
     *
     * @param actionCode Type of the action
     * @param param Action parameter
     */
    @Override
    public void actionInternal(ActionCode actionCode, Object param) {

        long socketRef = socket.getSocket().longValue();
        
        if (actionCode == ActionCode.CLOSE) {
            // Close

            // End the processing of the current request, and stop any further
            // transactions with the client

            comet = false;
            try {
                outputBuffer.endRequest();
            } catch (IOException e) {
                // Set error flag
                error = true;
            }

        } else if (actionCode == ActionCode.REQ_HOST_ADDR_ATTRIBUTE) {

            // Get remote host address
            if (remoteAddr == null && (socketRef != 0)) {
                try {
                    long sa = Address.get(Socket.APR_REMOTE, socketRef);
                    remoteAddr = Address.getip(sa);
                } catch (Exception e) {
                    log.warn(sm.getString("http11processor.socket.info"), e);
                }
            }
            request.remoteAddr().setString(remoteAddr);

        } else if (actionCode == ActionCode.REQ_LOCAL_NAME_ATTRIBUTE) {

            // Get local host name
            if (localName == null && (socketRef != 0)) {
                try {
                    long sa = Address.get(Socket.APR_LOCAL, socketRef);
                    localName = Address.getnameinfo(sa, 0);
                } catch (Exception e) {
                    log.warn(sm.getString("http11processor.socket.info"), e);
                }
            }
            request.localName().setString(localName);

        } else if (actionCode == ActionCode.REQ_HOST_ATTRIBUTE) {

            // Get remote host name
            if (remoteHost == null && (socketRef != 0)) {
                try {
                    long sa = Address.get(Socket.APR_REMOTE, socketRef);
                    remoteHost = Address.getnameinfo(sa, 0);
                    if (remoteHost == null) {
                        remoteHost = Address.getip(sa);
                    }
                } catch (Exception e) {
                    log.warn(sm.getString("http11processor.socket.info"), e);
                }
            }
            request.remoteHost().setString(remoteHost);

        } else if (actionCode == ActionCode.REQ_LOCAL_ADDR_ATTRIBUTE) {

            // Get local host address
            if (localAddr == null && (socketRef != 0)) {
                try {
                    long sa = Address.get(Socket.APR_LOCAL, socketRef);
                    localAddr = Address.getip(sa);
                } catch (Exception e) {
                    log.warn(sm.getString("http11processor.socket.info"), e);
                }
            }

            request.localAddr().setString(localAddr);

        } else if (actionCode == ActionCode.REQ_REMOTEPORT_ATTRIBUTE) {

            // Get remote port
            if (remotePort == -1 && (socketRef != 0)) {
                try {
                    long sa = Address.get(Socket.APR_REMOTE, socketRef);
                    Sockaddr addr = Address.getInfo(sa);
                    remotePort = addr.port;
                } catch (Exception e) {
                    log.warn(sm.getString("http11processor.socket.info"), e);
                }
            }
            request.setRemotePort(remotePort);

        } else if (actionCode == ActionCode.REQ_LOCALPORT_ATTRIBUTE) {

            // Get local port
            if (localPort == -1 && (socketRef != 0)) {
                try {
                    long sa = Address.get(Socket.APR_LOCAL, socketRef);
                    Sockaddr addr = Address.getInfo(sa);
                    localPort = addr.port;
                } catch (Exception e) {
                    log.warn(sm.getString("http11processor.socket.info"), e);
                }
            }
            request.setLocalPort(localPort);

        } else if (actionCode == ActionCode.REQ_SSL_ATTRIBUTE ) {

            if (endpoint.isSSLEnabled() && (socketRef != 0)) {
                try {
                    // Cipher suite
                    Object sslO = SSLSocket.getInfoS(socketRef, SSL.SSL_INFO_CIPHER);
                    if (sslO != null) {
                        request.setAttribute(SSLSupport.CIPHER_SUITE_KEY, sslO);
                    }
                    // Get client certificate and the certificate chain if present
                    // certLength == -1 indicates an error
                    int certLength = SSLSocket.getInfoI(socketRef, SSL.SSL_INFO_CLIENT_CERT_CHAIN);
                    byte[] clientCert = SSLSocket.getInfoB(socketRef, SSL.SSL_INFO_CLIENT_CERT);
                    X509Certificate[] certs = null;
                    if (clientCert != null  && certLength > -1) {
                        certs = new X509Certificate[certLength + 1];
                        CertificateFactory cf;
                        if (clientCertProvider == null) {
                            cf = CertificateFactory.getInstance("X.509"); 
                        } else {
                            cf = CertificateFactory.getInstance("X.509",
                                    clientCertProvider); 
                        }
                        certs[0] = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(clientCert));
                        for (int i = 0; i < certLength; i++) {
                            byte[] data = SSLSocket.getInfoB(socketRef, SSL.SSL_INFO_CLIENT_CERT_CHAIN + i);
                            certs[i+1] = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(data));
                        }
                    }
                    if (certs != null) {
                        request.setAttribute(SSLSupport.CERTIFICATE_KEY, certs);
                    }
                    // User key size
                    sslO = Integer.valueOf(SSLSocket.getInfoI(socketRef,
                            SSL.SSL_INFO_CIPHER_USEKEYSIZE));
                    request.setAttribute(SSLSupport.KEY_SIZE_KEY, sslO);

                    // SSL session ID
                    sslO = SSLSocket.getInfoS(socketRef, SSL.SSL_INFO_SESSION_ID);
                    if (sslO != null) {
                        request.setAttribute(SSLSupport.SESSION_ID_KEY, sslO);
                    }
                    //TODO provide a hook to enable the SSL session to be
                    // invalidated. Set AprEndpoint.SESSION_MGR req attr
                } catch (Exception e) {
                    log.warn(sm.getString("http11processor.socket.ssl"), e);
                }
            }

        } else if (actionCode == ActionCode.REQ_SSL_CERTIFICATE) {

            if (endpoint.isSSLEnabled() && (socketRef != 0)) {
                // Consume and buffer the request body, so that it does not
                // interfere with the client's handshake messages
                InputFilter[] inputFilters = inputBuffer.getFilters();
                ((BufferedInputFilter) inputFilters[Constants.BUFFERED_FILTER]).setLimit(maxSavePostSize);
                inputBuffer.addActiveFilter(inputFilters[Constants.BUFFERED_FILTER]);
                try {
                    // Configure connection to require a certificate
                    SSLSocket.setVerify(socketRef, SSL.SSL_CVERIFY_REQUIRE,
                            ((AprEndpoint)endpoint).getSSLVerifyDepth());
                    // Renegotiate certificates
                    if (SSLSocket.renegotiate(socketRef) == 0) {
                        // Don't look for certs unless we know renegotiation worked.
                        // Get client certificate and the certificate chain if present
                        // certLength == -1 indicates an error 
                        int certLength = SSLSocket.getInfoI(socketRef,SSL.SSL_INFO_CLIENT_CERT_CHAIN);
                        byte[] clientCert = SSLSocket.getInfoB(socketRef, SSL.SSL_INFO_CLIENT_CERT);
                        X509Certificate[] certs = null;
                        if (clientCert != null && certLength > -1) {
                            certs = new X509Certificate[certLength + 1];
                            CertificateFactory cf = CertificateFactory.getInstance("X.509");
                            certs[0] = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(clientCert));
                            for (int i = 0; i < certLength; i++) {
                                byte[] data = SSLSocket.getInfoB(socketRef, SSL.SSL_INFO_CLIENT_CERT_CHAIN + i);
                                certs[i+1] = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(data));
                            }
                        }
                        if (certs != null) {
                            request.setAttribute(SSLSupport.CERTIFICATE_KEY, certs);
                        }
                    }
                } catch (Exception e) {
                    log.warn(sm.getString("http11processor.socket.ssl"), e);
                }
            }

        } else if (actionCode == ActionCode.AVAILABLE) {
            request.setAvailable(inputBuffer.available());
        } else if (actionCode == ActionCode.COMET_BEGIN) {
            comet = true;
        } else if (actionCode == ActionCode.COMET_END) {
            comet = false;
        } else if (actionCode == ActionCode.COMET_CLOSE) {
            ((AprEndpoint)endpoint).processSocketAsync(this.socket,
                    SocketStatus.OPEN);
        } else if (actionCode == ActionCode.COMET_SETTIMEOUT) {
            //no op
        } else if (actionCode == ActionCode.ASYNC_COMPLETE) {
            if (asyncStateMachine.asyncComplete()) {
                ((AprEndpoint)endpoint).processSocketAsync(this.socket,
                        SocketStatus.OPEN);
            }
        } else if (actionCode == ActionCode.ASYNC_SETTIMEOUT) {
            if (param==null) return;
            long timeout = ((Long)param).longValue();
            socket.setTimeout(timeout);
        } else if (actionCode == ActionCode.ASYNC_DISPATCH) {
            if (asyncStateMachine.asyncDispatch()) {
                ((AprEndpoint)endpoint).processSocketAsync(this.socket,
                        SocketStatus.OPEN);
            }
        }
        

    }


    // ------------------------------------------------------ Protected Methods


    /**
     * After reading the request headers, we have to setup the request filters.
     */
    protected void prepareRequest() {

        http11 = true;
        http09 = false;
        contentDelimitation = false;
        expectation = false;
        sendfileData = null;
        if (endpoint.isSSLEnabled()) {
            request.scheme().setString("https");
        }
        MessageBytes protocolMB = request.protocol();
        if (protocolMB.equals(Constants.HTTP_11)) {
            http11 = true;
            protocolMB.setString(Constants.HTTP_11);
        } else if (protocolMB.equals(Constants.HTTP_10)) {
            http11 = false;
            keepAlive = false;
            protocolMB.setString(Constants.HTTP_10);
        } else if (protocolMB.equals("")) {
            // HTTP/0.9
            http09 = true;
            http11 = false;
            keepAlive = false;
        } else {
            // Unsupported protocol
            http11 = false;
            error = true;
            // Send 505; Unsupported HTTP version
            response.setStatus(505);
            adapter.log(request, response, 0);
        }

        MessageBytes methodMB = request.method();
        if (methodMB.equals(Constants.GET)) {
            methodMB.setString(Constants.GET);
        } else if (methodMB.equals(Constants.POST)) {
            methodMB.setString(Constants.POST);
        }

        MimeHeaders headers = request.getMimeHeaders();

        // Check connection header
        MessageBytes connectionValueMB = headers.getValue("connection");
        if (connectionValueMB != null) {
            ByteChunk connectionValueBC = connectionValueMB.getByteChunk();
            if (findBytes(connectionValueBC, Constants.CLOSE_BYTES) != -1) {
                keepAlive = false;
            } else if (findBytes(connectionValueBC,
                                 Constants.KEEPALIVE_BYTES) != -1) {
                keepAlive = true;
            }
        }

        MessageBytes expectMB = null;
        if (http11)
            expectMB = headers.getValue("expect");
        if ((expectMB != null)
            && (expectMB.indexOfIgnoreCase("100-continue", 0) != -1)) {
            inputBuffer.setSwallowInput(false);
            expectation = true;
        }

        // Check user-agent header
        if ((restrictedUserAgents != null) && ((http11) || (keepAlive))) {
            MessageBytes userAgentValueMB = headers.getValue("user-agent");
            // Check in the restricted list, and adjust the http11
            // and keepAlive flags accordingly
            if(userAgentValueMB != null) {
                String userAgentValue = userAgentValueMB.toString();
                if (restrictedUserAgents != null &&
                        restrictedUserAgents.matcher(userAgentValue).matches()) {
                    http11 = false;
                    keepAlive = false;
                }
            }
        }

        // Check for a full URI (including protocol://host:port/)
        ByteChunk uriBC = request.requestURI().getByteChunk();
        if (uriBC.startsWithIgnoreCase("http", 0)) {

            int pos = uriBC.indexOf("://", 0, 3, 4);
            int uriBCStart = uriBC.getStart();
            int slashPos = -1;
            if (pos != -1) {
                byte[] uriB = uriBC.getBytes();
                slashPos = uriBC.indexOf('/', pos + 3);
                if (slashPos == -1) {
                    slashPos = uriBC.getLength();
                    // Set URI as "/"
                    request.requestURI().setBytes
                        (uriB, uriBCStart + pos + 1, 1);
                } else {
                    request.requestURI().setBytes
                        (uriB, uriBCStart + slashPos,
                         uriBC.getLength() - slashPos);
                }
                MessageBytes hostMB = headers.setValue("host");
                hostMB.setBytes(uriB, uriBCStart + pos + 3,
                                slashPos - pos - 3);
            }

        }

        // Input filter setup
        InputFilter[] inputFilters = inputBuffer.getFilters();

        // Parse transfer-encoding header
        MessageBytes transferEncodingValueMB = null;
        if (http11)
            transferEncodingValueMB = headers.getValue("transfer-encoding");
        if (transferEncodingValueMB != null) {
            String transferEncodingValue = transferEncodingValueMB.toString();
            // Parse the comma separated list. "identity" codings are ignored
            int startPos = 0;
            int commaPos = transferEncodingValue.indexOf(',');
            String encodingName = null;
            while (commaPos != -1) {
                encodingName = transferEncodingValue.substring
                    (startPos, commaPos).toLowerCase(Locale.ENGLISH).trim();
                if (!addInputFilter(inputFilters, encodingName)) {
                    // Unsupported transfer encoding
                    error = true;
                    // 501 - Unimplemented
                    response.setStatus(501);
                    adapter.log(request, response, 0);
                }
                startPos = commaPos + 1;
                commaPos = transferEncodingValue.indexOf(',', startPos);
            }
            encodingName = transferEncodingValue.substring(startPos)
                .toLowerCase(Locale.ENGLISH).trim();
            if (!addInputFilter(inputFilters, encodingName)) {
                // Unsupported transfer encoding
                error = true;
                // 501 - Unimplemented
                response.setStatus(501);
                adapter.log(request, response, 0);
            }
        }

        // Parse content-length header
        long contentLength = request.getContentLengthLong();
        if (contentLength >= 0 && !contentDelimitation) {
            inputBuffer.addActiveFilter
                (inputFilters[Constants.IDENTITY_FILTER]);
            contentDelimitation = true;
        }

        MessageBytes valueMB = headers.getValue("host");

        // Check host header
        if (http11 && (valueMB == null)) {
            error = true;
            // 400 - Bad request
            response.setStatus(400);
            adapter.log(request, response, 0);
        }

        parseHost(valueMB);

        if (!contentDelimitation) {
            // If there's no content length 
            // (broken HTTP/1.0 or HTTP/1.1), assume
            // the client is not broken and didn't send a body
            inputBuffer.addActiveFilter
                    (inputFilters[Constants.VOID_FILTER]);
            contentDelimitation = true;
        }

        // Advertise sendfile support through a request attribute
        if (endpoint.getUseSendfile()) {
            request.setAttribute("org.apache.tomcat.sendfile.support", Boolean.TRUE);
        }
        // Advertise comet support through a request attribute
        request.setAttribute("org.apache.tomcat.comet.support", Boolean.TRUE);
        
    }


    @Override
    protected boolean prepareSendfile(OutputFilter[] outputFilters) {
        String fileName = (String) request.getAttribute(
                "org.apache.tomcat.sendfile.filename");
        if (fileName != null) {
            // No entity body sent here
            outputBuffer.addActiveFilter(outputFilters[Constants.VOID_FILTER]);
            contentDelimitation = true;
            sendfileData = new AprEndpoint.SendfileData();
            sendfileData.fileName = fileName;
            sendfileData.start = ((Long) request.getAttribute(
                    "org.apache.tomcat.sendfile.start")).longValue();
            sendfileData.end = ((Long) request.getAttribute(
                    "org.apache.tomcat.sendfile.end")).longValue();
            return true;
        }
        return false;
    }

    @Override
    protected AbstractInputBuffer getInputBuffer() {
        return inputBuffer;
    }

    @Override
    protected AbstractOutputBuffer getOutputBuffer() {
        return outputBuffer;
    }
}
