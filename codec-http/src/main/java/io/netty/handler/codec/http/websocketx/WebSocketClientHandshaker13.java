/*
 * Copyright 2011 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http.websocketx;

import java.net.URI;
import java.util.Map;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaders.Names;
import io.netty.handler.codec.http.HttpHeaders.Values;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.logging.InternalLogger;
import io.netty.logging.InternalLoggerFactory;
import io.netty.util.CharsetUtil;

/**
 * <p>
 * Performs client side opening and closing handshakes for web socket specification version <a
 * href="http://tools.ietf.org/html/draft-ietf-hybi-thewebsocketprotocol-17" >draft-ietf-hybi-thewebsocketprotocol-
 * 17</a>
 * </p>
 */
public class WebSocketClientHandshaker13 extends WebSocketClientHandshaker {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(WebSocketClientHandshaker13.class);

    public static final String MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private String expectedChallengeResponseString;

    private static final String protocol = null;

    private boolean allowExtensions;

    /**
     * Constructor specifying the destination web socket location and version to initiate
     * 
     * @param webSocketURL
     *            URL for web socket communications. e.g "ws://myhost.com/mypath". Subsequent web socket frames will be
     *            sent to this URL.
     * @param version
     *            Version of web socket specification to use to connect to the server
     * @param subProtocol
     *            Sub protocol request sent to the server.
     * @param allowExtensions
     *            Allow extensions to be used in the reserved bits of the web socket frame
     * @param customHeaders
     *            Map of custom headers to add to the client request
     */
    public WebSocketClientHandshaker13(URI webSocketURL, WebSocketVersion version, String subProtocol,
            boolean allowExtensions, Map<String, String> customHeaders) {
        super(webSocketURL, version, subProtocol, customHeaders);
        this.allowExtensions = allowExtensions;
    }

    /**
     * /**
     * <p>
     * Sends the opening request to the server:
     * </p>
     * 
     * <pre>
     * GET /chat HTTP/1.1
     * Host: server.example.com
     * Upgrade: websocket
     * Connection: Upgrade
     * Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
     * Sec-WebSocket-Origin: http://example.com
     * Sec-WebSocket-Protocol: chat, superchat
     * Sec-WebSocket-Version: 13
     * </pre>
     * 
     * @param channel
     *            Channel into which we can write our request
     */
    @Override
    public void performOpeningHandshake(Channel channel) {
        // Get path
        URI wsURL = this.getWebSocketURL();
        String path = wsURL.getPath();
        if (wsURL.getQuery() != null && wsURL.getQuery().length() > 0) {
            path = wsURL.getPath() + "?" + wsURL.getQuery();
        }

        // Get 16 bit nonce and base 64 encode it
        byte[] nonce = createRandomBytes(16);
        String key = base64Encode(nonce);

        String acceptSeed = key + MAGIC_GUID;
        byte[] sha1 = sha1(acceptSeed.getBytes(CharsetUtil.US_ASCII));
        this.expectedChallengeResponseString = base64Encode(sha1);

        if (logger.isDebugEnabled()) {
            logger.debug(String.format("WS Version 13 Client Handshake key: %s. Expected response: %s.", key,
                    this.expectedChallengeResponseString));
        }

        // Format request
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path);
        request.addHeader(Names.UPGRADE, Values.WEBSOCKET.toLowerCase());
        request.addHeader(Names.CONNECTION, Values.UPGRADE);
        request.addHeader(Names.SEC_WEBSOCKET_KEY, key);
        request.addHeader(Names.HOST, wsURL.getHost());
        request.addHeader(Names.ORIGIN, "http://" + wsURL.getHost());
        if (protocol != null && !protocol.equals("")) {
            request.addHeader(Names.SEC_WEBSOCKET_PROTOCOL, protocol);
        }
        request.addHeader(Names.SEC_WEBSOCKET_VERSION, "13");

        if (customHeaders != null) {
            for (String header : customHeaders.keySet()) {
                request.addHeader(header, customHeaders.get(header));
            }
        }
        channel.write(request);

        channel.getPipeline().replace(HttpRequestEncoder.class, "ws-encoder", new WebSocket13FrameEncoder(true));
    }

    /**
     * <p>
     * Process server response:
     * </p>
     * 
     * <pre>
     * HTTP/1.1 101 Switching Protocols
     * Upgrade: websocket
     * Connection: Upgrade
     * Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
     * Sec-WebSocket-Protocol: chat
     * </pre>
     * 
     * @param channel
     *            Channel
     * @param response
     *            HTTP response returned from the server for the request sent by beginOpeningHandshake00().
     * @throws WebSocketHandshakeException
     */
    @Override
    public void performClosingHandshake(Channel channel, HttpResponse response) throws WebSocketHandshakeException {
        final HttpResponseStatus status = new HttpResponseStatus(101, "Switching Protocols");

        if (!response.getStatus().equals(status)) {
            throw new WebSocketHandshakeException("Invalid handshake response status: " + response.getStatus());
        }

        String upgrade = response.getHeader(Names.UPGRADE);
        if (upgrade == null || !upgrade.equals(Values.WEBSOCKET.toLowerCase())) {
            throw new WebSocketHandshakeException("Invalid handshake response upgrade: "
                    + response.getHeader(Names.UPGRADE));
        }

        String connection = response.getHeader(Names.CONNECTION);
        if (connection == null || !connection.equals(Values.UPGRADE)) {
            throw new WebSocketHandshakeException("Invalid handshake response connection: "
                    + response.getHeader(Names.CONNECTION));
        }

        String accept = response.getHeader(Names.SEC_WEBSOCKET_ACCEPT);
        if (accept == null || !accept.equals(this.expectedChallengeResponseString)) {
            throw new WebSocketHandshakeException(String.format("Invalid challenge. Actual: %s. Expected: %s", accept,
                    this.expectedChallengeResponseString));
        }

        channel.getPipeline().replace(HttpResponseDecoder.class, "ws-decoder",
                new WebSocket13FrameDecoder(false, this.allowExtensions));

        this.setOpenningHandshakeCompleted(true);
    }

}
