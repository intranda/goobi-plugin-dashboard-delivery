package de.intranda.goobi.plugins.utils;

import java.util.ArrayList;
import java.util.List;

import javax.faces.model.SelectItem;

import org.apache.commons.lang.StringUtils;

import lombok.Data;

@Data
public class MetadataField {

    private String rulesetName; // ruleset name
    private String label; // display name in UI
    private String displayType; // input, textarea, dropdown, person, corporate

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

    private boolean validationError; // true if validation fails

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

        validationError = false;
        if (required && StringUtils.isBlank(val)) {
            validationError = true;
            return false;
        }

        if (StringUtils.isNotBlank(validationExpression) && StringUtils.isNotBlank(val)) {
            if (!val.matches(validationExpression)) {
                validationError = true;
                return false;
            }

        }
        return true;
    }

}
