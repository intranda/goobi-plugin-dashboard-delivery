package de.intranda.goobi.plugins.utils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.faces.component.UIComponent;
import javax.faces.context.FacesContext;
import javax.faces.model.SelectItem;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.validator.routines.checkdigit.EAN13CheckDigit;
import org.goobi.vocabulary.Field;
import org.goobi.vocabulary.VocabRecord;
import org.goobi.vocabulary.Vocabulary;

import de.intranda.goobi.plugins.DeliveryDashboardPlugin;
import de.sub.goobi.persistence.managers.VocabularyManager;
import lombok.Data;

@Data
public class MetadataField implements Serializable {

    private static final long serialVersionUID = -2601314844043091136L;
    private String rulesetName; // ruleset name
    private String label; // display name in UI
    private String displayType; // input, textarea, dropdown, person, corporate, picklist

    private String metadataLevel; // anchor or volume, only relevant for periodical data

    private String cardinality; // 1 = exact one, ? = zero or one, * = any, + = at least one
    private boolean required;

    private String marcMainTag; // only used if field gets imported from opac
    private String marcSubTag; // only used if field gets imported from opac

    private String validationExpression; // regex to validate the results
    private String validationErrorText; // display, when value is invalid

    private String helpMessage; // display when help is activated

    private String value; // actual value
    private String value2; // actual value, used for persons
    private String role; // actual role, used for persons and corporations

    private List<SelectItem> selectList = new ArrayList<>(); // list of selectable values

    private List<VocabRecord> vocabList = new ArrayList<>(); // list of selectable values

    private String vocabularyName;
    private String vocabularyDisplayField;
    private String vocabularyImportField;

    private String vocabularyUrl;

    private String additionalType; // is not shown in UI, is used to store field name from user or institution objects

    private boolean fieldValid = true;

    public void setBooleanValue(boolean val) {
        if (val) {
            value = "true";
        } else {
            value = "false";
        }
    }

    public boolean getBooleanValue() {
        return StringUtils.isNotBlank(value) && "true".equals(value);
    }

    public boolean validateValue() {

        String val = value;

        if (displayType.equals("combo") && getBooleanValue() && StringUtils.isBlank(value2)) {
            fieldValid = false;
            return false;
        }

        if (StringUtils.isNotBlank(value2)) {
            val = value2;
        }

        if (required && StringUtils.isBlank(val)) {
            fieldValid = false;
            return false;
        }

        if (StringUtils.isNotBlank(validationExpression) && StringUtils.isNotBlank(val) && !val.matches(validationExpression)) {//NOSONAR
            fieldValid = false;
            return false;
        }
        fieldValid = true;
        return true;
    }

    public void setVocabulary(String name, String displayField, String importFied) {
        vocabularyName = name;
        vocabularyDisplayField = displayField;
        vocabularyImportField = importFied;

        Vocabulary currentVocabulary = VocabularyManager.getVocabularyByTitle(vocabularyName);
        vocabularyUrl = DeliveryDashboardPlugin.vocabularyUrl + currentVocabulary.getId();
        VocabularyManager.getAllRecords(currentVocabulary);
        vocabList = currentVocabulary.getRecords();
        Collections.sort(vocabList);
        selectList = new ArrayList<>(vocabList.size());
        if (currentVocabulary.getId() != null) {
            for (VocabRecord vr : vocabList) {
                for (Field f : vr.getFields()) {
                    if (StringUtils.isBlank(vocabularyDisplayField) && f.getDefinition().isMainEntry()) {
                        selectList.add(new SelectItem(String.valueOf(vr.getId()), f.getValue()));
                        break;
                    } else if (f.getDefinition().getLabel().equals(vocabularyDisplayField)) {
                        selectList.add(new SelectItem(String.valueOf(vr.getId()), f.getValue()));
                    }
                }
            }
        }
    }

