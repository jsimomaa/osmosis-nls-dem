package me.jsimomaa.osmosis;

import java.net.URI;
import java.net.URISyntaxException;

import org.openstreetmap.osmosis.core.pipeline.common.TaskConfiguration;
import org.openstreetmap.osmosis.core.pipeline.common.TaskManager;
import org.openstreetmap.osmosis.core.pipeline.common.TaskManagerFactory;
import org.openstreetmap.osmosis.core.pipeline.v0_6.SinkSourceManager;

/**
 * @author jsimomaa
 *
 */
public class NLSDEMTaskManagerFactory extends TaskManagerFactory {

    @Override
    protected TaskManager createTaskManagerImpl(TaskConfiguration taskConfig) {
        String apiKey = taskConfig.getDefaultArg();
        if (apiKey == null)
            throw new IllegalArgumentException("NLS API key is required! (apiKey)");

        String prjFile = getStringArgument(taskConfig, "prjFile");
        String tiffStorage = getStringArgument(taskConfig, "tiffStorage", System.getProperty("java.io.tmpdir"));
        String heightTags = getStringArgument(taskConfig, "heightTags", "");
        String[] tags = heightTags.split(",");
        boolean override = getBooleanArgument(taskConfig, "override", true);
        try {
            
            NLSDEMTask task = new NLSDEMTask(apiKey, prjFile != null ? new URI(prjFile) : null, tiffStorage);
            task.setOverrideExisting(override);
            task.setHeightTags(tags);
            return new SinkSourceManager(taskConfig.getId(), task, taskConfig.getPipeArgs());
        } catch (URISyntaxException e) {
            // This should never happen! still lets rethrow it
            throw new IllegalArgumentException(e);
        }
    }
}
