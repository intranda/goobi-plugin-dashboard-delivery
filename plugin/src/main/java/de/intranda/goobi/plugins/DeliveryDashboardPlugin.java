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
import org.goobi.beans.Institution;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.User;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IDashboardPlugin;
import org.goobi.vocabulary.Field;
import org.goobi.vocabulary.VocabRecord;

import de.intranda.goobi.plugins.utils.FieldGrouping;
import de.intranda.goobi.plugins.utils.MetadataField;
import de.intranda.goobi.plugins.utils.ProcessMetadataManager;
import de.intranda.goobi.plugins.utils.ProcessPaginator;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.BeanHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.enums.PropertyType;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.InstitutionManager;
import de.sub.goobi.persistence.managers.MySQLHelper;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.PropertyManager;
import de.sub.goobi.persistence.managers.UserManager;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.Corporate;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.UGHException;
import ugh.fileformats.mets.MetsMods;

@PluginImplementation
@Log4j2
public class DeliveryDashboardPlugin implements IDashboardPlugin {

    public static String vocabularyUrl;

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

    private String monographTemplateName = "Standard";
    private String journalTemplateName = "Standard";
    private String zdbProcessTemplateName = "ZDB_template";

    private String monographicDocType;
    private String zdbTitleDocType;
    private String journalDocType;
    private String issueDocType;

    private String zdbIdFieldName;

    @Getter
    private List<FieldGrouping> configuredGroups = new ArrayList<>();

    private List<MetadataField> additionalMetadata = new ArrayList<>();

    private static final String configurationName = "intranda_administration_deliveryManagement";

    @Getter
    private FieldGrouping userData;;
    @Getter
    private FieldGrouping institutionData;
    @Getter
    private FieldGrouping contactData;

    @Getter
    private List<Path> files = new ArrayList<>();

    private Path temporaryFolder;

    @Getter
    protected ProcessPaginator paginator;
    @Getter
    @Setter
    private String sortField = "erstellungsdatum desc";

    @Getter
    private List<SelectItem> metadataFields = new ArrayList<>();
    @Getter
    @Setter
    private String selectedField;
    @Getter
    @Setter
    private String searchValue;

