
package com.atakmap.android.zenoh.plugin;

import com.atak.plugins.impl.AbstractPlugin;

import gov.tak.api.plugin.IServiceController;

/**
 * IPlugin entry point. All actual behavior lives in {@link ZenohMapComponent},
 * whose onStart/onStop hooks open and close the Zenoh session.
 */
public class ZenohPlugin extends AbstractPlugin {

    public ZenohPlugin(IServiceController serviceController) {
        super(serviceController, new ZenohMapComponent());
    }
}
