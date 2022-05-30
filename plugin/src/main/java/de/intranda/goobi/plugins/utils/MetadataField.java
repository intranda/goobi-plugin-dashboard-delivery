package de.intranda.goobi.plugins.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.faces.model.SelectItem;

import org.apache.commons.lang.StringUtils;
import org.goobi.vocabulary.Field;
import org.goobi.vocabulary.VocabRecord;
import org.goobi.vocabulary.Vocabulary;

import de.intranda.goobi.plugins.DeliveryDashboardPlugin;
import de.sub.goobi.persistence.managers.VocabularyManager;
import lombok.Data;
import lombok.Getter;

@Data
public class MetadataField {

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

    @Getter
    private String vocabularyUrl;

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
        if (StringUtils.isNotBlank(value2)) {
            val = value2;
        }

        if (required && StringUtils.isBlank(val)) {
            return false;
        }

        if (StringUtils.isNotBlank(validationExpression) && StringUtils.isNotBlank(val)) {
            if (!val.matches(validationExpression)) {
                return false;
            }

        }
        return true;
    }

    public void setVocabulary(String name, String displayField, String importFied) {
        vocabularyName = name;
        vocabularyDisplayField = displayField;
        vocabularyImportField = importFied;

        Vocabulary currentVocabulary = VocabularyManager.getVocabularyByTitle(vocabularyName);
        vocabularyUrl = DeliveryDashboardPlugin.vocabularyUrl + currentVocabulary.getId();
        if (currentVocabulary != null) {
            VocabularyManager.getAllRecords(currentVocabulary);
            vocabList = currentVocabulary.getRecords();
            Collections.sort(vocabList);
            selectList = new ArrayList<>(vocabList.size());
            if (currentVocabulary != null && currentVocabulary.getId() != null) {
                for (VocabRecord vr : vocabList) {
                    for (Field f : vr.getFields()) {
                        if (StringUtils.isBlank(vocabularyDisplayField) && f.getDefinition().isMainEntry()) {
                            selectList.add(new SelectItem(String.valueOf(vr.getId()), f.getValue()));
                            break;
                        } else if (f.getDefinition().getLabel().equals(vocabularyDisplayField)) {
                            selectList.add(new SelectItem(String.valueOf(vr.getId()), f.getValue()));
                            break;
                        }
                    }
                }
            }
        }
    }

    public boolean isValid() {
        if (value == null || StringUtils.isBlank(value)) {
            if (required) {
                if (StringUtils.isBlank(validationErrorText)) {
                    validationErrorText = "Field is required";
                }
                return false;
            }
        } else if (StringUtils.isNotBlank(validationExpression)) {
            if (!value.matches(validationExpression)) {
                return false;
            }
        }
        return true;
    }
}
