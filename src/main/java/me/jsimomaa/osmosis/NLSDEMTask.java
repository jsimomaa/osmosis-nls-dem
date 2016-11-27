package me.jsimomaa.osmosis;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.data.PrjFileReader;
import org.geotools.factory.Hints;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.geometry.DirectPosition2D;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.opengis.geometry.DirectPosition;
import org.opengis.geometry.MismatchedDimensionException;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;
import org.openstreetmap.osmosis.core.container.v0_6.BoundContainer;
import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer;
import org.openstreetmap.osmosis.core.container.v0_6.EntityProcessor;
import org.openstreetmap.osmosis.core.container.v0_6.NodeContainer;
import org.openstreetmap.osmosis.core.container.v0_6.RelationContainer;
import org.openstreetmap.osmosis.core.container.v0_6.WayContainer;
import org.openstreetmap.osmosis.core.domain.v0_6.CommonEntityData;
import org.openstreetmap.osmosis.core.domain.v0_6.Node;
import org.openstreetmap.osmosis.core.domain.v0_6.Tag;
import org.openstreetmap.osmosis.core.task.v0_6.Sink;
import org.openstreetmap.osmosis.core.task.v0_6.SinkSource;

import me.jsimomaa.osmosis.utils.NLSXMLClient;
import me.jsimomaa.osmosis.utils.TM35Utils;
import me.jsimomaa.osmosis.utils.TM35Utils.TM35Scale;

/**
 * @author jsimomaa
 *
 */
public class NLSDEMTask implements SinkSource, EntityProcessor {

    private static final Logger LOGGER = Logger.getLogger(NLSDEMTask.class.getName());

    private Sink sink;
    private final CoordinateReferenceSystem sourceCRS;
    private final CoordinateReferenceSystem targetCRS;
    private final MathTransform transform;
    private final ExecutorService tiffDownloaderService;
    private final ConcurrentHashMap<String, Set<TranslatedNode>> processing = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, GridCoverage2D> ready = new ConcurrentHashMap<>();
    private final Set<String> notFound = ConcurrentHashMap.newKeySet();
    private final NLSXMLClient nlsXmlClient;

    private final Set<String> executorExecuting = ConcurrentHashMap.newKeySet();
    private boolean override = true;
    private String[] tags = new String[0];

    public NLSDEMTask(String apiKey) throws URISyntaxException {
        this(apiKey, System.getProperty("java.io.tmpdir"));
    }

    public NLSDEMTask(String apiKey, String tiffStorage) throws URISyntaxException {
        this(apiKey, NLSDEMTask.class.getResource("EPSG3067.prj").toURI(), tiffStorage);

    }

