package de.intranda.goobi.plugins;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.goobi.api.mail.SendMail;
import org.goobi.beans.Institution;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Step;
import org.goobi.beans.User;
import org.goobi.files.FileValidator;
import org.goobi.managedbeans.UserBean;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IDashboardPlugin;
import org.goobi.reporting.Report;
import org.goobi.security.authentication.IAuthenticationProvider.AuthenticationType;

import de.intranda.goobi.plugins.utils.FieldGrouping;
import de.intranda.goobi.plugins.utils.MetadataField;
import de.intranda.goobi.plugins.utils.ProcessMetadataManager;
import de.intranda.goobi.plugins.utils.ProcessPaginator;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.BeanHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.ScriptThreadWithoutHibernate;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.enums.PropertyType;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.ldap.LdapAuthentication;
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
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.UGHException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsMods;

@PluginImplementation
@Log4j2
public class DeliveryDashboardPlugin implements IDashboardPlugin {

    private static final long serialVersionUID = -6261703469505380104L;

    private static final String NEWLINE = "<br />";
    private static final String COLON = ": ";

    public static String vocabularyUrl; //NOSONAR

    @Getter
    @Setter
    private String focusField;

    @Getter
    private String title = "intranda_dashboard_delivery";

    @Getter
    private PluginType type = PluginType.Dashboard;

    @Getter
    private String guiPath = "/uii/plugin_dashboard_delivery.xhtml"; //NOSONAR

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

    // upload a file
    private transient Part file;

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

    private static final String CONFIGURATION_NAME = "intranda_administration_deliveryManagement";

    @Getter
    private FieldGrouping userData;
    @Getter
    private FieldGrouping institutionData;
    @Getter
    private FieldGrouping contactData;

    @Getter
    private FieldGrouping contact2Data;
    @Getter
    private transient List<Path> files = new ArrayList<>();

    private transient Path temporaryFolder;

    @Getter
    protected ProcessPaginator paginator;
    @Getter
    @Setter
    private String sortField = "erstellungsdatum desc"; //NOSONAR

    @Getter
    private List<SelectItem> metadataFields = new ArrayList<>();
    @Getter
    @Setter
    private String selectedField;
    @Getter
    @Setter
    private String searchValue;

    // metadata to display
    @Getter
    private List<String> metadataDisplayList = new ArrayList<>();

    @Getter
    @Setter
    private String downloadUrl;

    private String registrationMailRecipient;
    private String registrationMailSubject;
    private String registrationMailBody;

    @Getter
    private boolean incompleteUserData = false;

    @Getter
    @Setter
    private String oldPassword;

    @Getter
    @Setter
    private String newPassword;

    @Getter
    @Setter
    private String newPasswordRepeated;
    @Getter
    @Setter
    private boolean oldPasswordValid = true;
    @Getter
    @Setter
    private boolean newPasswordValid = true;
    @Getter
    @Setter
    private String passwordValidationError;

    @Getter
    @Setter
    private boolean displaySecondContact = false;

    private List<SelectItem> availableTitles = new ArrayList<>();;

    public DeliveryDashboardPlugin() {
        try {
            temporaryFolder = Files.createTempDirectory("delivery");
            readUserConfiguration();
            readConfiguration();
        } catch (IOException e) {
            log.error(e);
        }
    }