    public void validateField(FacesContext context, UIComponent comp, Object obj) { //NOSONAR
        if (obj instanceof Boolean) {
            return;
        }

        String testValue = (String) obj;
        fieldValid = true;

        if (displayType.equals("combo")) {
            fieldValid = false;
            return;
        }

        // check field type, different validation for different types
        if ("person".equals(displayType) && comp != null) {
            // if required, role and either firstname or lastname must be filled
            if (required && StringUtils.isBlank(role)) {
                fieldValid = false;
            }
            // if firstname or lastname is used, role must be set
            if (comp.getClientId().endsWith("firstname")) {
                if (StringUtils.isBlank(role) && (StringUtils.isNotBlank(testValue) || StringUtils.isNotBlank(value2))) {
                    // current value (firstname) or last name are not blank, role must be selected
                    fieldValid = false;
                }
            } else if (comp.getClientId().endsWith("lastname")) {
                if (StringUtils.isBlank(role) && (StringUtils.isNotBlank(testValue) || StringUtils.isNotBlank(value))) {
                    // current value (lastname) or firstname are not blank, role must be selected
                    fieldValid = false;
                }
            } else if (comp.getClientId().endsWith("select")) {
                if ("null".equals(testValue)) {
                    testValue = null;
                }
                if (StringUtils.isBlank(testValue) && (StringUtils.isNotBlank(value) || StringUtils.isNotBlank(value2))) {
                    // current field is role, its blank, but firstname or lastname are filled
                    fieldValid = false;
                }
            }
        } else if ("corporate".equals(displayType) && comp != null) {
            // corporate name
            if (comp.getClientId().endsWith("input")) {
                // new corporate name is empty, but role is set
                if (StringUtils.isNotBlank(testValue) && StringUtils.isBlank(role)) {
                    fieldValid = false;
                }
                // corporate type
                // if corporate name is set, role cannot be empty
            } else if (comp.getClientId().endsWith("select")) {
                if ("null".equals(testValue)) {
                    testValue = null;
                }
                if (StringUtils.isNotBlank(testValue) && StringUtils.isBlank(value)) {
                    fieldValid = false;
                }
            }
        } else if ("picklist".equals(displayType) && comp != null) {
            if (comp.getClientId().endsWith("select")) {
                if ("null".equals(testValue)) {
                    testValue = null;
                }
                if (StringUtils.isBlank(testValue) && StringUtils.isNotBlank(value)) {
                    fieldValid = false;
                } else if (StringUtils.isNotBlank(testValue) && StringUtils.isBlank(value)) {
                    fieldValid = false;
                } else if (StringUtils.isNotBlank(testValue) && StringUtils.isNotBlank(value)) {
                    // different validation based on selected type
                    validateContent(testValue, value);
                }
            } else if (comp.getClientId().endsWith("input")) {
                if (StringUtils.isBlank(testValue) && (!"null".equals(role) && StringUtils.isNotBlank(role))) {
                    fieldValid = false;
                } else if (StringUtils.isNotBlank(testValue) && ("null".equals(role) || StringUtils.isBlank(role))) {
                    fieldValid = false;
                } else if (StringUtils.isNotBlank(testValue) && (!"null".equals(role) && StringUtils.isNotBlank(role))) {
                    // different validation based on selected type
                    validateContent(role, testValue);
                }
            }
        }

        //  simple field validation
        if (StringUtils.isBlank(testValue)) {
            if (required) {
                fieldValid = false;
            }
        }

        else if (StringUtils.isNotBlank(testValue) && StringUtils.isNotBlank(validationExpression) && !testValue.matches(validationExpression)) {
            fieldValid = false;
        }
    }