    public NLSDEMTask(String apiKey, URI prjFileLocation, String tiffStorage) throws URISyntaxException {
        if (prjFileLocation == null)
            // Fallback to EPSG3067 found within JAR
            prjFileLocation = NLSDEMTask.class.getResource("EPSG3067.prj").toURI();

        if (tiffStorage == null)
            tiffStorage = System.getProperty("java.io.tmpdir");

        Path prjFile = Paths.get(prjFileLocation);
        if (!Files.isReadable(prjFile))
            throw new IllegalArgumentException(
                    ".prj file " + prjFileLocation + " cannot be read. See that it exists and is readable!");

        this.sourceCRS = DefaultGeographicCRS.WGS84;
        try {
            PrjFileReader reader = new PrjFileReader(FileChannel.open(prjFile, StandardOpenOption.READ));
            targetCRS = reader.getCoordinateReferenceSystem();
            transform = CRS.findMathTransform(sourceCRS, targetCRS);
        } catch (FactoryException | IOException e) {
            throw new IllegalArgumentException(".prj file provided is invalid!", e);
        }
        this.nlsXmlClient = new NLSXMLClient(apiKey, tiffStorage);
        this.tiffDownloaderService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(),
                new NLSTiffDownloaderFactory());
    }

    @Override
    public void process(EntityContainer entityContainer) {
        entityContainer.process(this);
    }

    @Override
    public void initialize(Map<String, Object> metaData) {
    }

    @Override
    public void complete() {
        shutdown();
        sink.complete();
    }

    @Override
    public void release() {
        shutdown();
        sink.release();
    }

    public void shutdown() {
        if (!tiffDownloaderService.isShutdown()) {
            tiffDownloaderService.shutdown();
            try {
                while (!tiffDownloaderService.awaitTermination(10, TimeUnit.SECONDS)) {
                    LOGGER.info("Waiting for NLSDEMTask to finish..");
                    processReadyItems();
                    processNotFoundItems();
                }
            } catch (InterruptedException e) {
                LOGGER.log(Level.SEVERE, "NLSDEMTask did not complete succesfully!", e);
            }
        }
    }

    @Override
    public void setSink(Sink sink) {
        this.sink = sink;
    }

    @Override
    public void process(BoundContainer bound) {
        sink.process(bound);
    }

    @Override
    public void process(NodeContainer nodec) {
        Node node = nodec.getEntity();
        double lat = node.getLatitude();
        double lon = node.getLongitude();

        // Transform to EPSG:3067
        DirectPosition2D ptDst = new DirectPosition2D(targetCRS);
        try {
            transform.transform(new DirectPosition2D(sourceCRS, lon, lat), ptDst);

            String tm35MapSheet = TM35Utils.reverseGeocode(ptDst.x, ptDst.y, TM35Scale.SCALE_10000);

            GridCoverage2D gc = ready.get(tm35MapSheet);

            if (gc != null) {
                // Do the computation
                processReady(gc, ptDst, node);
            } else {
                // Download tiff async

                processing.compute(tm35MapSheet, (sheetKey, currentSet) -> {
                    if (currentSet == null)
                        currentSet = new HashSet<>();

                    currentSet.add(new TranslatedNode(node, ptDst));
                    return currentSet;
                });
                submitTiffQuerying(tm35MapSheet);
            }
            processReadyItems();

        } catch (MismatchedDimensionException | TransformException e) {
            LOGGER.log(Level.WARNING,
                    "Could not transform lat=" + lat + ", lon=" + lon + " from " + sourceCRS + " to " + targetCRS, e);
        }
    }

    @Override
    public void process(WayContainer way) {
        sink.process(way);
    }

    @Override
    public void process(RelationContainer relation) {
        sink.process(relation);
    }

    public void setOverrideExisting(boolean override) {
        this.override = override;
    }

    public void setHeightTags(String[] tags) {
        this.tags = tags;
    }

    private void processReadyItems() {
        // Process pending nodes that are ready
        processing.forEach((key, value) -> {
            GridCoverage2D gc3 = ready.get(key);
            // Check if tiff download and processing is ready - if not, keep in
            // the queue for next iteration
            if (gc3 != null) {
                Set<TranslatedNode> removed = new HashSet<>();
                value.forEach(trNode -> {
                    try {
                        processReady(gc3, trNode.ptDst, trNode.node);
                        removed.add(trNode);
                    } catch (Exception e) {
                        // Underlying tiff of the gc3 is corrupt!
                        // lets remove it from the ready-map
                        LOGGER.log(Level.WARNING, "TIFF-file for " + key + " is corrupted! Trying to download it again", e);
                        ready.remove(key);
                        submitTiffQuerying(key);
                    }
                });
                value.removeAll(removed);
                // Remove the nodes from processing cache as they are now
                // processed
                if (value.isEmpty())
                    processing.remove(key);
            }
        });
    }

    private void processNotFoundItems() {
        notFound.forEach(item -> {
            Set<TranslatedNode> p = processing.remove(item);
            if (p != null) {
                p.forEach(tr -> {
                    sink.process(new NodeContainer(tr.node));
                });
            }
        });
        notFound.clear();
    }

    private void submitTiffQuerying(String tm35MapSheet) {
        tiffDownloaderService.submit(() -> {

            if (!executorExecuting.add(tm35MapSheet))
                return;

            if (ready.containsKey(tm35MapSheet)) {
                LOGGER.info(tm35MapSheet + " is already inside ready-map for processing!");
                return;
            }

            boolean tiffProcesed = false;
            int retries = 0;
            Throwable last = null;
            while (!tiffProcesed && retries != 5) {
                // Download tiff from nls.fi API endpoint
                try {
                    GridCoverage2D gc4 = getTiff(tm35MapSheet);
                    if (gc4 == null) {
                        // tiff file is not available - let nodes be processed
                        // without z-tag
                        LOGGER.info("Map sheet " + tm35MapSheet + " is not available to download - skipping nodes on that sheet!");
                        notFound.add(tm35MapSheet);
                    } else {
                        ready.put(tm35MapSheet, gc4);
                    }
                    tiffProcesed = true;
                    break;
                } catch (Throwable t) {
                    // The file is corrupt! Need to re-download it
                    retries++;
                    LOGGER.warning(tm35MapSheet + " is corrupt - re-download! (" + retries + "/5)");
                    last = t;
                }
            }
            if (!tiffProcesed)
                LOGGER.log(Level.SEVERE, "Could not download " + tm35MapSheet + " - last exception was:", last);

            executorExecuting.remove(tm35MapSheet);
        });
    }

    private GridCoverage2D getTiff(String tm35MapSheet) throws IOException {
        Path tiff = nlsXmlClient.getTiff(tm35MapSheet);
        if (tiff == null)
            return null;
        File tiffFile = tiff.toFile();

        // Reading the coverage through a file
        GeoTiffReader reader = null;
        try {
            reader = new GeoTiffReader(tiffFile, new Hints(Hints.DEFAULT_COORDINATE_REFERENCE_SYSTEM, targetCRS));
            return reader.read(null);
        } catch (Throwable t) {
            // File is possibly corrupt - lets delete it and try again
            if (Files.exists(tiff))
                Files.delete(tiff);
            throw t;
        } finally {
            if (reader != null)
                reader.dispose();
        }
    }

    private void processReady(GridCoverage2D gc, DirectPosition ptDst, Node node) throws RuntimeException {
        float[] value = gc.evaluate(ptDst, (float[]) null);

        String height = Float.toString((value)[0]);

        // look for existing height tag
        Collection<Tag> tags = node.getTags();
        Tag existingHeight = null;
        for (Tag tag : tags) {
            for (String tagg : this.tags) {
                if (tag.getKey().equalsIgnoreCase(tagg)) {
                    existingHeight = tag;
                    break;
                }
            }

        }

        // work with possible existing height tag
        // check if it should be replaced or not
        boolean addHeight = true;
        if (existingHeight != null) {
            if (override) {
                tags.remove(existingHeight);
            } else {
                addHeight = false;
            }
        }

        // add new height tag
        if (addHeight)
            tags.add(new Tag("z", height));

        // create new node entity with new z-tag
        CommonEntityData ced = new CommonEntityData(node.getId(), node.getVersion(), node.getTimestamp(),
                node.getUser(), node.getChangesetId(), tags);

        // distribute the new NodeContainer to the following sink
        sink.process(new NodeContainer(new Node(ced, node.getLatitude(), node.getLongitude())));
    }

    private final class NLSTiffDownloaderFactory implements ThreadFactory {
        private int counter = 0;

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "osmosis-nls-tiff-downloader-" + counter++);
        }
    }

    private static class TranslatedNode {

        private final Node node;
        private final DirectPosition2D ptDst;

        TranslatedNode(Node node, DirectPosition2D ptDst) {
            this.node = node;
            this.ptDst = ptDst;
        }
    }

}
