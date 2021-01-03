package net.cardosi.mojo.tools;

import java.awt.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Phaser;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;

/**
 * <p>This class serves resources to the client and manages live-reloading.</p>
 * <p>If a user requests any resource via GET, it is resolved against {@link #root()}
 *  and served as is. The one exception is index.html, which is handled as follows:</p>
 *
 * <ol>
 *   <li>User requests index.html.</li>
 *   <li>We inject some JS into the page, and serve.</li>
 *   <li>The injected JS initiates a websocket connection with the server.</li>
 *   <li>We store this socket connection in a queue.</li>
 *   <li>When some source-file changes eventually trigger reload(), we poll() every
 *   connection and send a reload message.</li>
 *   <li>The injected JS receives this message and reloads the page. Loop to 1.</li>
 * </ol>
 */
public class DevServer implements Runnable {

    // we inject some javascript after this tag in index.html
    private static final String BODY_TAG = "<body>";
    private static final int BODY_TAG_LEN = BODY_TAG.length();

    // webserver root
    private final Path root;
    // path to index.html, whether it currently exists or not
    private final Path indexHtmlPath;
    // webserver port
    private final int port;
    // JS injected into index.html, triggers web socket initialization and reload
    private final ByteBuffer encodedJsBuf;
    // buffer used for serving requests and responses
    private final ByteBuffer buffer = ByteBuffer.allocate(250_000);
    // see class javadoc
    private final ConcurrentLinkedQueue<SocketChannel> webSockets = new ConcurrentLinkedQueue<>();
    /**
     * <p>This Phaser determines when to reload() the tabs. Using Phaser solves the problem
     * of reloading before all build steps have finished. Consider:</p>
     * <ol>
     *   <li>User saves their IDE, saving {@code module1/src/main/java/Main.java} and
     *   {@code module2/src/main/java/Util.java} at the same time.</li>
     *   <li>Thread A is running the WatchService for module1. The thread
     *   calls {@link #notifyBuilding()} and begins the (long) build.</li>
     *   <li>Thread B is running the WatchService for module2. The thread
     *   calls {@link #notifyBuilding()} and begins the (short) build.</li>
     *   <li>Thread B finishes and calls {@link #notifyBuildStepComplete()}.</li>
     *   <li>Thread A finishes and calls {@link #notifyBuildStepComplete()}</li>
     *   <li>All builds are now finished, triggering Phaser#onAdvance, which
     *   calls reload().</li>
     * </ol>
     *
     * <p>Note: This is a classic CyclicBarrier problem. Phaser is perfect because, unlike
     * CyclicBarrier, the number of registered parties can be dynamic.</p>
     */
    private final Phaser phaser = new Phaser() {
        @Override
        protected boolean onAdvance(int phase, int registeredParties) {
            reload();
            return false;
        }
    };
    // the initial websocket message.
    private final ByteBuffer initMsgBuffer = ByteBuffer.wrap(encodeWSMsg("init"));
    // websocket message triggering reload.
    private final ByteBuffer reloadMsgBuffer = ByteBuffer.wrap(encodeWSMsg("reloadplz"));
    // we assume utf-8 encoding, like elsewhere in this plugin
    private final CharsetEncoder utf8Encoder = UTF_8.newEncoder();
    private final CharsetDecoder utf8Decoder = UTF_8.newDecoder();

    /**
     * @param root Path to host files from
     * @param port Port to bind on localhost
     */
    public DevServer(Path root, int port) {
        this.root = root;
        indexHtmlPath = root.resolve("index.html");
        this.port = port;

        String js = "<script>" +
                    "(function() {" +
                    "var websocket=new WebSocket('ws://localhost:" + port + "/_serveWebsocket');" +
                    "websocket.onmessage=function(e){" +
                    "if (e.data!=='init')location.reload();" +
                    "};" +
                    "})();" +
                    "</script>";
        encodedJsBuf = UTF_8.encode(js);
    }

    /**
     * Content root. Configured during construction.
     */
    public Path root() {
        return root;
    }

    /**
     * Notifies this server that a build-action has
     * started, and it is unsafe to perform a
     * reload.
     */
    public void notifyBuilding() {
        phaser.register();
    }

    /**
     * Notifies this server that a build-action has
     * completed. Once all other pending build-actions
     * have also called notifyBuildComplete(), a
     * reload will be triggered.
     */
    public void notifyBuildStepComplete() {
        phaser.arriveAndDeregister();
    }

