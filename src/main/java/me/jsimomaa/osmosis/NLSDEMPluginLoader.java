package me.jsimomaa.osmosis;

import java.util.Collections;
import java.util.Map;

import org.openstreetmap.osmosis.core.pipeline.common.TaskManagerFactory;
import org.openstreetmap.osmosis.core.plugin.PluginLoader;

/**
 * @author jsimomaa
 *
 */
public class NLSDEMPluginLoader implements PluginLoader {

    @Override
    public Map<String, TaskManagerFactory> loadTaskFactories() {
        return Collections.singletonMap("nls-dem", new NLSDEMTaskManagerFactory());
    }
}