    public DeliveryDashboardPlugin() {
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
        additionalMetadata.clear();
        vocabularyUrl = config.getString("/vocabularyServerUrl");

        monographicDocType = config.getString("/doctypes/monographic", "Monograph");
        zdbTitleDocType = config.getString("/doctypes/zdbRecordType", "ZdbTitle");
        journalDocType = config.getString("/doctypes/journalType", "Periodical");
        issueDocType = config.getString("/doctypes/issueType", "PeriodicalVolume");
        zdbIdFieldName = config.getString("/doctypes/zdbId", "CatalogIDPeriodicalDB");

        monographTemplateName = config.getString("/processtemplates/monograph");
        journalTemplateName = config.getString("/processtemplates/journal");
        zdbProcessTemplateName = config.getString("/processtemplates/zdbTitle");

        List<HierarchicalConfiguration> groups = config.configurationsAt("/group");

        List<HierarchicalConfiguration> additional = config.configurationsAt("/additionalMetadata/field");
        for (HierarchicalConfiguration field : additional) {
            MetadataField mf = new MetadataField();
            additionalMetadata.add(mf);
            mf.setRulesetName(field.getString("@rulesetName"));
            String replaceWith = field.getString("@replaceWith");
            if (StringUtils.isNotBlank(replaceWith)) {
                User user = Helper.getCurrentUser();
                if (user != null) {
                    Institution inst = user.getInstitution();
                    if (replaceWith.equals("institution")) {
                        mf.setValue(inst.getLongName());
                    } else if (replaceWith.startsWith("institution")) {
                        String val = inst.getAdditionalData().get(replaceWith);
                        if (!"false".equals(val)) {
                            mf.setValue(val);
                        }
                    } else {
                        String val = user.getAdditionalData().get(replaceWith);
                        if (!"false".equals(val)) {
                            mf.setValue(val);
                        }
                    }
                }
            }
            String defaultValue = field.getString("@defaultValue");
            if (StringUtils.isNotBlank(defaultValue)) {
                mf.setValue(defaultValue);
            }
        }

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

                String replaceWith = field.getString("@replaceWith");
                if (StringUtils.isNotBlank(replaceWith)) {
                    User user = Helper.getCurrentUser();
                    if (user != null) {
                        Institution inst = user.getInstitution();
                        if (replaceWith.startsWith("institution")) {
                            String val = inst.getAdditionalData().get(replaceWith);
                            if (!"false".equals(val)) {
                                mf.setValue(val);
                            }
                        } else {
                            String val = user.getAdditionalData().get(replaceWith);
                            if (!"false".equals(val)) {
                                mf.setValue(val);
                            }
                        }
                    }
                }

                switch (mf.getDisplayType()) {
                    case "dropdown":
                    case "picklist":
                        List<HierarchicalConfiguration> valueList = field.configurationsAt("/selectfield");
                        for (HierarchicalConfiguration v : valueList) {
                            SelectItem si = new SelectItem(v.getString("@value"), v.getString("@label"));
                            mf.getSelectList().add(si);
                        }

                        break;
                    case "corporate":
                    case "person":
                    case "vocabulary":
                        String vocabularyName = field.getString("/vocabulary/@name");
                        String displayField = field.getString("/vocabulary/@displayField", null);
                        String importField = field.getString("/vocabulary/@importField", null);
                        mf.setVocabulary(vocabularyName, displayField, importField);
                        break;
                    case "journaltitles":
                        mf.setSelectList(generateListOfJournalTitles());
                        break;
                    default:
                        break;
                }

                grp.getFields().add(mf);

                switch (mf.getDisplayType()) {
                    case "person":
                    case "corporate":
                    case "picklist":
                        for (SelectItem s : mf.getSelectList()) {
                            boolean match = false;
                            for (SelectItem si : metadataFields) {
                                if (si.getValue().equals(s.getValue())) {
                                    match = true;
                                    break;
                                }
                            }
                            if (!match) {
                                metadataFields.add(new SelectItem(s.getValue(), s.getLabel()));
                            }
                        }

                    case "journaltitles":
                        break;
                    default:
                        boolean match = false;
                        for (SelectItem si : metadataFields) {
                            if (si.getValue().equals(mf.getRulesetName())) {
                                match = true;
                                break;
                            }
                        }
                        if (!match) {
                            metadataFields.add(new SelectItem(mf.getRulesetName(), mf.getLabel()));
                        }
                }
            }
        }
    }

    public void readUserConfiguration() {
        User user = Helper.getCurrentUser();
        if (user != null) {
            Institution inst = user.getInstitution();
            XMLConfiguration conf = ConfigPlugins.getPluginConfig(configurationName);
            conf.setExpressionEngine(new XPathExpressionEngine());

            List<HierarchicalConfiguration> fields = conf.configurationsAt("/fields/field");

            userData = new FieldGrouping();
            userData.setDocumentType("user");
            userData.setLabel("User");
            //  add regular user data like account name, email, ....

            getUserData(user);

            institutionData = new FieldGrouping();
            institutionData.setDocumentType("institution");
            institutionData.setLabel("Institution");
            // add regular institution data

            getInstitutionData(inst);

            contactData = new FieldGrouping();
            contactData.setDocumentType("contact");
            contactData.setLabel("Contact");

            for (HierarchicalConfiguration hc : fields) {

                String type = hc.getString("@type");

                String fieldType = hc.getString("@fieldType", "input");
                String label = hc.getString("@label");
                String name = hc.getString("@name");
                boolean required = hc.getBoolean("@required", false);
                String validation = hc.getString("@validation", null);
                String validationErrorMessage = hc.getString("@validationErrorDescription", null);
                String helpMessage = hc.getString("@helpMessage");

                MetadataField mf = new MetadataField();
                mf.setLabel(label);
                mf.setDisplayType(fieldType);
                mf.setRequired(required);
                mf.setCardinality(required ? "1" : "?");
                mf.setValidationExpression(validation);
                mf.setValidationErrorText(validationErrorMessage);
                mf.setHelpMessage(helpMessage);
                mf.setRulesetName(name);

                if (fieldType.equals("dropdown") || fieldType.equals("combo")) {
                    List<HierarchicalConfiguration> valueList = hc.configurationsAt("/value");
                    for (HierarchicalConfiguration v : valueList) {
                        SelectItem si = new SelectItem(v.getString("."), v.getString("."));
                        mf.getSelectList().add(si);
                    }
                }
                if ("institution".equals(type) && name.startsWith("contact")) {
                    contactData.getFields().add(mf);
                    mf.setValue(inst.getAdditionalData().get(name));
                } else if ("institution".equals(type)) {
                    institutionData.getFields().add(mf);
                    mf.setValue(inst.getAdditionalData().get(name));
                } else {
                    userData.getFields().add(mf);
                    mf.setValue(user.getAdditionalData().get(name));
                }
            }
        }
    }

    private void getUserData(User user) {
        MetadataField loginField = new MetadataField();
        loginField.setLabel(Helper.getTranslation("login_new_account_accountName"));
        loginField.setDisplayType("output");
        loginField.setRequired(true);
        loginField.setCardinality("1");
        loginField.setAdditionalType("login");
        loginField.setValue(user.getLogin());
        userData.getFields().add(loginField);

        MetadataField firstname = new MetadataField();
        firstname.setLabel(Helper.getTranslation("firstname"));
        firstname.setDisplayType("input");
        firstname.setRequired(true);
        firstname.setCardinality("1");
        firstname.setAdditionalType("firstname");
        firstname.setValue(user.getVorname());
        userData.getFields().add(firstname);

        MetadataField lastname = new MetadataField();
        lastname.setLabel(Helper.getTranslation("lastname"));
        lastname.setDisplayType("input");
        lastname.setRequired(true);
        lastname.setCardinality("1");
        lastname.setAdditionalType("lastname");
        lastname.setValue(user.getNachname());
        userData.getFields().add(lastname);

        MetadataField emailAddress = new MetadataField();
        emailAddress.setLabel(Helper.getTranslation("login_new_account_emailAddress"));
        emailAddress.setDisplayType("output");
        emailAddress.setRequired(true);
        emailAddress.setCardinality("1");
        emailAddress.setAdditionalType("email");
        emailAddress.setValue(user.getEmail());
        userData.getFields().add(emailAddress);
    }

    private void getInstitutionData(Institution inst) {
        MetadataField shortName = new MetadataField();
        shortName.setLabel(Helper.getTranslation("institution_shortName"));
        shortName.setDisplayType("input");
        shortName.setRequired(true);
        shortName.setCardinality("1");
        shortName.setAdditionalType("shortName");
        shortName.setValue(inst.getShortName());
        institutionData.getFields().add(shortName);

        MetadataField longName = new MetadataField();
        longName.setLabel(Helper.getTranslation("institution_longName"));
        longName.setDisplayType("input");
        longName.setRequired(true);
        longName.setCardinality("1");
        longName.setAdditionalType("longName");
        longName.setValue(inst.getLongName());
        institutionData.getFields().add(longName);
    }

    // update user object, save it
    public void saveUserData() {
        User user = Helper.getCurrentUser();
        for (MetadataField mf : userData.getFields()) {
            if ("firstname".equals(mf.getAdditionalType())) {
                user.setVorname(mf.getValue());
            } else if ("lastname".equals(mf.getAdditionalType())) {
                user.setNachname(mf.getValue());
            }
        }
        try {
            UserManager.saveUser(user);
        } catch (DAOException e) {
            log.error(e);
        }
    }

    public void saveInstitutionData() {
        User user = Helper.getCurrentUser();
        Institution institution = user.getInstitution();
        for (MetadataField mf : institutionData.getFields()) {
            if (StringUtils.isNotBlank(mf.getAdditionalType())) {
                if ("shortName".equals(mf.getAdditionalType())) {
                    institution.setShortName(mf.getValue());
                } else if ("longName".equals(mf.getAdditionalType())) {
                    institution.setLongName(mf.getValue());
                }
            } else {
                institution.getAdditionalData().put(mf.getRulesetName(), mf.getValue());
            }
        }
        InstitutionManager.saveInstitution(institution);
    }

    public void saveContactData() {
        User user = Helper.getCurrentUser();
        Institution institution = user.getInstitution();
        for (MetadataField mf : contactData.getFields()) {
            institution.getAdditionalData().put(mf.getRulesetName(), mf.getValue());
        }

        InstitutionManager.saveInstitution(institution);
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
            case "userdata":
            case "titleSelection":
            case "existingData":
                navigation = "main";
                documentType = "";
                break;
            case "user":
            case "institution":
            case "contact":
                navigation = "userdata";
                break;
            case "newIssue":
            case "newTitle":
                navigation = "titleSelection";
                break;
            case "upload":
                navigation = "main";
                documentType = "";
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
        if (!validateCurrentField()) {
            return;
        }
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
                createProcess(monographTemplateName);

                break;
            default:
                break;
        }
    }

    private boolean validateCurrentField() {
        boolean valid = true;
        for (FieldGrouping fg : configuredGroups) {
            if (documentType.equals(fg.getDocumentType()) && navigation.equals(fg.getPageName())) {
                for (MetadataField mf : fg.getFields()) {
                    mf.validateField(null, null, mf.getValue());
                    if (!mf.isFieldValid()) {
                        valid = false;
                    }
                }
            }
        }
        return valid;
    }

    private void createProcess(String templateName) {

        User user = Helper.getCurrentUser();
        String acccountName = "";
        String institutionName = "";
        if (user != null) {
            acccountName = user.getLogin();
            institutionName = user.getInstitution().getShortName();
        }
        String identifier = UUID.randomUUID().toString();

        // do all use the same template? Or each institution a different one? All in the same project or a new project for each user?
        Process template = ProcessManager.getProcessByTitle(templateName);
        Prefs prefs = template.getRegelsatz().getPreferences();
        // username + shortname + counter
        String processTitle = acccountName + "_" + institutionName + "_" + identifier;

        // create fileformat, import entered metadata
        Fileformat fileformat = createFileformat(prefs, identifier);

        // save metadata and create goobi process
        Process process = new BeanHelper().createAndSaveNewProcess(template, processTitle.replaceAll("\\W", "").toLowerCase(), fileformat);

        //  after creation, move uploaded files into process source folder
        try {
            DocStructType pageType = prefs.getDocStrctTypeByName("page");
            MetadataType physType = prefs.getMetadataTypeByName("physPageNumber");
            MetadataType logType = prefs.getMetadataTypeByName("logicalPageNumber");
            DigitalDocument dd = fileformat.getDigitalDocument();
            DocStruct physical = dd.getPhysicalDocStruct();
            DocStruct logical = dd.getLogicalDocStruct();
            String sourceFolder = process.getImagesTifDirectory(false);
            Path destinationFolder = Paths.get(sourceFolder);
            if (!StorageProvider.getInstance().isFileExists(destinationFolder)) {
                StorageProvider.getInstance().createDirectories(destinationFolder);
            }
            int order = 1;
            for (Path uploadedFile : files) {
                StorageProvider.getInstance().move(uploadedFile, Paths.get(destinationFolder.toString(), uploadedFile.getFileName().toString()));

                DocStruct page = dd.createDocStruct(pageType);
                Metadata phys = new Metadata(physType);
                phys.setValue("" + order);
                page.addMetadata(phys);
                Metadata log = new Metadata(logType);
                log.setValue("-");
                page.addMetadata(log);
                // add file name
                page.setImageName(uploadedFile.getFileName().toString());
                physical.addChild(page);
                logical.addReferenceTo(page, "logical_physical");
                order = order + 1;
            }
        } catch (IOException | InterruptedException | SwapException | DAOException | UGHException e) {
            log.error(e);
        }

        createProperties(process, acccountName, institutionName);

        // TODO send success mail, start any automatic tasks
        navigation = "main";
    }

    private Fileformat createFileformat(Prefs prefs, String identifier) {

        /* TODO: generate metadata
        Umfang: Angabe des Umfangs unkörperlicher digitaler Medienwerke ist immer Online-Ressource. Sofern eine Seitenzählung vorhanden ist, kann dieser zusätzlich in runden Klammern angegeben werden (Beispiel: Online-Ressource (72 Seiten).
        Dateiformat und Dateigröße: Dateiformat und –größe der  zu beschreibenden Ressource.

         */

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

            Metadata md = null;
            try {
                md = new Metadata(prefs.getMetadataTypeByName("CatalogIDDigital"));
                md.setValue(identifier);
            } catch (MetadataTypeNotAllowedException e) {
            }

            Metadata genre = new Metadata(prefs.getMetadataTypeByName("ModsGenre"));
            if (documentType.equals("monograph")) {
                docstruct = dd.createDocStruct(prefs.getDocStrctTypeByName(monographicDocType));
                dd.setLogicalDocStruct(docstruct);
                docstruct.addMetadata(md);

                genre.setValue("Buch");
                docstruct.addMetadata(genre);

            } else if (documentType.equals("journal") && navigation.equals("newTitle")) {
                docstruct = dd.createDocStruct(prefs.getDocStrctTypeByName(zdbTitleDocType));
                dd.setLogicalDocStruct(docstruct);
                docstruct.addMetadata(md);

                genre.setValue("Zeitschrift");
                docstruct.addMetadata(genre);

            } else if (documentType.equals("issue") && navigation.equals("newIssue")) {
                anchor = dd.createDocStruct(prefs.getDocStrctTypeByName(journalDocType));
                docstruct = dd.createDocStruct(prefs.getDocStrctTypeByName(issueDocType));
                dd.setLogicalDocStruct(anchor);
                anchor.addChild(docstruct);
                docstruct.addMetadata(md);

                genre.setValue("Ausgabe");
                docstruct.addMetadata(genre);

                for (FieldGrouping fg : configuredGroups) {
                    if (fg.getDocumentType().equals("issue")) {
                        for (MetadataField mf : fg.getFields()) {
                            if (mf.getDisplayType().equals("journaltitles")) {
                                String processid = mf.getValue();
                                // load metadata
                                Process otherProc = ProcessManager.getProcessById(Integer.parseInt(processid));
                                try {
                                    Fileformat other = otherProc.readMetadataFile();
                                    // copy metadata to anchor
                                    DocStruct templateDocstruct = other.getDigitalDocument().getLogicalDocStruct();
                                    if (templateDocstruct.getAllMetadata() != null) {
                                        for (Metadata templateMetadata : templateDocstruct.getAllMetadata()) {
                                            try {
                                                Metadata clone = new Metadata(templateMetadata.getType());
                                                clone.setValue(templateMetadata.getValue());
                                                anchor.addMetadata(clone);
                                            } catch (Exception e) {
                                                log.trace(e);
                                            }
                                        }
                                    }
                                    if (templateDocstruct.getAllPersons() != null) {
                                        for (Person templatePerson : templateDocstruct.getAllPersons()) {
                                            try {
                                                Person clone = new Person(templatePerson.getType());
                                                clone.setFirstname(templatePerson.getFirstname());
                                                clone.setLastname(templatePerson.getLastname());
                                                anchor.addPerson(clone);
                                            } catch (Exception e) {
                                                log.trace(e);
                                            }
                                        }
                                    }
                                    if (templateDocstruct.getAllCorporates() != null) {
                                        for (Corporate templateCorporate : templateDocstruct.getAllCorporates()) {
                                            try {
                                                Corporate clone = new Corporate(templateCorporate.getType());
                                                clone.setMainName(templateCorporate.getMainName());
                                                anchor.addCorporate(clone);
                                            } catch (Exception e) {
                                                log.trace(e);
                                            }
                                        }
                                    }

                                } catch (IOException | InterruptedException | SwapException | DAOException e) {
                                    log.error(e);
                                }

                            }
                        }
                    }
                }
            }

            for (FieldGrouping fg : configuredGroups) {
                if (fg.getDocumentType().equals(documentType)) {
                    importMetadata(prefs, docstruct, fg);
                }
            }

            Metadata responsibility = new Metadata(prefs.getMetadataTypeByName("NoteStatementOfResponsibility"));

            // first get authors
            Map<String, String> data = new HashMap<>();
            if (docstruct.getAllPersons() != null) {
                for (Person p : docstruct.getAllPersons()) {
                    String existing = data.get(p.getType().getLanguage("de"));
                    if (StringUtils.isNotBlank(existing)) {
                        existing += ", ";
                    } else {
                        existing = "";
                        data.put(p.getType().getLanguage("de"), existing);
                    }
                    existing += p.getFirstname() + " " + p.getLastname();
                }
            }

            if (docstruct.getAllCorporates() != null) {
                for (Corporate c : docstruct.getAllCorporates()) {
                    String existing = data.get(c.getType().getLanguage("de"));
                    if (StringUtils.isNotBlank(existing)) {
                        existing += ", ";
                    } else {
                        existing = "";
                    }
                    existing += c.getMainName();
                    data.put(c.getType().getLanguage("de"), existing);
                }
            }

            StringBuilder sb = new StringBuilder();
            // first author
            String aut = data.get("Autor");
            if (aut != null) {
                sb.append(aut);
            }
            // then add other types
            for (String type : data.keySet()) {
                if (!type.equals("Autor")) {
                    if (sb.length() > 1) {
                        sb.append(" ; ");
                    }
                    sb.append(type + " " + data.get(type));
                }
            }
            responsibility.setValue(sb.toString());
            docstruct.addMetadata(responsibility);

            for (MetadataField f : additionalMetadata) {
                Metadata meta = new Metadata(prefs.getMetadataTypeByName(f.getRulesetName()));
                meta.setValue(f.getValue());
                docstruct.addMetadata(meta);
            }

            if (docstruct.getType().getName().equals(issueDocType)) {
                String publicationYear = "", year = "", volume = "";

                for (Metadata meta : docstruct.getAllMetadata()) {
                    switch (meta.getType().getName()) {
                        case "PublicationYear":
                            publicationYear = meta.getValue();
                            break;
                        case "CurrentNo": // Jahrgang
                            year = meta.getValue();
                            break;
                        case "VolumeNo": // Bandnummer
                            volume = meta.getValue();
                            break;

                        default:
                            break;

                    }
                }
                String order = "";

                if (StringUtils.isBlank(year) && StringUtils.isBlank(volume)) {
                    order = publicationYear + "00000";
                }

                if (StringUtils.isNotBlank(year)) {
                    order += year;
                }
                if (StringUtils.isNotBlank(volume)) {
                    if (volume.length() > 1) {
                        order += volume;
                    } else {
                        order += "0" + volume;
                    }
                }

                while (order.length() < 9) {
                    order += "0";
                }
                Metadata sorting = new Metadata(prefs.getMetadataTypeByName("CurrentNoSorting"));
                sorting.setValue(order);
                docstruct.addMetadata(sorting);

            }

        } catch (UGHException e) {
            log.error(e);
        }
        return fileformat;
    }

    private void importMetadata(Prefs prefs, DocStruct docstruct, FieldGrouping fg) {

        for (MetadataField mf : fg.getFields()) {
            if (StringUtils.isNotBlank(mf.getValue()) && StringUtils.isNotBlank(mf.getRulesetName())) {
                switch (mf.getDisplayType()) {
                    case "person":
                        try {
                            String roleTerm = getValueFromRecord(mf, mf.getRole());
                            Person person = new Person(prefs.getMetadataTypeByName(roleTerm));
                            person.setFirstname(mf.getValue());
                            person.setLastname(mf.getValue2());
                            docstruct.addPerson(person);
                        } catch (MetadataTypeNotAllowedException e) {
                            log.error(e);
                        }
                        break;
                    case "corporate":

                        try {
                            String roleTerm = getValueFromRecord(mf, mf.getRole());
                            Corporate corp = new Corporate(prefs.getMetadataTypeByName(roleTerm));
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

    private String getValueFromRecord(MetadataField mf, String field) {
        String answer = null;
        for (VocabRecord rec : mf.getVocabList()) {
            if (field.equals(String.valueOf(rec.getId()))) {
                for (Field f : rec.getFields()) {
                    if (StringUtils.isNotBlank(mf.getVocabularyImportField()) && f.getLabel().equals(mf.getVocabularyImportField())) {
                        answer = f.getValue();
                        break;
                    } else if (StringUtils.isBlank(mf.getVocabularyImportField()) && StringUtils.isNotBlank(mf.getVocabularyDisplayField())
                            && f.getLabel().equals(mf.getVocabularyDisplayField())) {
                        answer = f.getValue();
                        break;
                    } else if (StringUtils.isBlank(mf.getVocabularyImportField()) && StringUtils.isBlank(mf.getVocabularyDisplayField())
                            && f.getDefinition().isMainEntry()) {
                        answer = f.getValue();
                        break;
                    }
                }
            }
        }
        return answer;
    }

    public void duplicateMetadataField() {
        // find field in group,
        for (FieldGrouping fg : configuredGroups) {
            if (fg.getDocumentType().equals(documentType)) {
                // get location of the field in list
                int position = 0;
                boolean found = false;
                for (MetadataField mf : fg.getFields()) {
                    if (mf == currentField) {
                        found = true;
                        break;
                    }
                    position = position + 1;
                }
                if (found) {
                    // add a second field of the same type one position behind, mark as optional
                    MetadataField clone = currentField.cloneField();
                    fg.getFields().add(position + 1, clone);
                    break;
                }
            }
        }
    }

    public void startNewImport() {
        // cleanup entered metadata by reloading configuration
        readConfiguration();

        // delete previous uploaded files
        StorageProvider.getInstance().deleteDataInDir(temporaryFolder);
        files.clear();

    }

    public void createJournalTitle() {

        if (!validateCurrentField()) {
            return;
        }

        String identifier = UUID.randomUUID().toString();

        User user = Helper.getCurrentUser();
        String acccountName = "";
        String institutionName = "";
        if (user != null) {
            acccountName = user.getLogin();
            institutionName = user.getInstitution().getShortName();
        }
        Process template = ProcessManager.getProcessByTitle(zdbProcessTemplateName);
        Prefs prefs = template.getRegelsatz().getPreferences();
        String processTitle = "zdb" + "_" + acccountName + "_" + institutionName + "_" + identifier;

        // create fileformat, import entered metadata
        Fileformat fileformat = createFileformat(prefs, identifier);

        // save metadata and create goobi process
        Process process = new BeanHelper().createAndSaveNewProcess(template, processTitle.replaceAll("\\W", "").toLowerCase(), fileformat);

        createProperties(process, acccountName, institutionName);

        // TODO send mail to zlb staff

        navigation = "main";
    }

    public void createJournalIssue() {
        if (!validateCurrentField()) {
            return;
        }

        createProcess(journalTemplateName);
    }

    private List<SelectItem> generateListOfJournalTitles() {

        List<SelectItem> availableTitles = new ArrayList<>();

        // propulate a pickup list for issue creation
        // - search in journal processes only (special project, process title starts with a specific term?)
        // - was created by this institution (or user?)
        // - has no issue yet OR has a zdb id (metadata is filled)
        // - approved by zlb (has reached a certain step) ?

        String institutionName = Helper.getCurrentUser().getInstitutionName();

        StringBuilder sql = new StringBuilder();
        sql.append("select * from metadata where processid in ( ");
        sql.append("select processid from metadata where processid in ( ");
        sql.append("select metadata.processid from prozesseeigenschaften left join metadata on prozesseeigenschaften.prozesseID = ");
        sql.append("metadata.processid where titel =\"Institution\" and wert = ? ");
        sql.append("and metadata.name = \"DocStruct\" and metadata.value= ? ");
        sql.append(") and metadata.name= ? )");
        sql.append("UNION ");
        sql.append("select * from metadata where processid in ( ");
        sql.append("select processid from metadata where processid in ( ");
        sql.append("select metadata.processid from prozesseeigenschaften left join metadata on prozesseeigenschaften.prozesseID = ");
        sql.append("metadata.processid where titel =\"Institution\" and wert = ? ");
        sql.append("and not exists (select * from metadata m2 where m2.name= ? and m2.processid = metadata.processid) ");
        sql.append(") and metadata.name=\"CatalogIDDigital\" group by metadata.value having count(metadata.value)=1");
        sql.append(" and metadata.name = \"DocStruct\" and metadata.value= ?) ");

        Map<Integer, Map<String, String>> results = null;
        Connection connection = null;
        try {
            connection = MySQLHelper.getInstance().getConnection();
            results = new QueryRunner().query(connection, sql.toString(), resultSetToMapHandler, institutionName, zdbTitleDocType, zdbIdFieldName,
                    institutionName, zdbIdFieldName, zdbTitleDocType);
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
        if (results != null) {
            for (Integer processid : results.keySet()) {
                String title = results.get(processid).get("TitleDocMain");
                SelectItem item = new SelectItem(processid, title);
                availableTitles.add(item);
            }
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

    private void createProperties(Process process, String acccountName, String institutionName) {

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

    public void getExistingDataForInstitution() {
        readConfiguration(); // populate dropdown list

        User user = Helper.getCurrentUser();
        Institution institution = user.getInstitution();

        ProcessMetadataManager m = new ProcessMetadataManager();
        StringBuilder sql = new StringBuilder();
        sql.append("(prozesse.ProzesseID in (select prozesseID from prozesseeigenschaften where prozesseeigenschaften.Titel =");
        sql.append("'Institution' AND prozesseeigenschaften.Wert =");
        sql.append("'");
        sql.append(institution.getShortName());
        sql.append("')) ");
        // limit result to specific fields
        //  sql.append("AND (prozesse.ProzesseID IN (SELECT DISTINCT processid FROM metadata WHERE metadata.name LIKE  '%TitleDocMain%' AND MATCH (value) AGAINST ('\"+*Titel* ' IN BOOLEAN MODE)))");

        sql.append("AND prozesse.istTemplate = false ");

        paginator = new ProcessPaginator(getOrder(), sql.toString(), m);
    }

    public void search() {
        if (StringUtils.isBlank(searchValue)) {
            getExistingDataForInstitution();
            return;
        }

        User user = Helper.getCurrentUser();
        Institution institution = user.getInstitution();
        ProcessMetadataManager m = new ProcessMetadataManager();
        StringBuilder sql = new StringBuilder();
        sql.append("(prozesse.ProzesseID in (select prozesseID from prozesseeigenschaften where prozesseeigenschaften.Titel =");
        sql.append("'Institution' AND prozesseeigenschaften.Wert =");
        sql.append("'");
        sql.append(institution.getShortName());
        sql.append("')) ");

        if (StringUtils.isBlank(selectedField)) {
            sql.append("AND (prozesse.ProzesseID IN (SELECT DISTINCT processid FROM metadata WHERE MATCH (value) AGAINST ('\"+*" + searchValue
                    + "* ' IN BOOLEAN MODE)))");
        } else {
            sql.append("AND (prozesse.ProzesseID IN (SELECT DISTINCT processid FROM metadata WHERE metadata.name =  '" + selectedField
                    + "' AND value LIKE '%" + searchValue + "%' ))");
        }

        sql.append("AND prozesse.istTemplate = false ");

        paginator = new ProcessPaginator(getOrder(), sql.toString(), m);
    }

    private String getOrder() {
        String value = "erstellungsdatum desc";
        switch (sortField) {
            case "titelAsc":
                value = "titel asc";
                break;
            case "titelDesc":
                value = "titel desc";
                break;
            case "creationDateAsc":
                value = "erstellungsdatum asc";
                break;
            case "creationDateDesc":
                value = "erstellungsdatum desc";
                break;
            case "statusAsc":
                value = "sortHelperStatus";
                break;
            case "statusDesc":
                value = "sortHelperStatus desc";
                break;
            case "imagesAsc":
                value = "sortHelperImages";
                break;
            case "imagesDesc":
                value = "sortHelperImages desc";
                break;
            case "mainTitleDesc":
                value = "md1.value desc";
                break;
            case "mainTitleAsc":
                value = "md1.value";
                break;

            case "authorDesc":
                value = "md2.value desc";
                break;
            case "authorAsc":
                value = "md2.value";
                break;

            case "publicationYearDesc":
                value = "md3.value desc";
                break;
            case "publicationYearAsc":
                value = "md3.value";
                break;

        }
        return value;
    }

}