    private void readConfiguration() {

        XMLConfiguration config = ConfigPlugins.getPluginConfig(title);
        config.setExpressionEngine(new XPathExpressionEngine());
        configuredGroups.clear();
        additionalMetadata.clear();

        metadataDisplayList = Arrays.asList(config.getStringArray("/metadata"));

        vocabularyUrl = config.getString("/vocabularyServerUrl"); //NOSONAR

        monographicDocType = config.getString("/doctypes/monographic", "Monograph");
        zdbTitleDocType = config.getString("/doctypes/zdbRecordType", "ZdbTitle");
        journalDocType = config.getString("/doctypes/journalType", "Periodical");
        issueDocType = config.getString("/doctypes/issueType", "PeriodicalVolume");
        zdbIdFieldName = config.getString("/doctypes/zdbId", "CatalogIDPeriodicalDB");

        monographTemplateName = config.getString("/processtemplates/monograph");
        journalTemplateName = config.getString("/processtemplates/journal");
        zdbProcessTemplateName = config.getString("/processtemplates/zdbTitle");

        registrationMailRecipient = config.getString("/zdbTitle/recipient");
        registrationMailSubject = config.getString("/zdbTitle/subject");
        registrationMailBody = config.getString("/zdbTitle/body");

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
                    if ("institution".equals(replaceWith)) { //NOSONAR
                        mf.setValue(inst.getLongName());
                    } else if (replaceWith.startsWith("institution")) {
                        String val = inst.getAdditionalData().get(replaceWith);
                        if (!"false".equals(val)) { //NOSONAR
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
            String groupLabel = group.getString("@label"); //NOSONAR
            String groupPageName = group.getString("@pageName");
            String groupDocumentType = group.getString("@documentType");

            FieldGrouping grp = new FieldGrouping();
            grp.setLabel(groupLabel);
            grp.setPageName(groupPageName);
            grp.setDocumentType(groupDocumentType);

            grp.setDisableLabel(group.getString("@disableLabel"));
            grp.setDisableGroup(group.getBoolean("@disableGroup", false));

            configuredGroups.add(grp);

            List<HierarchicalConfiguration> fields = group.configurationsAt("/field");
            for (HierarchicalConfiguration field : fields) {
                MetadataField mf = new MetadataField();
                mf.setRulesetName(field.getString("@rulesetName"));
                mf.setLabel(field.getString("@label"));
                mf.setDisplayType(field.getString("@displayType", "input")); //NOSONAR
                mf.setMetadataLevel(("@metadataLevel"));
                String cardinality = field.getString("@cardinality", "*");
                mf.setCardinality(cardinality);
                if ("1".equals(cardinality) || "+".equals(cardinality)) {
                    mf.setRequired(true);
                }

                mf.setMarcMainTag(field.getString("@marcMainTag"));
                mf.setMarcSubTag(field.getString("@marcSubTag"));
                mf.setValidationExpression(field.getString("@validationExpression"));
                mf.setValidationErrorText(field.getString("@validationErrorText"));
                mf.setHelpMessageTitle(field.getString("@helpMessageTitle", ""));
                mf.setHelpMessage(field.getString("@helpMessage", ""));

                String replaceWith = field.getString("@replaceWith");
                if (StringUtils.isNotBlank(replaceWith)) {
                    User user = Helper.getCurrentUser();
                    if (user != null) {
                        Institution inst = user.getInstitution();
                        String val;
                        if (replaceWith.startsWith("institution")) {
                            if (replaceWith.endsWith("-name")) {
                                val = inst.getLongName();
                            } else {
                                val = inst.getAdditionalData().get(replaceWith);
                            }
                            if (!"false".equals(val) && "combo".equals(mf.getDisplayType())) { //NOSONAR
                                mf.setBooleanValue(true);
                                if (!"true".equals(val)) {
                                    mf.setValue2(val);
                                }
                            } else {
                                mf.setValue(val);
                            }
                        } else {
                            if (replaceWith.endsWith("-name")) {
                                val = user.getNachVorname();
                            } else {
                                val = user.getAdditionalData().get(replaceWith);
                            }
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

                switch (mf.getDisplayType()) {
                    case "dropdown":
                    case "picklist": //NOSONAR
                        List<HierarchicalConfiguration> valueList = field.configurationsAt("/selectfield");
                        for (HierarchicalConfiguration v : valueList) {
                            SelectItem si = new SelectItem(v.getString("@value"), v.getString("@label"));
                            mf.getSelectList().add(si);
                        }

                        break;
                    case "corporate": //NOSONAR
                    case "person": //NOSONAR
                    case "vocabulary":
                        String vocabularyName = field.getString("/vocabulary/@name");
                        String displayField = field.getString("/vocabulary/@displayField", null);
                        String importField = field.getString("/vocabulary/@importField", null);
                        mf.setVocabulary(vocabularyName, displayField, importField);
                        break;
                    case "journaltitles": //NOSONAR
                        mf.setSelectList(generateListOfJournalTitles());
                        break;
                    default:
                        break;
                }

                grp.getFields().add(mf);

                switch (mf.getDisplayType()) {
                    case "person":
                    case "corporate":
                    case "picklist": //NOSONAR
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
        incompleteUserData = false;
        User user = Helper.getCurrentUser();
        if (user != null) {
            Institution inst = user.getInstitution();
            XMLConfiguration conf = ConfigPlugins.getPluginConfig(CONFIGURATION_NAME);
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
            contactData.setDocumentType("contact"); //NOSONAR
            contactData.setLabel("Contact");

            contact2Data = new FieldGrouping();
            contact2Data.setDocumentType("contact2"); //NOSONAR
            contact2Data.setLabel("Contact");

            for (HierarchicalConfiguration hc : fields) {

                String currentType = hc.getString("@type");

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

                if ("dropdown".equals(fieldType) || "combo".equals(fieldType)) {
                    List<HierarchicalConfiguration> valueList = hc.configurationsAt("/value");
                    for (HierarchicalConfiguration v : valueList) {
                        SelectItem si = new SelectItem(v.getString("."), v.getString("."));
                        mf.getSelectList().add(si);
                    }
                }
                if ("institution".equals(currentType) && name.startsWith("contact2")) {
                    contact2Data.getFields().add(mf);
                } else if ("institution".equals(currentType) && name.startsWith("contact")) {
                    contactData.getFields().add(mf);
                } else if ("institution".equals(currentType)) {
                    institutionData.getFields().add(mf);
                } else {
                    userData.getFields().add(mf);
                }

                String val = inst.getAdditionalData().get(name);
                if (!"false".equals(val) && "combo".equals(mf.getDisplayType())) {
                    mf.setBooleanValue(true);
                    if (!"true".equals(val)) {
                        mf.setValue2(val);
                    }
                } else {
                    mf.setValue(val);
                }
                if (required && StringUtils.isBlank(val) && !name.startsWith("contact2")) {
                    incompleteUserData = true;
                }

            }
            displaySecondContact = false;
            for (MetadataField mf : contact2Data.getFields()) {
                if (StringUtils.isNotBlank(mf.getValue())) {
                    displaySecondContact = true;
                    break;
                }
            }
        }
    }

    private void getUserData(User user) {
        MetadataField loginField = new MetadataField();
        loginField.setLabel(Helper.getTranslation("login_new_account_accountName"));
        loginField.setDisplayType("output");//NOSONAR
        loginField.setRequired(false);
        loginField.setCardinality("1");
        loginField.setAdditionalType("login");
        loginField.setValue(user.getLogin());
        userData.getFields().add(loginField);

        MetadataField emailAddress = new MetadataField();
        emailAddress.setLabel(Helper.getTranslation("login_new_account_emailAddress"));
        emailAddress.setDisplayType("output");
        emailAddress.setRequired(false);
        emailAddress.setCardinality("1");
        emailAddress.setAdditionalType("email");
        emailAddress.setValue(user.getEmail());
        userData.getFields().add(emailAddress);

        MetadataField firstname = new MetadataField();
        firstname.setLabel(Helper.getTranslation("firstname"));//NOSONAR
        firstname.setDisplayType("input");
        firstname.setRequired(true);
        firstname.setCardinality("1");
        firstname.setAdditionalType("firstname");
        firstname.setValue(user.getVorname());
        userData.getFields().add(firstname);

        MetadataField lastname = new MetadataField();
        lastname.setLabel(Helper.getTranslation("lastname"));//NOSONAR
        lastname.setDisplayType("input");
        lastname.setRequired(true);
        lastname.setCardinality("1");
        lastname.setAdditionalType("lastname");
        lastname.setValue(user.getNachname());
        userData.getFields().add(lastname);
    }

    private void getInstitutionData(Institution inst) {
        MetadataField longName = new MetadataField();
        longName.setLabel(Helper.getTranslation("institution_longName"));
        longName.setDisplayType("output");
        longName.setRequired(false);
        longName.setCardinality("1");
        longName.setAdditionalType("longName");
        longName.setValue(inst.getLongName());
        institutionData.getFields().add(longName);
    }

    // update user object, save it
    public void saveUserData() {
        oldPasswordValid = true;
        newPasswordValid = true;
        passwordValidationError = "";
        User user = Helper.getCurrentUser();
        // check if password fields are filled
        if (StringUtils.isNotBlank(newPassword) || StringUtils.isNotBlank(newPasswordRepeated)) {
            navigation = "user";

            // old pw is blank or old pw is wrong
            if (StringUtils.isBlank(oldPassword) || !user.istPasswortKorrekt(oldPassword)) {
                oldPasswordValid = false;
                return;
            }

            // new pw field is blank or fields don't match
            if (StringUtils.isBlank(newPassword) || StringUtils.isBlank(newPasswordRepeated) || !newPassword.equals(newPasswordRepeated)) {
                passwordValidationError = Helper.getTranslation("neuesPasswortNichtGleich");
                newPasswordValid = false;
                return;
            }

            // new pw doesn't fulfill requirements
            int minimumLength = ConfigurationHelper.getInstance().getMinimumPasswordLength();
            if (newPassword.length() < minimumLength) {
                passwordValidationError = Helper.getTranslation("neuesPasswortNichtLangGenug", "" + minimumLength);
                newPasswordValid = false;
                return;
            }
            // 6.) save new pw
            if (AuthenticationType.LDAP.equals(user.getLdapGruppe().getAuthenticationTypeEnum()) && !user.getLdapGruppe().isReadonly()) {

                LdapAuthentication myLdap = new LdapAuthentication();
                try {
                    myLdap.changeUserPassword(user, oldPassword, newPassword);
                } catch (NoSuchAlgorithmException e) {
                    log.error(e);
                }
            }
            UserBean.saltAndSaveUserPassword(user, newPassword);
        }

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
        navigation = "userdata";
        readUserConfiguration();
    }

    public void saveInstitutionData() {
        User user = Helper.getCurrentUser();
        Institution institution = user.getInstitution();
        boolean fieldsValid = true;
        for (MetadataField mf : institutionData.getFields()) {
            if (!mf.validateValue()) {
                fieldsValid = false;
            }
            if (StringUtils.isNotBlank(mf.getAdditionalType())) {
                if ("shortName".equals(mf.getAdditionalType())) {
                    institution.setShortName(mf.getValue());
                } else if ("longName".equals(mf.getAdditionalType())) {
                    institution.setLongName(mf.getValue());
                }
            } else if ("combo".equals(mf.getDisplayType()) && mf.getBooleanValue()) {
                institution.getAdditionalData().put(mf.getRulesetName(), mf.getValue2());
            } else {
                institution.getAdditionalData().put(mf.getRulesetName(), mf.getValue());
            }
        }
        if (fieldsValid) {

            InstitutionManager.saveInstitution(institution);
        } else {
            navigation = "institution";
        }

    }

    public void saveContactData() {
        User user = Helper.getCurrentUser();
        Institution institution = user.getInstitution();
        for (MetadataField mf : contactData.getFields()) {
            institution.getAdditionalData().put(mf.getRulesetName(), mf.getValue());
        }
        for (MetadataField mf : contact2Data.getFields()) {
            institution.getAdditionalData().put(mf.getRulesetName(), mf.getValue());
        }
        InstitutionManager.saveInstitution(institution);
    }

    public void downloadFile() {
        if (StringUtils.isBlank(downloadUrl)) {
            return;
        }
        User user = Helper.getCurrentUser();
        Institution institution = user.getInstitution();

        // some test files:
        // https://mariadb.org/wp-content/uploads/2022/08/MariaDBServerKnowledgeBase.pdf
        // https://filesamples.com/samples/ebook/epub/Around%20the%20World%20in%2028%20Languages.epub
        // https://filesamples.com/samples/ebook/epub/sample1.epub

        String fileName = null;
        try {

            CloseableHttpClient httpclient = HttpClientBuilder.create().build();

            HttpGet method = new HttpGet(downloadUrl);

            CloseableHttpResponse resp = httpclient.execute(method);

            String extension = null;

            // try to get filename from last url part
            String possibleFileName = downloadUrl.substring(downloadUrl.lastIndexOf("/"));
            if (possibleFileName.contains("?")) {
                // remove parameter
                possibleFileName = possibleFileName.substring(0, possibleFileName.indexOf("?"));
            }
            if (possibleFileName.contains(".")) {
                extension = possibleFileName.substring(possibleFileName.lastIndexOf(".") + 1);
                fileName = possibleFileName;
            }

            // get Content-Type, check if application/pdf
            if (StringUtils.isBlank(extension)) {
                Header contentTypeHeader = resp.getEntity().getContentType();
                if (contentTypeHeader != null) {
                    if ("application/pdf".equalsIgnoreCase(contentTypeHeader.getValue())) {
                        extension = "pdf";
                    } else if ("application/epub+zip".equalsIgnoreCase(contentTypeHeader.getValue())) {
                        extension = "epub";
                    }
                }
            }
            if (extension == null) {
                Helper.setFehlerMeldung("plugin_dashboard_delivery_error_urlDownloadNotPossible");
                return;
            }

            // check if filename was written as header param (field is missing in most cases)
            if (fileName == null) {
                Header[] hdrs = resp.getHeaders("Content-Disposition");
                for (Header h : hdrs) {
                    if (h.getValue().startsWith("filename")) {
                        fileName = h.getValue().substring(9);
                    }
                }
            }
            // use counter, if filename cannot be detected
            if (fileName == null) {
                fileName = (files.size() + 1) + "." + extension;
            }

            Path destination = Paths.get(temporaryFolder.toString(), fileName);
            try (OutputStream out = Files.newOutputStream(destination)) {
                InputStream istr = resp.getEntity().getContent();
                // Transfer bytes from in to out
                byte[] buf = new byte[1024];
                int len;
                while ((len = istr.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
            Report report = FileValidator.validateFile(destination, institution.getShortName());

            if (!report.isReachedTargetLevel()) {

                Helper.setFehlerMeldung(Helper.getTranslation(report.getErrorMessage()));

                // delete validation files
                Path testFolder = Paths.get(destination.toString().substring(0, destination.toString().lastIndexOf(".") + 1));
                StorageProvider.getInstance().deleteDir(testFolder);

                // delete file
                StorageProvider.getInstance().deleteFile(destination);

                return;
            }
            files.add(destination);
            Helper.setMeldung("plugin_dashboard_delivery_info_uploadSuccessful");
            downloadUrl = "";
        } catch (IOException e) {
            log.error(e);
        }

    }

    public void save() {
        if (file == null) {
            // no file selected, abort
            return;
        }

        String fileName = file.getSubmittedFileName();

        // check filename, normalize name, no white space, slash, backshlash, check if it has a file extension
        fileName = fileName.replaceAll("\\s", "_").replace("/", "").replace("\\", "");

        User user = Helper.getCurrentUser();
        Institution institution = user.getInstitution();

        try (InputStream in = file.getInputStream()) {
            Path destination = Paths.get(temporaryFolder.toString(), fileName);
            Files.copy(in, destination);

            Report report = FileValidator.validateFile(destination, institution.getShortName());

            if (!report.isReachedTargetLevel()) {

                Helper.setFehlerMeldung(Helper.getTranslation(report.getErrorMessage()));

                // delete validation files
                Path testFolder = Paths.get(destination.toString().substring(0, destination.toString().lastIndexOf(".")));
                StorageProvider.getInstance().deleteDir(testFolder);

                // delete file
                StorageProvider.getInstance().deleteFile(destination);

                return;
            }
            files.add(destination);
            Helper.setMeldung("plugin_dashboard_delivery_info_uploadSuccessful");
        } catch (IOException e) {
            log.error(e);
        }
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
            case "upload"://NOSONAR
            case "newIssue"://NOSONAR
                navigation = "main";
                documentType = "";
                break;

            case "user":
            case "institution":
            case "contact":
                navigation = "userdata";
                break;
            case "issueupload":
            case "newTitle":
                navigation = "titleSelection";
                break;
            case "data1"://NOSONAR
                navigation = "upload";
                break;
            case "data2"://NOSONAR
                navigation = "data1";
                break;
            case "data3"://NOSONAR
                navigation = "data2";
                break;
            case "overview"://NOSONAR
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
                if (files.isEmpty()) {
                    Helper.setFehlerMeldung(Helper.getTranslation("plugin_dashboard_delivery_noFileUploaded"));
                    return;
                }
                navigation = "data1";
                break;

            case "newIssue":
                if (!validateCurrentField()) {
                    return;
                }
                navigation = "issueupload";
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
            case "finish":
                navigation = "main";
                break;
            default:
                break;
        }
    }

    private boolean validateCurrentField() {
        boolean valid = true;
        for (FieldGrouping fg : configuredGroups) {
            if (documentType.equals(fg.getDocumentType()) && navigation.equals(fg.getPageName()) && !fg.isDisabled()) {
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
        String processTitle = institutionName + "_" + identifier.substring(identifier.lastIndexOf("-") + 1);

        // create fileformat, import entered metadata
        Fileformat fileformat = createFileformat(prefs, identifier);

        // save metadata and create goobi process
        Process process = new BeanHelper().createAndSaveNewProcess(template, processTitle.replaceAll("^\\w-", "").toLowerCase(), fileformat);

        //  after creation, move uploaded files into process source folder
        try {
            DocStructType pageType = prefs.getDocStrctTypeByName("page");
            MetadataType physType = prefs.getMetadataTypeByName("physPageNumber");
            MetadataType logType = prefs.getMetadataTypeByName("logicalPageNumber");
            DigitalDocument dd = fileformat.getDigitalDocument();
            DocStruct physical = dd.getPhysicalDocStruct();
            DocStruct logical = dd.getLogicalDocStruct();
            String imageFolder = process.getImagesTifDirectory(false);
            Path destinationFolder = Paths.get(imageFolder);
            if (!StorageProvider.getInstance().isFileExists(destinationFolder)) {
                StorageProvider.getInstance().createDirectories(destinationFolder);
            }
            int order = 1;

            long totalFileSize = 0l;
            String fileFormat = "";
            for (Path uploadedFile : files) {

                if (uploadedFile.getFileName().toString().toLowerCase().endsWith(".pdf")) {
                    fileFormat = "PDF";
                } else {
                    fileFormat = "EPUB";
                }
                totalFileSize += Files.size(uploadedFile);
                StorageProvider.getInstance().move(uploadedFile, Paths.get(destinationFolder.toString(), uploadedFile.getFileName().toString()));

                // delete validation files or import them?
                Path testFolder = Paths.get(uploadedFile.toString().substring(0, uploadedFile.toString().lastIndexOf(".")));
                if (StorageProvider.getInstance().isDirectory(testFolder)) {
                    StorageProvider.getInstance().deleteDir(testFolder);
                }

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
            if (StringUtils.isNotBlank(fileFormat)) {
                Metadata format = new Metadata(prefs.getMetadataTypeByName("FileFormat"));
                format.setValue(fileFormat);
                logical.addMetadata(format);
            }
            if (totalFileSize > 0) {
                Metadata fileSize = new Metadata(prefs.getMetadataTypeByName("FileSize"));
                fileSize.setValue(humanReadableByteCountSI(totalFileSize));
                logical.addMetadata(fileSize);
            }
        } catch (IOException | SwapException | UGHException e) {
            log.error(e);
        }

        createProperties(process, acccountName, institutionName);

        Step step = process.getAktuellerSchritt();
        if (step != null && step.isTypAutomatisch()) {
            new ScriptThreadWithoutHibernate(step).startOrPutToQueue();
        }
        navigation = "finish";
    }

    private Fileformat createFileformat(Prefs prefs, String identifier) {

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
            try {//NOSONAR
                md = new Metadata(prefs.getMetadataTypeByName("CatalogIDDigital_Delivery"));
                md.setValue(identifier);
            } catch (MetadataTypeNotAllowedException e) {
                log.error(e);
            }

            Metadata genre = new Metadata(prefs.getMetadataTypeByName("ModsGenre"));
            if ("monograph".equals(documentType)) {
                docstruct = dd.createDocStruct(prefs.getDocStrctTypeByName(monographicDocType));
                dd.setLogicalDocStruct(docstruct);
                docstruct.addMetadata(md);

                genre.setValue("Buch");
                docstruct.addMetadata(genre);

            } else if ("journal".equals(documentType) && "newTitle".equals(navigation)) {
                docstruct = dd.createDocStruct(prefs.getDocStrctTypeByName(zdbTitleDocType));
                dd.setLogicalDocStruct(docstruct);
                docstruct.addMetadata(md);

                genre.setValue("Zeitschrift");
                docstruct.addMetadata(genre);

            } else if ("issue".equals(documentType) && "issueupload".equals(navigation)) {
                anchor = dd.createDocStruct(prefs.getDocStrctTypeByName(journalDocType));
                docstruct = dd.createDocStruct(prefs.getDocStrctTypeByName(issueDocType));
                dd.setLogicalDocStruct(anchor);
                anchor.addChild(docstruct);
                docstruct.addMetadata(md);

                genre.setValue("Ausgabe");
                docstruct.addMetadata(genre);

                String anchorId = "";
                for (FieldGrouping fg : configuredGroups) {
                    if ("issue".equals(fg.getDocumentType())) {
                        for (MetadataField mf : fg.getFields()) {
                            if ("journaltitles".equals(mf.getDisplayType())) {
                                String processid = mf.getValue();
                                // load metadata
                                Process otherProc = ProcessManager.getProcessById(Integer.parseInt(processid));
                                anchorId = writeAnchorData(anchor, otherProc);

                            }
                        }
                    }
                }
                if (isMasterIssue(anchorId)) {
                    // write anchor master metadata for the first issue
                    try {
                        Metadata masterRecord = new Metadata(prefs.getMetadataTypeByName("InternalNote"));
                        masterRecord.setValue("AnchorMaster");
                        docstruct.addMetadata(masterRecord);
                    } catch (Exception e) {
                        log.error(e);
                    }
                }

            }

            for (FieldGrouping fg : configuredGroups) {
                if (fg.getDocumentType().equals(documentType) && !fg.isDisabled()) {
                    importMetadata(prefs, docstruct, fg);
                }
            }

            for (MetadataField f : additionalMetadata) {
                Metadata meta = new Metadata(prefs.getMetadataTypeByName(f.getRulesetName()));
                meta.setValue(f.getValue());
                docstruct.addMetadata(meta);
            }

            // generate sorting number
            if (docstruct.getType().getName().equals(issueDocType)) {
                String publicationYear = "";
                String year = "";
                String volume = "";

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
                } else {
                    // create metadata CurrentNo
                    Metadata currentNo = new Metadata(prefs.getMetadataTypeByName("CurrentNo"));
                    currentNo.setValue(publicationYear);
                    docstruct.addMetadata(currentNo);
                }
                if (StringUtils.isNotBlank(volume)) {
                    if (volume.length() > 1) {
                        order += volume;
                    } else {
                        order += "0" + volume;
                    }
                }

                while (order.length() < 9) {
                    order += "0"; //NOSONAR
                }
                Metadata sorting = new Metadata(prefs.getMetadataTypeByName("CurrentNoSorting"));
                sorting.setValue(order);
                docstruct.addMetadata(sorting);
            }
            Metadata physicalDescriptionExtent = new Metadata(prefs.getMetadataTypeByName("physicalDescriptionExtent"));
            physicalDescriptionExtent.setValue("Online-Ressource");
            docstruct.addMetadata(physicalDescriptionExtent);

        } catch (UGHException e) {
            log.error(e);
        }
        return fileformat;
    }

    private boolean isMasterIssue(String anchorId) {
        if (StringUtils.isBlank(anchorId)) {
            return true;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("select processid from metadata where metadata.processid in ( ");
        sb.append("select processid from metadata where name = 'DocStruct' and value = ? ) ");
        sb.append("and name = 'CatalogIDDigital_Delivery' and value = ?");

        Connection connection = null;
        try {
            connection = MySQLHelper.getInstance().getConnection();
            List<Integer> results =
                    new QueryRunner().query(connection, sb.toString(), MySQLHelper.resultSetToIntegerListHandler, journalDocType, anchorId);
            return results.isEmpty();
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
        return false;
    }

    private String writeAnchorData(DocStruct anchor, Process otherProc) throws ReadException, PreferencesException, WriteException {
        String anchorID = "";

        try {
            Fileformat other = otherProc.readMetadataFile();
            // copy metadata to anchor
            DocStruct templateDocstruct = other.getDigitalDocument().getLogicalDocStruct();
            if (templateDocstruct.getAllMetadata() != null) {
                for (Metadata templateMetadata : templateDocstruct.getAllMetadata()) {
                    copyMetadata(anchor, templateMetadata);
                    if (templateMetadata.getType().isIdentifier()) {
                        anchorID = templateMetadata.getValue();
                    }
                }
            }
            if (templateDocstruct.getAllPersons() != null) {
                for (Person templatePerson : templateDocstruct.getAllPersons()) {
                    copyPerson(anchor, templatePerson);
                }
            }
            if (templateDocstruct.getAllCorporates() != null) {
                for (Corporate templateCorporate : templateDocstruct.getAllCorporates()) {
                    copyCorporate(anchor, templateCorporate);
                }
            }

        } catch (IOException | SwapException e) {
            log.error(e);
        }
        return anchorID;
    }

    private void copyMetadata(DocStruct anchor, Metadata templateMetadata) {
        try {
            Metadata clone = new Metadata(templateMetadata.getType());
            clone.setValue(templateMetadata.getValue());
            anchor.addMetadata(clone);
        } catch (Exception e) {
            log.trace(e);
        }
    }

    private void copyCorporate(DocStruct anchor, Corporate templateCorporate) {
        try {
            Corporate clone = new Corporate(templateCorporate.getType());
            clone.setMainName(templateCorporate.getMainName());
            anchor.addCorporate(clone);
        } catch (Exception e) {
            log.trace(e);
        }
    }

    private void copyPerson(DocStruct anchor, Person templatePerson) {
        try {
            Person clone = new Person(templatePerson.getType());
            clone.setFirstname(templatePerson.getFirstname());
            clone.setLastname(templatePerson.getLastname());
            anchor.addPerson(clone);
        } catch (Exception e) {
            log.trace(e);
        }
    }

    private void importMetadata(Prefs prefs, DocStruct docstruct, FieldGrouping fg) {

        for (MetadataField mf : fg.getFields()) {
            if (StringUtils.isNotBlank(mf.getValue()) && StringUtils.isNotBlank(mf.getRulesetName())) {
                switch (mf.getDisplayType()) {
                    case "person":
                        try {
                            String roleTerm = mf.getRole();
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
                            String roleTerm = mf.getRole();
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
                    currentField.setDuplicate(true);
                    currentField.setCardinality("1");
                    fg.getFields().add(position + 1, clone);
                    break;
                }
            }
        }
    }

    public void removeMetadataField() {
        for (FieldGrouping fg : configuredGroups) {
            if (fg.getDocumentType().equals(documentType)) {
                for (MetadataField mf : fg.getFields()) {
                    if (mf == currentField) {
                        fg.getFields().remove(mf);
                        return;
                    }
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
        String processTitle = "zdb" + "_" + institutionName + "_" + identifier.substring(identifier.lastIndexOf("-") + 1);

        // create fileformat, import entered metadata
        Fileformat fileformat = createFileformat(prefs, identifier);

        // save metadata and create goobi process
        Process process = new BeanHelper().createAndSaveNewProcess(template, processTitle.replaceAll("^\\w-", "").toLowerCase(), fileformat);

        createProperties(process, acccountName, institutionName);

        if (StringUtils.isNotBlank(registrationMailRecipient) && StringUtils.isNotBlank(registrationMailBody)) {
            String subject = Helper.getTranslation(registrationMailSubject);
            String body = Helper.getTranslation(registrationMailBody);

            StringBuilder sb = new StringBuilder();
            sb.append(NEWLINE);
            sb.append(NEWLINE);

            try {
                for (Metadata md : fileformat.getDigitalDocument().getLogicalDocStruct().getAllMetadata()) {

                    sb.append(md.getType().getLanguage(Helper.getMetadataLanguage()));
                    sb.append(COLON);
                    sb.append(md.getValue());
                    sb.append(NEWLINE);

                }
            } catch (PreferencesException e) {
                log.error(e);
            }

            SendMail.getInstance().sendMailToUser(subject, body + NEWLINE + sb.toString(), registrationMailRecipient);
        }
        navigation = "finish";
    }

    public void createJournalIssue() {
        if (files.isEmpty()) {
            Helper.setFehlerMeldung(Helper.getTranslation("plugin_dashboard_delivery_noFileUploaded"));
            return;
        }

        if (!validateCurrentField()) {
            return;
        }

        createProcess(journalTemplateName);

        navigation = "finish";
    }

    public boolean isHasJournals() {
        return !availableTitles.isEmpty();

    }

    private List<SelectItem> generateListOfJournalTitles() {

        availableTitles = new ArrayList<>();
        // propulate a pickup list for issue creation
        // - search in journal processes only (special project, process title starts with a specific term?)
        // - was created by this institution (or user?)
        // - has no issue yet OR has a zdb id (metadata is filled)
        // - approved by zlb (has reached a certain step) ?
        User user = Helper.getCurrentUser();
        if (user != null) {
            String institutionName = user.getInstitutionName();

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
            sql.append(") and metadata.name=\"CatalogIDDigital_Delivery\" group by metadata.value having count(metadata.value)=1");
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
                for (Integer processid : results.keySet()) { //NOSONAR
                    SelectItem item = new SelectItem(processid, results.get(processid).get("TitleDocMain"));
                    availableTitles.add(item);
                }
            }

        }

        return availableTitles;
    }

    public static final ResultSetHandler<Map<Integer, Map<String, String>>> resultSetToMapHandler =
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
                rs.close();
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
            default:
                // nothing
        }
        return value;
    }

    private static String humanReadableByteCountSI(long bytes) {
        if (-1000 < bytes && bytes < 1000) {
            return bytes + " B";
        }
        CharacterIterator ci = new StringCharacterIterator("kMGTPE");
        while (bytes <= -999_950 || bytes >= 999_950) {
            bytes /= 1000;
            ci.next();
        }
        return String.format("%.1f %cB", bytes / 1000.0, ci.current());
    }

    public void disableContact() {
        // delete content from second contract page
        for (MetadataField ucf : contact2Data.getFields()) {
            ucf.setValue("");
        }
        displaySecondContact = false;
    }

    public void createNewContact() {
        // show fields for second contract in ui
        displaySecondContact = true;
    }

    public void backToMain() {
        navigation = "main";
    }

}
