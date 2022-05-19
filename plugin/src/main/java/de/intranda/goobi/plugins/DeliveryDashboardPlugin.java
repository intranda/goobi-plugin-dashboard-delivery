package de.intranda.goobi.plugins;

import java.util.ArrayList;
import java.util.List;

import javax.faces.model.SelectItem;
import javax.servlet.http.Part;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IDashboardPlugin;

import de.intranda.goobi.plugins.utils.FieldGrouping;
import de.intranda.goobi.plugins.utils.MetadataField;
import de.sub.goobi.config.ConfigPlugins;
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

    @Getter
    @Setter
    private String downloadUrl;

    // upload a file
    private Part file;

    @Getter
    private List<FieldGrouping> configuredGroups = new ArrayList<>();

    public DeliveryDashboardPlugin() {
        log.info("Delivery dashboard plugin started");

        XMLConfiguration config = ConfigPlugins.getPluginConfig(title);
        config.setExpressionEngine(new XPathExpressionEngine());
        List<HierarchicalConfiguration> groups = config.configurationsAt("/group");

        for (HierarchicalConfiguration group : groups) {
            String groupLabel = group.getString("@label");
            String groupPageName = group.getString("@pageName");
            String groupDocumentType = group.getString("@documentType");

            FieldGrouping grp = new FieldGrouping();
            grp.setLabel(groupLabel);
            grp.setPageName(groupPageName);
            grp.setDocumentType(groupDocumentType);
            configuredGroups.add(grp);

            List<HierarchicalConfiguration> fields = group.configurationsAt("/field");
            for (HierarchicalConfiguration field : fields) {
                MetadataField mf = new MetadataField();
                mf.setRulesetName(field.getString("@rulesetName"));
                mf.setLabel(field.getString("@label"));
                mf.setDisplayType(field.getString("@displayType", "input"));
                mf.setMetadataLevel(("@metadataLevel"));
                String cardinality = field.getString("@cardinality", "*");
                mf.setCardinality(cardinality);
                if (cardinality.equals("1") || cardinality.equals("+")) {
                    mf.setRequired(true);
                }

                mf.setMarcMainTag(field.getString("@marcMainTag"));
                mf.setMarcSubTag(field.getString("@marcSubTag"));
                mf.setValidationExpression(field.getString("@validationExpression"));
                mf.setValidationErrorText(field.getString("@validationErrorText"));
                mf.setHelpMessage(field.getString("@helpMessage"));

                switch (mf.getDisplayType()) {
                    case "dropdown":
                    case "person":
                    case "corporate":

                        List<HierarchicalConfiguration> valueList = field.configurationsAt("/selectfield");
                        for (HierarchicalConfiguration v : valueList) {
                            SelectItem si = new SelectItem(v.getString("@value"), v.getString("@label"));
                            mf.getSelectList().add(si);
                        }

                        break;
                    default:
                        break;
                }

                grp.getFields().add(mf);
            }
        }

        //        value = ConfigPlugins.getPluginConfig(title).getString("value", "default value");
    }

    public void save() {
        String fileName = file.getSubmittedFileName();
        String contentType = file.getContentType();
        long size = file.getSize();
        System.out.println(fileName);
        // ...

        // TODO save files in user home temp dir

        // TODO validate files after upload, see 02-1-1_Upload-Pruefprozesse.docx

    }

    public Part getFile() {
        return file;
    }

    public void setFile(Part file) {
        this.file = file;
        save();
    }

    public void previousPage() {
        switch (navigation) {
            case "main":
                // first page, do nothing,
                break;
            case "upload":
                navigation = "main";
                break;
            case "data1":
                navigation = "upload";
                break;
            case "data2":
                navigation = "data1";
                break;
            case "data3":
                navigation = "data2";
                break;

            case "overview":
                navigation = "data3";
                break;

            case "finish":
                navigation = "overview";
                break;

            default:
                break;
        }
    }

    public void nextPage() {
        switch (navigation) {
            case "main":
                navigation = "upload";
                break;
            case "upload":
                navigation = "data1";
                break;
            case "data1":
                navigation = "data2";
                break;
            case "data2":
                navigation = "data3";
                break;
            case "data3":
                navigation = "overview";
                break;
            case "overview":
                navigation = "finish";
                break;
            default:
                break;
        }
    }
    @Getter @Setter
    private MetadataField currentField;

    public void duplicateMetadataField() {


        System.out.println("Found field to duplicate: " + currentField.getLabel());

    }

}
