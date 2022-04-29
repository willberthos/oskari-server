package org.oskari.capabilities.ogc.wfs;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import fi.nls.oskari.util.WFSConversionHelper;

import java.util.HashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class FeaturePropertyType {
    public String name;
    public String type;
    public Map<String, String> restrictions = new HashMap<>();

    @JsonIgnore
    public boolean isGeometry() {
        return WFSConversionHelper.isGeometryType(type);
    }
}