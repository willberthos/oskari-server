package org.oskari.control.userlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import fi.nls.oskari.annotation.Oskari;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.json.JSONException;
import org.json.JSONObject;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.Filter;
import org.opengis.filter.FilterFactory;
import org.opengis.filter.expression.Expression;

import fi.nls.oskari.domain.map.OskariLayer;
import fi.nls.oskari.util.PropertyUtil;

import org.oskari.geojson.GeoJSONFeatureCollection;
import org.oskari.service.user.UserLayerService;

@Oskari
public class UserLayerWFSHelper extends UserLayerService {

    public static final String PROP_USERLAYER_BASELAYER_ID = "userlayer.baselayer.id";
    public static final String PREFIX_USERLAYER = "userlayer_";

    protected static final String USERLAYER_ATTR_GEOMETRY = "geometry";
    private static final String USERLAYER_ATTR_USER_LAYER_ID = "user_layer_id";
    private static final String USERLAYER_ATTR_UUID = "uuid";
    private static final String USERLAYER_ATTR_PUBLISHER_NAME = "publisher_name";
    private static final String USERLAYER_ATTR_PROPERTY_JSON = "property_json";

    private FilterFactory ff;
    private int userlayerLayerId;

    public UserLayerWFSHelper() {
        init();
    }

    public void init() {
        this.ff = CommonFactoryFinder.getFilterFactory();
        this.userlayerLayerId = PropertyUtil.getOptional(PROP_USERLAYER_BASELAYER_ID, -2);
    }

    public int getBaselayerId() {
        return userlayerLayerId;
    }

    public boolean isUserlayerLayer(OskariLayer layer) {
        return layer.getId() == userlayerLayerId;
    }

    public boolean isUserContentLayer(String layerId) {
        return layerId.startsWith(PREFIX_USERLAYER);
    }

    public int parseId(String layerId) {
        return Integer.parseInt(layerId.substring(PREFIX_USERLAYER.length()));
    }

    public Filter getWFSFilter(String layerId, String uuid, ReferencedEnvelope bbox) {
        int userlayerId = parseId(layerId);
        Expression _userlayerId = ff.property(USERLAYER_ATTR_USER_LAYER_ID);
        Expression _uuid = ff.property(USERLAYER_ATTR_UUID);

        Filter userlayerIdEquals = ff.equals(_userlayerId, ff.literal(userlayerId));

        Filter uuidEquals = ff.equals(_uuid, ff.literal(uuid));
/*
// FIXME: Referencing publisher name requires the layer is oskari:user_layer_data_style instead of oskari:vuser_layer_data
// which brings more attributes that we want AND breaks transport
// TODO: We might need to check if the use has right to view the layer that is not his/her own in another way
// Leaving this logic out means that guests won't see the published user content layer
        Expression _publisherName = ff.property(USERLAYER_ATTR_PUBLISHER_NAME);
        Filter publisherNameNotNull = ff.not(ff.isNull(_publisherName));
        Filter publisherNameNotEmpty = ff.notEqual(_publisherName, ff.literal(""));
        Filter publisherNameNotNullNotEmpty = ff.and(publisherNameNotNull, publisherNameNotEmpty);

        Filter uuidEqualsOrPublished = ff.or(uuidEquals, publisherNameNotNullNotEmpty);
 */

        Filter uuidEqualsOrPublished = uuidEquals;

        Filter bboxFilter = ff.bbox(USERLAYER_ATTR_GEOMETRY,
                bbox.getMinX(), bbox.getMinY(),
                bbox.getMaxX(), bbox.getMaxY(),
                CRS.toSRS(bbox.getCoordinateReferenceSystem()));

        return ff.and(Arrays.asList(userlayerIdEquals, uuidEqualsOrPublished, bboxFilter));
    }

    @SuppressWarnings("unchecked")
    public SimpleFeatureCollection postProcess(SimpleFeatureCollection sfc) throws Exception {
        List<SimpleFeature> fc = new ArrayList<>();
        SimpleFeatureType schema;

        String geomAttrName = sfc.getSchema().getGeometryDescriptor().getLocalName();

        try (SimpleFeatureIterator it = sfc.features()) {
            if (!it.hasNext()) {
                return sfc;
            }
            SimpleFeature f = it.next();

            String property_json = (String) f.getAttribute(USERLAYER_ATTR_PROPERTY_JSON);
            JSONObject properties = new JSONObject(property_json);

            schema = createType(sfc.getSchema(), properties);
            SimpleFeatureBuilder builder = new SimpleFeatureBuilder(schema);

            builder.set(geomAttrName, f.getDefaultGeometry());
            Iterator<String> keys = properties.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object obj = properties.get(key);
                builder.set(key, obj);
            }
            fc.add(builder.buildFeature(f.getID()));

            while (it.hasNext()) {
                f = it.next();
                builder.set(geomAttrName, f.getDefaultGeometry());
                property_json = (String) f.getAttribute(USERLAYER_ATTR_PROPERTY_JSON);
                properties = new JSONObject(property_json);
                keys = properties.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object obj = properties.get(key);
                    builder.set(key, obj);
                }
                fc.add(builder.buildFeature(f.getID()));
            }
        }

        return new GeoJSONFeatureCollection(fc, schema);
    }

    private SimpleFeatureType createType(SimpleFeatureType schema, JSONObject properties) throws JSONException {
        SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
        typeBuilder.setName(schema.getName());
        typeBuilder.add(schema.getGeometryDescriptor());
        typeBuilder.setDefaultGeometry(schema.getGeometryDescriptor().getLocalName());
        @SuppressWarnings("unchecked")
        Iterator<String> keys = properties.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object obj = properties.get(key);
            typeBuilder.add(key, obj.getClass());
        }
        return typeBuilder.buildFeatureType();
    }

}