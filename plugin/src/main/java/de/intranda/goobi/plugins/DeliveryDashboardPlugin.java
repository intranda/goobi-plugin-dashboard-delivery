package de.intranda.goobi.plugins;

import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IDashboardPlugin;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
@Log4j2
public class DeliveryDashboardPlugin implements IDashboardPlugin {

    @Getter
    private String title = "intranda_dashboard_delivery";

    @Getter
    private PluginType type = PluginType.Dashboard;

    @Getter
    private String guiPath = "/uii/plugin_dashboard_delivery.xhtml";

    @Getter
    @Setter
    private String navigation = "main";

    @Getter
    @Setter
    private String documentType;


    @Getter
    private PluginGuiType pluginGuiType = PluginGuiType.FULL;

    /**
     * Constructor
     */
    public DeliveryDashboardPlugin() {
        log.info("Delivery dashboard plugin started");
        //        value = ConfigPlugins.getPluginConfig(title).getString("value", "default value");
    }

}