    private String validateContent(String role, String value) {
        switch (role) {
            case "ISBN":
                //  zulässige Zeichen: Ziffern, Bindestriche und X
                //  Zeichenbegrenzung: max. 17 Zeichen

                if (value.length() > 17) {
                    // to many characters
                    fieldValid = false;
                }
                // ISBN-13: 978-3-86680-192-9
                // ISBN-10: 3-86640-001-2

                // remove hyphens
                String number = value.replace("-", "");
                // must be numeric or numeric + X
                if (!number.matches("[0-9]{10}|[0-9]{9}X|[0-9]{13}|[0-9]{12}X")) {
                    // invalid characters
                    validationErrorText = "Falsche Anzahl oder ungültige Zeichen, bitte ISBN-10 oder ISBN-13 angeben.";
                    fieldValid = false;
                }
                // check length, should be 10 or 13
                // 10 -> isbn validation
                if (number.length() == 10 && !validateIdentifier(number)) {
                    validationErrorText = "Ungültige ISBN-10 Nummer.";
                    fieldValid = false;

                }
                // 13 -> ean validation
                if (number.length() == 13 && !EAN13CheckDigit.EAN13_CHECK_DIGIT.isValid(number)) {
                    validationErrorText = "Ungültige ISBN-13 Nummer.";
                    fieldValid = false;
                }

                // wrong number of digits
                if (number.length() != 10 && number.length() != 13) {
                    validationErrorText = "Falsche Anzahl Zeichen, bitte ISBN-10 oder ISBN-13 angeben.";
                    fieldValid = false;
                }
                break;

            case "GTIN/EAN":
                // last character is used as checksum. Calculation is done with this algorithm: https://www.gs1.org/services/how-calculate-check-digit-manually
                // some valid codes:
                // GTIN-8: 12345670
                // EAN / GTIN-13: 4012345000009
                // GTIN-14: 94054321000019

                // zulässige Zeichen: Ziffern
                // Zeichenbegrenzung: max. 14 Zeichen, bei geringerer Stelligkeit werden vorangehende Leerstellen mit Nullen aufgefüllt
                if (value.length() > 14) {
                    // to many characters
                    fieldValid = false;
                }
                if (!EAN13CheckDigit.EAN13_CHECK_DIGIT.isValid(value)) {
                    validationErrorText = "Kein gültiger GTIN/EAN Code.";
                    fieldValid = false;
                }

                if (value.length() < 14) {
                    // fill string with leading 000
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 14 - value.length(); i++) {
                        sb.append("0");
                    }
                    sb.append(value);

                    value = sb.toString();
                }
                break;

            case "ISSN":
                // zulässige Zeichen: Ziffern, Bindestriche und X
                // Zeichenbegrenzung: 9 Zeichen
                if (!value.matches("[0-9]{8}|[0-9]{7}X|[0-9]{4}\\-[0-9]{4}|[0-9]{4}\\-[0-9]{3}X")) { //NOSONAR
                    // invalid characters
                    fieldValid = false;
                }

                if (!validateIdentifier(value)) {
                    // invalid characters
                    // valid examples: 0317-8471, 1050-124X
                    validationErrorText = "ISSN ist invalide. Bitte eine gültige ISSN in der Form XXXX-XXXX angeben.";
                    fieldValid = false;
                }
                break;
            default:
                // do nothing
        }
        return value;
    }

    /*
     * Validate, if the given string is a valid identifier
     * 
     * Algorithm:
     * remove optional hyphens
     * Take all but the last digits of the identifier
     * Take the weighting factors associated with each digit: ... 8 7 6 5 4 3 2
     * Multiply each digit in turn by its weighting factor
     * Add these numbers together
     * Divide this sum by the modulus 11
     * Substract the remainder from 11
     * Compare the remainder with the most right position. If the remainder is 10, expect 'X' or 'x'
     * 
     */

    public static boolean validateIdentifier(String value) {
        value = value.replace("-", "");
        int checksum = 0;
        int weight = 2;
        int val;
        int l = value.length() - 1;
        for (int i = l - 1; i >= 0; i--) {
            val = value.charAt(i) - '0';
            checksum += val * weight++;
        }
        int mod = checksum % 11;
        if (mod != 0) {
            mod = 11 - mod;
        }
        return mod == (value.charAt(l) == 'X' || value.charAt(l) == 'x' ? 10 : value.charAt(l) - '0');
    }

    public MetadataField cloneField() {
        MetadataField mf = new MetadataField();
        mf.setCardinality(cardinality);
        mf.setDisplayType(displayType);
        mf.setHelpMessage(helpMessage);
        mf.setLabel(label);
        mf.setMarcMainTag(marcMainTag);
        mf.setMarcSubTag(marcSubTag);
        mf.setMetadataLevel(metadataLevel);
        mf.setRequired(false);

        mf.setRulesetName(rulesetName);
        mf.setSelectList(selectList);
        mf.setValidationErrorText(validationErrorText);
        mf.setValidationExpression(validationExpression);
        mf.setVocabList(vocabList);
        mf.setVocabularyDisplayField(vocabularyDisplayField);
        mf.setVocabularyImportField(vocabularyImportField);
        mf.setVocabularyName(vocabularyName);
        mf.setVocabularyUrl(vocabularyUrl);

        return mf;

    }

    public boolean isDisplayHelpButton() {
        return StringUtils.isNotBlank(helpMessage);
    }
}
