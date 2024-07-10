package de.intranda.goobi.plugins;

import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IDashboardPlugin;

import lombok.Getter;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation

public class DeliveryDashboardPlugin implements IDashboardPlugin {

    private static final long serialVersionUID = -6261703469505380104L;

    @Getter
    private String title = "intranda_dashboard_delivery";

    @Getter
    private PluginType type = PluginType.Dashboard;

    @Getter
    private String guiPath = "/uii/plugin_dashboard_delivery.xhtml"; //NOSONAR

    @Getter
    private PluginGuiType pluginGuiType = PluginGuiType.FULL;

}