    private void reload() {
        System.out.println("Files changed, reloading...");

        try {
            SocketChannel s;
            while ((s = webSockets.poll()) != null) {
                while (reloadMsgBuffer.hasRemaining()) s.write(reloadMsgBuffer);
                s.close();
                reloadMsgBuffer.rewind();
            }
        } catch (IOException e) {
            e.printStackTrace();
            reloadMsgBuffer.rewind();
        }
    }

    /**
     * Starts the server
     */
    @Override
    public void run() {
        try (ServerSocketChannel ssc = ServerSocketChannel.open()) {
            ssc.bind(new InetSocketAddress(port));

            // open the browser to localhost, if supported.
            Desktop desktop;
            if (Files.exists(indexHtmlPath) &&
                Desktop.isDesktopSupported() &&
                (desktop = Desktop.getDesktop()).isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(URI.create("http://localhost:" + port));
            }

            while (true) {
                SocketChannel sc = ssc.accept();

                // the resource to serve
                Path res;
                // whether to insert our websocket JS
                boolean servingIndexHtml = true;

                /*
                Read request bytes into the buffer. Since this is a simple dev server,
                we only need to support small GET requests.
                 */
                buffer.clear();
                sc.read(buffer);
                buffer.flip();


                /*
                the resource requested, ie `/home` or `/css/styles.css`. Note that
                getInBetween advances buffer, which is fine.. there is a required
                ordering to the headers we care about, with request type coming first.
                 */
                String req = getInBetween("GET ", " ");
                if (req == null) {
                    sc.close();
                    System.err.println("Malformed Request.. could not find GET header");
                    continue;
                }

                /*
                Now we switch over a bunch of request cases. The client could ask
                for a resource, index.html, websocket connection, source map, etc.

                First up, if the request is for '/', set res = index.html.
                 */
                if (req.equals("/")) {
                    res = indexHtmlPath;

                } else if (req.equals("/_serveWebsocket")) {
                    sc.socket().setKeepAlive(true);

                    /*
                    we search the header for WebSocket Key,
                    perform the protocol switch, and
                    send an init message.
                     */
                    String wsKey = getInBetween("Sec-WebSocket-Key: ", "\r\n");
                    if (wsKey == null) {
                        sc.close();
                        System.err.println("Could not find websocket key");
                        continue;
                    }

                    // Building the WS Upgrade Header
                    byte[] digest = (wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(UTF_8);
                    String resp = "HTTP/1.1 101 Switching Protocols\r\n" +
                                  "Connection: Upgrade\r\n" +
                                  "Upgrade: websocket\r\n" +
                                  "Sec-WebSocket-Accept: " +
                                  Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(digest)) +
                                  "\r\n\r\n";

                    // send headers + WS init message,
                    encodeBuf(resp);
                    while (buffer.hasRemaining()) sc.write(buffer);
                    while (initMsgBuffer.hasRemaining()) sc.write(initMsgBuffer);
                    // no need to clear buffer, since we continue;
                    initMsgBuffer.rewind();

                    /*
                    add SocketChannel to our concurrent queue, so we can
                    send reload message if necessary.
                     */
                    webSockets.offer(sc);
                    continue;

                // it must otherwise be some type of resource.. lets find out
                } else {
                    /*
                    could be index.html in another directory. For example, if
                    request is `/mypage/pageX`, we should serve `mypage/pageX/index.html`.
                    However, we need to test if the file actually exists.
                     */
                    res = root.resolve(req.substring(1)).resolve("index.html");

                    // If it doesn't exist, we must be requesting some resource like `/styles.css`
                    if (!Files.exists(res)) {
                        servingIndexHtml = false;
                        res = root.resolve(req.substring(1));

                        if (!Files.exists(res)) {
                            /*
                            Could be a source map resource. Currently j2cl is generating source maps
                            that work fine if you're at your root directory, like '/'. But sub-directories
                            don't work very well. If you're on page '/myapp/', the browser will
                            try to load the maps from '/myapp/sources/.../*.map', which does not exist.
                            So, we must strip the prefix before '/sources', and the file can resolve.

                            todo: look at adjusting Closure's --source_map_location_mapping to add a
                            secondary location mapping
                            https://github.com/google/closure-compiler/blob/bf351b9f099e55e2c6405d73b22aaee8924c6f87/src/com/google/javascript/jscomp/CommandLineRunner.java#L338-L341
                            */
                            int sourceIndex = req.indexOf("/sources/");
                            if (sourceIndex != -1) {
                                res = root.resolve(req.substring(sourceIndex + 1));
                            }
                        }
                    }
                }

                if (!Files.exists(res)) {
                    // might be SPA, so default to index.html if we can and not some 404 page.
                    if (Files.exists(indexHtmlPath)) {
                        res = indexHtmlPath;
                        servingIndexHtml = true;
                    } else {
                        System.err.println("No such file: " + res);
                        encodeBuf("HTTP/1.0 404 Not Found\r\n");
                        while (buffer.hasRemaining()) sc.write(buffer);
                        sc.close();
                        continue;
                    }
                }

                // build the response header
                String date = Instant.now().atOffset(ZoneOffset.UTC).format(RFC_1123_DATE_TIME);
                String respHeader = "HTTP/1.0 200 OK\r\n" +
                                    "Content-Type: " + Files.probeContentType(res) + "\r\n" +
                                    "Date: " + date + "\r\n";

                /*
                Since we're using BUNDLE_JAR for fast incremental recompilation,
                we need to cache the large unoptimized bundles. Good news is that
                these *.bundle.js files are 'revved' [1] with a hash, so we can simply
                cache them forever.

                We also cache the large j2cl-base.js... we should look at hashing this
                resource in the future, although perhaps it will be invalidated
                when bumping the j2cl-maven-plugin version.
                [1]: https://developer.mozilla.org/en-US/docs/Web/HTTP/Caching#Revved_resources
                 */
                String fileName = res.getFileName().toString();
                if ("j2cl-base.js".equals(fileName) || fileName.endsWith("bundle.js")) {
                    respHeader += "Cache-Control: max-age=31536000\r\n";
                }


                long size = Files.size(res);

                // if serving an index.html, insert the websocket JS
                if (servingIndexHtml) {
                    // lets try to find the body tag
                    try (FileChannel fc = FileChannel.open(res)) {
                        // the index after the <body> tag
                        long afterBodyTag = 0;
                        /*
                        The index.html might be bigger than the buffer, so we
                        load in iterations, taking care of the split case
                        ie, buffer1 = ...<bo
                        and buffer2 = dy>...

                        If there's not at least "<body>".length() chars, then we may exit
                        since we're looking for the position after this search string.
                         */
                        while (size - fc.position() > BODY_TAG_LEN) {
                            long lastPos = fc.position();

                            // read some of the file into the buffer
                            buffer.clear();
                            while (buffer.hasRemaining() && fc.position() < size) fc.read(buffer);
                            buffer.flip();

                            int bodyPosInBuffer = findInBuffer(BODY_TAG);
                            if (bodyPosInBuffer > 0) {
                                afterBodyTag = lastPos + bodyPosInBuffer;
                                break;
                            }

                            // be generous in case of split <body> tag case.
                            fc.position(fc.position() - BODY_TAG_LEN);
                        }

                        // reset file position and begin to send to client
                        fc.position(0);

                        if (afterBodyTag == 0) {
                            // we could not find body tag.. just transfer the file as is
                            System.err.println("Could not find <body> tag in " + res);
                            respHeader += "Content-Length: " + size + "\r\n\r\n";
                            // write header to socketchannel
                            encodeBuf(respHeader);
                            while (buffer.hasRemaining()) sc.write(buffer);
                            transferFile(fc, sc);

                        } else {
                            long contentLength = size + encodedJsBuf.limit();
                            respHeader += "Content-Length: " + contentLength + "\r\n\r\n";
                            // write header to socketchannel
                            encodeBuf(respHeader);
                            while (buffer.hasRemaining()) sc.write(buffer);

                            // send index.html up to afterBodyTag
                            long transferred = 0;
                            while (transferred < afterBodyTag)
                                transferred += fc.transferTo(transferred, afterBodyTag - transferred, sc);

                            // send the JS addition
                            while (encodedJsBuf.hasRemaining()) sc.write(encodedJsBuf);
                            encodedJsBuf.rewind();

                            // send remaining part of file
                            transferred = afterBodyTag;
                            while (transferred < size)
                                transferred += fc.transferTo(transferred, size - transferred, sc);
                        }
                    }

                // if not servingIndexHtml, serve the resource
                } else {
                    // add Content-Size to resp header, and send it
                    respHeader += "Content-Length: " + size + "\r\n\r\n";
                    encodeBuf(respHeader);
                    while (buffer.hasRemaining()) sc.write(buffer);

                    try (FileChannel fc = FileChannel.open(res)) {
                        transferFile(fc, sc);
                    }
                }

                sc.close();
            }

        } catch (IOException | NoSuchAlgorithmException e) {
            e.printStackTrace();
            // todo; just following project convention here..
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Extracts a header value given the key (ex, "GET ") from
     * start until end.
     * Returns the String value on success,
     * or null on failure. buffer is advanced.
     */
    private String getInBetween(String start, String end) throws CharacterCodingException {
        int startI = findInBuffer(start);
        if (startI < 0) return null;
        int endI = findInBuffer(end) - end.length();
        if (endI < 0) return null;

        // Wish I had Java 13 ByteBuffer.slice
        int lim = buffer.limit();
        buffer.position(startI);
        buffer.limit(endI);
        String res = decodeBuf(buffer);
        buffer.limit(lim);
        buffer.position(endI);
        return res;
    }

    /**
     * searches for a given String in the UTF-8 buffer.
     * Returns the advanced buffer position on success,
     * or -1 on failure.
     * <p>
     * todo: consider adding skips
     */
    private int findInBuffer(String s) {
        byte[] search = s.getBytes(UTF_8);
        int searchLimit = buffer.limit() - search.length;
        search:
        while (buffer.position() < searchLimit) {
            for (byte b : search) if (buffer.get() != b) continue search;
            return buffer.position();
        }
        return -1;
    }

    /**
     * Encode the UTF-16 String to UTF-8 Buffer.
     * Flips buffer when done, so position = 0.
     */
    private void encodeBuf(String s) {
        buffer.clear();
        utf8Encoder.reset();
        utf8Encoder.encode(CharBuffer.wrap(s), buffer, true);
        utf8Encoder.flush(buffer);
        buffer.flip();
    }

    private String decodeBuf(ByteBuffer bb) throws CharacterCodingException {
        String res = utf8Decoder.decode(bb).toString();
        bb.flip();
        return res;
    }

    /**
     * transfers all data from FileChannel to SocketChannel, throwing
     * exception on failure
     */
    private void transferFile(FileChannel fc, SocketChannel sc) throws IOException {
        long transferred = 0;
        long size = fc.size();
        while (transferred < size)
            transferred += fc.transferTo(transferred, size - transferred, sc);
    }

    /**
     * Lifted from now lost SO answer. Encodes a websocket message.
     */
    private static byte[] encodeWSMsg(String mess) {
        byte[] rawData = mess.getBytes();

        int frameCount = 0;
        byte[] frame = new byte[10];

        frame[0] = (byte) 129;

        if (rawData.length <= 125) {
            frame[1] = (byte) rawData.length;
            frameCount = 2;
        } else if (rawData.length >= 126 && rawData.length <= 65535) {
            frame[1] = (byte) 126;
            int len = rawData.length;
            frame[2] = (byte) ((len >> 8) & (byte) 255);
            frame[3] = (byte) (len & (byte) 255);
            frameCount = 4;
        } else {
            frame[1] = (byte) 127;
            int len = rawData.length;
            frame[2] = (byte) ((len >> 56) & (byte) 255);
            frame[3] = (byte) ((len >> 48) & (byte) 255);
            frame[4] = (byte) ((len >> 40) & (byte) 255);
            frame[5] = (byte) ((len >> 32) & (byte) 255);
            frame[6] = (byte) ((len >> 24) & (byte) 255);
            frame[7] = (byte) ((len >> 16) & (byte) 255);
            frame[8] = (byte) ((len >> 8) & (byte) 255);
            frame[9] = (byte) (len & (byte) 255);
            frameCount = 10;
        }

        int bLength = frameCount + rawData.length;

        byte[] reply = new byte[bLength];

        int bLim = 0;
        for (int i = 0; i < frameCount; i++) {
            reply[bLim] = frame[i];
            bLim++;
        }
        for (int i = 0; i < rawData.length; i++) {
            reply[bLim] = rawData[i];
            bLim++;
        }

        return reply;
    }
}
