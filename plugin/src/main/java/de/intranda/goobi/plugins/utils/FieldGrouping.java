package de.intranda.goobi.plugins.utils;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class FieldGrouping {

    private String label; // display name in UI

    private String pageName; // defines on which page the item gets displayed
    private String documentType; // for monographs or journals

    // actual metadata list
    private List<MetadataField> fields = new ArrayList<>();

}
