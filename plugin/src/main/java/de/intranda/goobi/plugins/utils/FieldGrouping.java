package de.intranda.goobi.plugins.utils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class FieldGrouping implements Serializable {

    private static final long serialVersionUID = -1167645687386330344L;

    private String label; // display name in UI

    private String pageName; // defines on which page the item gets displayed
    private String documentType; // for monographs or journals

    private boolean disableGroup;
    private String disableLabel;

    private boolean disabled;

    // actual metadata list
    private List<MetadataField> fields = new ArrayList<>();

}
