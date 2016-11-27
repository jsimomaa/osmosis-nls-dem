package me.jsimomaa.osmosis.utils;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeader;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * @author jsimomaa
 *
 */
public class NLSXMLClient implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(NLSXMLClient.class.getName());

    private static final Header ACCEPT = new BasicHeader("Accept", "application/atom+xml");
    // private static final Header ACCEPT_ENCODING = new
    // BasicHeader("Accept-Encoding","gzip, deflate, sdch, br");
    // private static final Header CONNECTION = new
    // BasicHeader("Connection","keep-alive");
    // private static final Header HOST = new
    // BasicHeader("Host","tiedostopalvelu.maanmittauslaitos.fi");

    private CloseableHttpClient client;
    private ConcurrentHashMap<String, String> tifs = new ConcurrentHashMap<>();
    private String apiKey;

    private Path tiffStorage;

    public NLSXMLClient(String apiKey) {
        this(apiKey, System.getProperty("java.io.tmpdir"));
    }

    public NLSXMLClient(String apiKey, String tiffStorage) {
        this.apiKey = apiKey;
        this.tiffStorage = Paths.get(tiffStorage).resolve(getClass().getSimpleName());
        this.client = HttpClientBuilder.create().setConnectionManager(new PoolingHttpClientConnectionManager())
                .setUserAgent(
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/54.0.2840.100 Safari/537.36")
                .setDefaultHeaders(Arrays.asList(ACCEPT)).build();

        try {
            init();
        } catch (URISyntaxException | IOException | ParserConfigurationException | SAXException e) {
            e.printStackTrace();
        }
    }

    private void init() throws URISyntaxException, IOException, ParserConfigurationException, SAXException {
        URI uri = new URIBuilder().setScheme("https").setHost("tiedostopalvelu.maanmittauslaitos.fi")
                .setPath("/tp/feed/mtp/korkeusmalli/hila_2m").addParameter("api_key", apiKey)
                .addParameter("format", "image/tiff").build();

        while (uri != null) {
            try {
                uri = fetchTifTitlesAndLinks(client, uri, tifs);
            } catch (Exception e) {
                // Lets retry once
                uri = fetchTifTitlesAndLinks(client, uri, tifs);
            }
        }
    }

    public Path getTiff(String key) throws IOException {
        try {
            String path = tifs.get(key);
            if (path == null)
                return null;

            Path tiff = tiffStorage.resolve(path.startsWith("/") ? path.substring(1, path.length()) : path);
            if (!Files.exists(tiff)) {
                URI downloadURI = new URIBuilder().setScheme("https").setHost("tiedostopalvelu.maanmittauslaitos.fi")
                        .setPath("/tp/tilauslataus" + path).addParameter("api_key", apiKey).build();
                try {
                    downloadTiff(client, downloadURI, tiff);
                } catch (Exception e) {
                    // Retry once
                    downloadTiff(client, downloadURI, tiff);
                }
            }
            return tiff;
        } catch (URISyntaxException e) {
            e.printStackTrace();
            throw new IllegalArgumentException("No map sheet available for " + key, e);
        }
    }

    private static Document parseXML(InputStream inputStream)
            throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory objDocumentBuilderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder objDocumentBuilder = objDocumentBuilderFactory.newDocumentBuilder();
        return objDocumentBuilder.parse(inputStream);
    }

    private static void downloadTiff(CloseableHttpClient client, URI downloadURI, Path targetLocation)
            throws ClientProtocolException, IOException {

        HttpUriRequest get = new HttpGet(downloadURI);
        CloseableHttpResponse response = null;
        try {
            response = client.execute(get);
            StatusLine status = response.getStatusLine();
            if (status.getStatusCode() != 200) {
                LOGGER.warning(status.toString());
                HttpEntity entity = response.getEntity();
                InputStream stream = entity.getContent();
                StringWriter writer = new StringWriter();
                IOUtils.copy(stream, writer);

                String content = writer.toString();
                LOGGER.warning(content);
                return;
            }
            if (!Files.exists(targetLocation)) {
                Files.createDirectories(targetLocation.getParent());
                Files.createFile(targetLocation);
            }
            OutputStream output = Files.newOutputStream(targetLocation, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            IOUtils.copy(response.getEntity().getContent(), output);

            // Ensure that file exist and is fully usable
            if (!Files.isReadable(targetLocation))
                throw new IOException(targetLocation + " not readable!");
        } catch (Throwable t) {
            if (Files.exists(targetLocation)) {
                try {
                    Files.delete(targetLocation);
                } catch (IOException e) {
                    // Ignore
                }
            }
            throw t;
        } finally {
            try {
                if (response != null)
                    response.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    private static URI fetchTifTitlesAndLinks(CloseableHttpClient client, URI uri, Map<String, String> titlesAndLinks)
            throws IOException, ParserConfigurationException, SAXException, URISyntaxException {
        LOGGER.info("Fetching URI " + uri);
        HttpUriRequest request = new HttpGet(uri);
        CloseableHttpResponse response = client.execute(request);

        StatusLine line = response.getStatusLine();
        if (line.getStatusCode() != 200) {
            LOGGER.warning(line.toString());

            HttpEntity entity = response.getEntity();

            InputStream stream = entity.getContent();

            StringWriter writer = new StringWriter();
            IOUtils.copy(stream, writer);

            String content = writer.toString();
            LOGGER.warning(content);
            return null;
        }

        HttpEntity entity = response.getEntity();
        InputStream stream = entity.getContent();

        StringWriter writer = new StringWriter();
        IOUtils.copy(stream, writer);
        String content = writer.toString();
        
        response.close();

        Document doc = parseXML(new ByteArrayInputStream(content.getBytes()));
        NodeList entries = doc.getElementsByTagName("entry");
        NodeList links = doc.getElementsByTagName("link");

        String nextUrl = null;
        for (int i = 0; i < links.getLength(); i++) {
            Node link = links.item(i);
            NamedNodeMap attrs = link.getAttributes();
            if (attrs != null) {
                Node linkRel = attrs.getNamedItem("rel");
                if (linkRel != null) {
                    if ("next".equals(linkRel.getTextContent())) {
                        Node linkHref = attrs.getNamedItem("href");
                        nextUrl = linkHref.getTextContent();
                        break;
                    }
                }
            }
        }

        List<NLSXMLAPIEntry> apiEntries = new ArrayList<>();

        for (int i = 0; i < entries.getLength(); i++) {
            Node entry = entries.item(i);
            NodeList entryChildren = entry.getChildNodes();

            NLSXMLAPIEntry apiEntry = new NLSXMLAPIEntry();

            for (int j = 0; j < entryChildren.getLength(); j++) {
                Node entryChild = entryChildren.item(j);
                switch (entryChild.getNodeName()) {
                case "title":
                    String title = entryChild.getTextContent();
                    title = title.substring(0, title.length() - ".tif".length());
                    apiEntry.setTitle(title);
                    break;
                case "id":
                    String urnPath = entryChild.getTextContent();
                    String path = urnPath.substring("urn:path:".length(), urnPath.length());
                    apiEntry.setLink(path);
                    break;
                default:
                    break;
                }
            }
            apiEntries.add(apiEntry);
        }
        apiEntries.forEach(entry -> {
            titlesAndLinks.put(entry.title, entry.link);
        });
        if (nextUrl != null)
            return new URIBuilder(nextUrl).build();
        else
            return null;
    }

    private static class NLSXMLAPIEntry {

        private String title;
        private String link;

        public void setTitle(String title) {
            this.title = title;
        }

        public void setLink(String link) {
            this.link = link;
        }

        @Override
        public String toString() {
            return new StringBuilder().append(title).append(" [").append(link).append("]").toString();
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
    }
}
