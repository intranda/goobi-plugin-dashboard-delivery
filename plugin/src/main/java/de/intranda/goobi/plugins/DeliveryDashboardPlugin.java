package de.intranda.goobi.plugins;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.faces.model.SelectItem;
import javax.servlet.http.Part;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.User;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IDashboardPlugin;

import de.intranda.goobi.plugins.utils.FieldGrouping;
import de.intranda.goobi.plugins.utils.MetadataField;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.BeanHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.enums.PropertyType;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.MySQLHelper;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.PropertyManager;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.Corporate;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.UGHException;
import ugh.fileformats.mets.MetsMods;

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
    @Setter
    private MetadataField currentField;

    @Getter
    private PluginGuiType pluginGuiType = PluginGuiType.FULL;

    @Getter
    @Setter
    private String downloadUrl;

    // upload a file
    private Part file;

    // TODO get it from configuration file
    private String processTemplateName = "Standard";
    private String zdbProcessTemplateName = "ZDB_template";

    private String monographicDocType;
    private String zdbTitleDocType;
    private String journalDocType;
    private String issueDocType;

    @Getter
    private List<FieldGrouping> configuredGroups = new ArrayList<>();

    @Getter
    private List<Path> files = new ArrayList<>();

    private Path temporaryFolder;

    public DeliveryDashboardPlugin() {
        log.info("Delivery dashboard plugin started");
        try {
            temporaryFolder = Files.createTempDirectory("delivery");
        } catch (IOException e) {
            log.error(e);
        }
    }

    private void readConfiguration() {
        XMLConfiguration config = ConfigPlugins.getPluginConfig(title);
        config.setExpressionEngine(new XPathExpressionEngine());
        configuredGroups.clear();

        monographicDocType = config.getString("/doctypes/monographic", "Monograph");
        zdbTitleDocType = config.getString("/doctypes/zdbRecordType", "ZdbTitle");
        journalDocType = config.getString("/doctypes/journalType", "Periodical");
        issueDocType = config.getString("/doctypes/issueType", "PeriodicalVolume");

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
                    case "picklist":
                        List<HierarchicalConfiguration> valueList = field.configurationsAt("/selectfield");
                        for (HierarchicalConfiguration v : valueList) {
                            SelectItem si = new SelectItem(v.getString("@value"), v.getString("@label"));
                            mf.getSelectList().add(si);
                        }

                        break;

                    case "journaltitles":
                        mf.setSelectList(generateListOfJournalTitles());
                        break;
                    default:
                        break;
                }

                grp.getFields().add(mf);
            }
        }
    }

    public void save() {
        if (file == null) {
            // no file selected, abort
            return;
        }

        String fileName = file.getSubmittedFileName();

        try (InputStream in = file.getInputStream()) {
            Path destination = Paths.get(temporaryFolder.toString(), fileName.replaceAll("\\W", "_"));
            Files.copy(in, destination);
            files.add(destination);
        } catch (IOException e) {
            log.error(e);
        }

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
            case "titleSelection":
                navigation = "main";
                break;
            case "newIssue":
            case "newTitle":
                navigation = "titleSelection";
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
                createProcess();

                break;
            default:
                break;
        }
    }

    private void createProcess() {
        // TODO do all use the same template? Or each institution a different one? All in the same project or a new project for each user?
        Process template = ProcessManager.getProcessByTitle(processTemplateName);

        Prefs prefs = template.getRegelsatz().getPreferences();
        String processTitle = "TODO"; // TODO institution + user + some record metadata?

        // create fileformat, import entered metadata
        Fileformat fileformat = createFileformat(prefs);

        // save metadata and create goobi process
        Process process = new BeanHelper().createAndSaveNewProcess(template, processTitle, fileformat);

        //  after creation, move uploaded files into process source folder
        try {
            String sourceFolder = process.getSourceDirectory();
            Path destinationFolder = Paths.get(sourceFolder);
            if (!StorageProvider.getInstance().isFileExists(destinationFolder)) {
                StorageProvider.getInstance().createDirectories(destinationFolder);
            }
            for (Path uploadedFile : files) {
                StorageProvider.getInstance().move(uploadedFile, Paths.get(destinationFolder.toString(), uploadedFile.getFileName().toString()));
            }
        } catch (IOException | InterruptedException | SwapException | DAOException e) {
            log.error(e);
        }

        createProperties(process);

        // TODO send success mail, start any automatic tasks

    }

    private Fileformat createFileformat(Prefs prefs) {

        Fileformat fileformat = null;
        try {
            fileformat = new MetsMods(prefs);
            DigitalDocument dd = new DigitalDocument();
            fileformat.setDigitalDocument(dd);

            DocStruct docstruct = null;
            DocStruct anchor = null;
            DocStruct physical = null;
            physical = dd.createDocStruct(prefs.getDocStrctTypeByName("BoundBook"));
            dd.setPhysicalDocStruct(physical);
            if (documentType.equals("monograph")) {
                docstruct = dd.createDocStruct(prefs.getDocStrctTypeByName(monographicDocType));
                dd.setLogicalDocStruct(docstruct);
                Metadata md = createIdentifierMetadata(prefs);
                docstruct.addMetadata(md);
            } else if (documentType.equals("journal")) {
                docstruct = dd.createDocStruct(prefs.getDocStrctTypeByName(zdbTitleDocType));
                dd.setLogicalDocStruct(docstruct);
                Metadata md = createIdentifierMetadata(prefs);
                docstruct.addMetadata(md);
            } else if (documentType.equals("issue")) {
                anchor = dd.createDocStruct(prefs.getDocStrctTypeByName(journalDocType));
                docstruct = dd.createDocStruct(prefs.getDocStrctTypeByName(issueDocType));
                dd.setLogicalDocStruct(anchor);
                anchor.addChild(docstruct);
                Metadata md = createIdentifierMetadata(prefs);
                docstruct.addMetadata(md);
                md = createIdentifierMetadata(prefs);
                anchor.addMetadata(md);
            }

            for (FieldGrouping fg : configuredGroups) {
                if (fg.getDocumentType().equals(documentType)) {
                    importMetadata(prefs, docstruct, fg);
                }
            }

        } catch (UGHException e) {
            log.error(e);
        }
        return fileformat;
    }

    private void importMetadata(Prefs prefs, DocStruct docstruct, FieldGrouping fg) {

        for (MetadataField mf : fg.getFields()) {
            if (StringUtils.isNotBlank(mf.getValue())) {
                switch (mf.getDisplayType()) {
                    case "person":
                        try {
                            Person person = new Person(prefs.getMetadataTypeByName(mf.getRole()));
                            person.setFirstname(mf.getValue());
                            person.setLastname(mf.getValue2());
                            docstruct.addPerson(person);
                        } catch (MetadataTypeNotAllowedException e) {
                            log.error(e);
                        }
                        break;
                    case "corporate":

                        try {
                            Corporate corp = new Corporate(prefs.getMetadataTypeByName(mf.getRole()));
                            corp.setMainName(mf.getValue());
                            docstruct.addCorporate(corp);
                        } catch (MetadataTypeNotAllowedException e) {
                            log.error(e);
                        }

                        break;

                    case "picklist":
                        try {
                            Metadata md = new Metadata(prefs.getMetadataTypeByName(mf.getRole()));
                            md.setValue(mf.getValue());
                            docstruct.addMetadata(md);
                        } catch (MetadataTypeNotAllowedException e) {
                            log.error(e);
                        }
                        break;

                    default:
                        // input, textarea, dropdown, ...
                        try {
                            Metadata md = new Metadata(prefs.getMetadataTypeByName(mf.getRulesetName()));
                            md.setValue(mf.getValue());
                            docstruct.addMetadata(md);
                        } catch (MetadataTypeNotAllowedException e) {
                            log.error("Error adding " + mf.getRulesetName());
                        }
                        break;
                }
            }
        }
    }

    public void duplicateMetadataField() {
        // TODO
        System.out.println("Found field to duplicate: " + currentField.getLabel());

        // TODO find field in group, get location of the field in list, add a second field one position behind

    }

    public void startNewImport() {
        // cleanup entered metadata by reloading configuration
        readConfiguration();

        // delete previous uploaded files
        StorageProvider.getInstance().deleteDataInDir(temporaryFolder);
        files.clear();

    }

    public void createJournalTitle() {

        Process template = ProcessManager.getProcessByTitle(zdbProcessTemplateName);
        Prefs prefs = template.getRegelsatz().getPreferences();
        String processTitle = "zdb"; // TODO zdb + institution + user + some record metadata?

        // create fileformat, import entered metadata
        Fileformat fileformat = createFileformat(prefs);

        // save metadata and create goobi process
        Process process = new BeanHelper().createAndSaveNewProcess(template, processTitle, fileformat);

        createProperties(process);

        // TODO send mail to zlb staff
    }

    private List<SelectItem> generateListOfJournalTitles() {

        List<SelectItem> availableTitles = new ArrayList<>();

        // TODO propulate a pickup list for issue creation
        // - search in journal processes only (special project, process title starts with a specific term?)
        // - was created by this institution (or user?)
        // - has no issue yet OR has a zdb id (metadata is filled)
        // - approved by zlb (has reached a certain step) ?

        String institutionName = Helper.getCurrentUser().getInstitutionName();
        String zdbIdName = "CatalogIDPeriodicalDB"; // TODO config
        //        SET @doctype='Periodical';
        //        SET @institutionName='test';
        //
        //        # get all title records with existing zdb id
        //        select * from metadata where processid in (
        //        select metadata.processid from prozesseeigenschaften left join metadata on prozesseeigenschaften.prozesseID = metadata.processid
        //        # limit result to current institution
        //        where titel ="Institution" and wert =@institutionName
        //        # limit result to periodica
        //        and metadata.name = "DocStruct" and metadata.value=@doctype
        //        ) and metadata.name="CatalogIDPeriodicalDB"
        //        UNION
        //        # get all title records without issues (id was only used once)
        //        select * from metadata where processid in (
        //        select metadata.processid from prozesseeigenschaften left join metadata on prozesseeigenschaften.prozesseID = metadata.processid
        //        where titel ="Institution" and wert =@institutionName and metadata.name="CatalogIDDigital"
        //        and not exists (select * from metadata m2 where m2.name="CatalogIDPeriodicalDB" and m2.processid = metadata.processid)
        //        group by metadata.value having count(metadata.value) =1) ;

        StringBuilder sql = new StringBuilder();

        sql.append("select * from metadata where processid in ( ");
        sql.append("select metadata.processid from prozesseeigenschaften left join metadata on prozesseeigenschaften.prozesseID = ");
        sql.append("metadata.processid where titel =\"Institution\" and wert = ? ");
        sql.append("and metadata.name = \"DocStruct\" and metadata.value= ? ");
        sql.append(") and metadata.name= ? ");
        sql.append("UNION ");
        sql.append("select * from metadata where processid in ( ");
        sql.append("select processid from metadata where processid in ( ");
        sql.append("select metadata.processid from prozesseeigenschaften left join metadata on prozesseeigenschaften.prozesseID = ");
        sql.append("metadata.processid where titel =\"Institution\" and wert = ? ");
        sql.append("and metadata.name = \"DocStruct\" and metadata.value= ? ");
        sql.append("and not exists (select * from metadata m2 where m2.name= ? and m2.processid = metadata.processid) ");
        sql.append(") and metadata.name=\"CatalogIDDigital\"  group by metadata.value having count(metadata.value)=1) ");

        Map<Integer, Map<String, String>> results = null;
        Connection connection = null;
        try {
            connection = MySQLHelper.getInstance().getConnection();
            results = new QueryRunner().query(connection, sql.toString(), resultSetToMapHandler, institutionName, zdbTitleDocType, zdbIdName,
                    institutionName, zdbTitleDocType, zdbIdName);
        } catch (SQLException e) {
            log.error(e);
        } finally {
            if (connection != null) {
                try {
                    MySQLHelper.closeConnection(connection);
                } catch (SQLException e) {
                    log.error(e);
                }
            }
        }

        for (Integer processid : results.keySet()) {
            String title = results.get(processid).get("TitleDocMain");
            SelectItem item = new SelectItem(processid, title);
            availableTitles.add(item);
        }

        return availableTitles;
    }

    public static ResultSetHandler<Map<Integer, Map<String, String>>> resultSetToMapHandler =
            new ResultSetHandler<Map<Integer, Map<String, String>>>() {

        @Override
        public Map<Integer, Map<String, String>> handle(ResultSet rs) throws SQLException {
            Map<Integer, Map<String, String>> answer = new HashMap<>();
            try {
                while (rs.next()) {
                    Integer processid = rs.getInt("processid");
                    String metadataName = rs.getString("name");
                    String metadataValue = rs.getString("value");
                    Map<String, String> metadataMap = new HashMap<>();
                    if (answer.containsKey(processid)) {
                        metadataMap = answer.get(processid);
                    } else {
                        metadataMap = new HashMap<>();
                        answer.put(processid, metadataMap);
                    }
                    metadataMap.put(metadataName, metadataValue);
                }
            } finally {
                if (rs != null) {
                    rs.close();
                }
            }
            return answer;
        }
    };

    private void createProperties(Process process) {
        User user = Helper.getCurrentUser();
        String acccountName = "";
        String institutionName = "";
        if (user != null) {
            acccountName = user.getLogin();
            institutionName = user.getInstitution().getLongName();
        }

        // add properties
        Processproperty userProperty = new Processproperty();
        userProperty.setProcessId(process.getId());
        userProperty.setProzess(process);
        userProperty.setTitel("UserName");
        userProperty.setType(PropertyType.String);
        userProperty.setWert(acccountName);
        process.getEigenschaften().add(userProperty);

        Processproperty institutionProperty = new Processproperty();
        institutionProperty.setProcessId(process.getId());
        institutionProperty.setProzess(process);
        institutionProperty.setTitel("Institution");
        institutionProperty.setType(PropertyType.String);
        institutionProperty.setWert(institutionName);
        process.getEigenschaften().add(institutionProperty);

        PropertyManager.saveProcessProperty(userProperty);
        PropertyManager.saveProcessProperty(institutionProperty);
    }

    private Metadata createIdentifierMetadata(Prefs prefs) {
        Metadata md = null;
        try {
            md = new Metadata(prefs.getMetadataTypeByName("CatalogIDDigital"));
        } catch (MetadataTypeNotAllowedException e) {
        }
        UUID uuid = UUID.randomUUID();
        md.setValue(uuid.toString());
        return md;
    }

}
