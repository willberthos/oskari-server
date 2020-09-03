package org.oskari.helpers;

import fi.nls.oskari.domain.map.view.Bundle;
import fi.nls.oskari.domain.map.view.View;
import fi.nls.oskari.domain.map.view.ViewTypes;
import fi.nls.oskari.log.LogFactory;
import fi.nls.oskari.log.Logger;
import fi.nls.oskari.map.view.ViewService;
import fi.nls.oskari.map.view.AppSetupServiceMybatisImpl;
import fi.nls.oskari.service.ServiceRuntimeException;
import fi.nls.oskari.util.ConversionHelper;
import fi.nls.oskari.util.IOHelper;
import fi.nls.oskari.util.JSONHelper;
import fi.nls.oskari.util.PropertyUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Helpers for flyway scripts. Be very careful when making changes as previous versions of Oskari are using this
 * to migrate database.
 */
public class AppSetupHelper {

    private static final ViewService viewService = new AppSetupServiceMybatisImpl();
    private static Logger log = LogFactory.getLogger(AppSetupHelper.class);

    public static long create(Connection conn, final String viewfile)
            throws IOException, SQLException {
        try {
            JSONObject viewJSON = readViewFile(viewfile);
            final Set<Integer> selectedLayerIds = setupLayers(viewJSON);

            final View view = createView(conn, viewJSON);
            Bundle bundle = view.getBundleByName("mapfull");
            replaceSelectedLayers(bundle, selectedLayerIds);

            final long viewId = viewService.addView(view);
            log.info("Added view from file:", viewfile, "/viewId is:", viewId, "/uuid is:", view.getUuid());
            // update supported SRS for layers after possibly new projection on appsetup/view
            LayerHelper.refreshLayerCapabilities();
            return viewId;
        } catch (Exception ex) {
            log.error(ex, "Unable to insert appsetup! ");
            throw new ServiceRuntimeException("Unable to insert appsetup", ex);
        }
    }

    /**
     * This returns you a list of id of appsetups that you usually want to make changes to
     * in migrations like adding, removing or reconfiguring a bundle.
     *
     * @param connection connection to the database
     * @return ids for appsetups of type DEFAULT and USER
     * @throws SQLException
     */
    public static List<Long> getSetupsForUserAndDefaultType(Connection connection)
            throws SQLException {
        return getSetupsForType(connection, ViewTypes.DEFAULT, ViewTypes.USER);
    }

    /**
     * This returns you a list of id of appsetups that you usually want to make changes to
     * in migrations like adding, removing or reconfiguring a bundle.
     *
     * If you have multiple frontend apps (like geoportal and geoportal-3d) you can specify
     * which application you are interested in.
     *
     * @param connection connection to the database
     * @param applicationName application name to filter by
     * @return ids for appsetups of type DEFAULT and USER
     * @throws SQLException
     */
    public static List<Long> getSetupsForUserAndDefaultType (Connection connection, String applicationName)
            throws SQLException {
        return getSetupsForApplicationByType(connection, applicationName, ViewTypes.DEFAULT, ViewTypes.USER);
    }

    /**
     * Same as getSetupsForUserAndDefaultType(conn) but you can specify the appsetup types
     * instead of using the built-in "usual types for migrations".
     *
     * The most common use for this instead of getSetupsForUserAndDefaultType() is to
     * make changes to embedded/published maps.
     *
     * @param connection connection to the database
     * @param types appsetup types like DEFAULT, USER, PUBLISHED
     * @return ids for appsetups
     * @throws SQLException
     */
    public static List<Long> getSetupsForType(Connection connection, String... types)
            throws SQLException {
        ArrayList<Long> ids = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT id FROM oskari_appsetup");
        if (types != null && types.length > 0) {
            sql.append(" WHERE type IN (?");
            for (int i = 1; i < types.length; ++i) {
                sql.append(", ?");
            }
            sql.append(")");

        }
        try (final PreparedStatement statement =
                     connection.prepareStatement(sql.toString())) {
            if (types != null) {
                for (int i = 0; i < types.length; ++i) {
                    statement.setString(i + 1, types[i]);
                }
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                }
            }
        }
        return ids;
    }

    /**
     * Same as getSetupsForUserAndDefaultType(conn, app) but you can specify the appsetup types
     * instead of using the built-in "usual types for migrations".
     *
     * The most common use for this instead of getSetupsForUserAndDefaultType() is to
     * make changes to embedded/published maps.
     *
     * @param conn connection to the database
     * @param applicationName application name to filter by like embedded-3d, geoportal-3d, geoportal
     * @param types appsetup types like DEFAULT, USER, PUBLISHED
     * @return ids for appsetups
     * @throws SQLException
     */
    public static List<Long> getSetupsForApplicationByType(Connection conn, String applicationName, String... types)
            throws SQLException {
        ArrayList<Long> ids = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT id FROM oskari_appsetup WHERE application=?");
        if (types != null && types.length > 0) {
            sql.append(" AND type IN (?");
            for (int i = 1; i < types.length; ++i) {
                sql.append(", ?");
            }
            sql.append(")");

        }
        try (final PreparedStatement statement =
                     conn.prepareStatement(sql.toString())) {
            statement.setString(1, applicationName);
            if (types != null) {
                for (int i = 0; i < types.length; ++i) {
                    statement.setString(i + 2, types[i]);
                }
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                }
            }
        }
        return ids;
    }

    /**
     * Note! Use with care, usually you don't need this.
     *
     * If you want to reference another appsetup you can create a link to it by using the UUID returned here.
     *
     * Tries to determine what is the default appsetup that should be shown for given application and returns it's UUID
     * for linking purposes.
     *
     * @param conn connection to the database
     * @param applicationName application name to filter by like embedded-3d, geoportal-3d, geoportal
     * @return
     * @throws SQLException
     */
    public static String getUuidForDefaultSetup(Connection conn, String applicationName) throws SQLException {
        Map<Long, String> uuids = new HashMap<>();
        final String sql = "SELECT id, uuid FROM oskari_appsetup WHERE application=? and type=?";
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setString(1, applicationName);
            statement.setString(2, ViewTypes.DEFAULT);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    uuids.put(rs.getLong("id"), rs.getString("uuid"));
                }
            }
        }
        if (uuids.size() == 1) {
            return uuids.values().stream().findFirst().get();
        }
        if (uuids.size() > 1) {
            long defaultId = ConversionHelper.getLong(PropertyUtil.get("view.default"), -1);
            if (uuids.containsKey(defaultId)) {
                return uuids.get(defaultId);
            }
            long appId = ConversionHelper.getLong(PropertyUtil.get("view.default." + applicationName), -1);
            if (uuids.containsKey(appId)) {
                return uuids.get(appId);
            }
            throw new SQLException ("Couldn't find unique default view. Define default view id in properties view.default or view.default.{application}");
        }
        throw new SQLException ("Couldn't find default view");
    }

    /**
     * Checks if a bundle is a part of an app. This can be used to double check if you need to
     * add a bundle to apps but DON'T want duplicates.
     *
     * @param connection
     * @param bundle
     * @param viewId
     * @return
     * @throws SQLException
     */
    public static boolean appContainsBundle(Connection connection, long viewId, String bundle)
            throws SQLException {
        final String sql ="SELECT * FROM oskari_appsetup_bundles " +
                "WHERE bundle_id = (SELECT id FROM oskari_bundle WHERE name=?) " +
                "AND appsetup_id=?";

        try (final PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setString(1, bundle);
            statement.setLong(2, viewId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    public static Bundle getAppBundle(Connection connection, long viewId, String bundle)
            throws SQLException {
        final String sql ="SELECT * FROM oskari_appsetup_bundles " +
                "WHERE bundle_id = (SELECT id FROM oskari_bundle WHERE name=?) " +
                "AND appsetup_id=?";

        try (final PreparedStatement statement =
                     connection.prepareStatement(sql)){
            statement.setString(1, bundle);
            statement.setLong(2, viewId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    Bundle b = new Bundle();
                    b.setViewId(viewId);
                    b.setName(bundle);
                    b.setBundleId(rs.getLong("bundle_id"));
                    b.setConfig(rs.getString("config"));
                    b.setState(rs.getString("state"));
                    b.setSeqNo(rs.getInt("seqno"));
                    b.setBundleinstance(rs.getString("bundleinstance"));
                    return b;
                }
            }
        }
        return null;
    }

    public static Bundle updateAppBundle(Connection connection, long viewId, Bundle bundle)
            throws SQLException {
        final String sql = "UPDATE oskari_appsetup_bundles SET " +
                "config=?, " +
                "state=?, " +
                "seqno=?, " +
                "bundleinstance=? " +
                " WHERE bundle_id=? " +
                " AND appsetup_id=?";

        try (final PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setString(1, bundle.getConfig());
            statement.setString(2, bundle.getState());
            statement.setInt(3, bundle.getSeqNo());
            statement.setString(4, bundle.getBundleinstance());
            statement.setLong(5, bundle.getBundleId());
            statement.setLong(6, viewId);
            statement.execute();
        }
        return null;
    }

    public static void addBundleToApp(Connection connection, long viewId, String bundleid)
            throws SQLException {
        final String sql ="INSERT INTO oskari_appsetup_bundles" +
                "(appsetup_id, bundle_id, seqno, config, state, bundleinstance) " +
                "VALUES (" +
                "?, " +
                "(SELECT id FROM oskari_bundle WHERE name=?), " +
                "(SELECT max(seqno)+1 FROM oskari_appsetup_bundles WHERE appsetup_id=?), " +
                "(SELECT config FROM oskari_bundle WHERE name=?), " +
                "(SELECT state FROM oskari_bundle WHERE name=?),  " +
                "?)";
        try(final PreparedStatement statement =
                    connection.prepareStatement(sql)) {
            statement.setLong(1, viewId);
            statement.setString(2, bundleid);
            statement.setLong(3, viewId);
            statement.setString(4, bundleid);
            statement.setString(5, bundleid);
            statement.setString(6, bundleid);
            statement.execute();
        }
    }

    public static void removeBundleFromApp(Connection connection, long viewId, String bundleName)
            throws SQLException {
        final String sql ="DELETE FROM oskari_appsetup_bundles " +
                "WHERE bundle_id = (SELECT id FROM oskari_bundle WHERE name=?) AND appsetup_id=?";
        try(final PreparedStatement statement =
                    connection.prepareStatement(sql)) {
            statement.setString(1, bundleName);
            statement.setLong(2, viewId);
            statement.execute();
        }
    }

    private static Set<Integer> setupLayers(JSONObject viewJSON)
            throws Exception {

        final JSONArray layers = viewJSON.optJSONArray("selectedLayers");
        final Set<Integer> selectedLayerIds = new HashSet<Integer>();
        if (layers != null) {
            for (int i = 0; i < layers.length(); ++i) {
                final String layerfile = layers.getString(i);
                try {
                    selectedLayerIds.add(LayerHelper.setupLayer(layerfile));
                } catch (Exception ex) {
                    log.warn("Unable to setup layers from:", layerfile);
                    throw ex;
                }
            }
        }
        return selectedLayerIds;
    }

    private static JSONObject readViewFile(final String viewfile)
            throws Exception {
        log.info("/ - /json/views/" + viewfile);
        String json = IOHelper.readString(AppSetupHelper.class.getResourceAsStream("/json/views/" + viewfile));
        JSONObject viewJSON = JSONHelper.createJSONObject(json);
        log.debug(viewJSON);
        return viewJSON;
    }

    private static View createView(Connection conn, final JSONObject viewJSON)
            throws Exception {
        try {
            final View view = new View();
            view.setCreator(ConversionHelper.getLong(viewJSON.optString("creator"), -1));
            view.setIsPublic(viewJSON.optBoolean("public", false));
            view.setOnlyForUuId(viewJSON.optBoolean("onlyUuid", true));
            view.setType(viewJSON.getString("type"));
            view.setName(viewJSON.getString("name"));
            view.setIsDefault(viewJSON.optBoolean("default"));
            final JSONObject oskari = JSONHelper.getJSONObject(viewJSON, "oskari");
            view.setPage(oskari.getString("page"));
            view.setDevelopmentPath(oskari.getString("development_prefix"));
            view.setApplication(oskari.getString("application"));

            setupLayers(viewJSON);

            final JSONArray bundles = viewJSON.getJSONArray("bundles");
            for (int i = 0; i < bundles.length(); ++i) {
                final JSONObject bJSON = bundles.getJSONObject(i);
                final Bundle bundle = BundleHelper.getRegisteredBundle(bJSON.getString("id"), conn);
                if (bundle == null) {
                    throw new Exception("Bundle not registered - id:" + bJSON.getString("id"));
                }
                if (bJSON.has("instance")) {
                    bundle.setBundleinstance(bJSON.getString("instance"));
                }
                if (bJSON.has("config")) {
                    bundle.setConfig(bJSON.getJSONObject("config").toString());
                }
                if (bJSON.has("state")) {
                    bundle.setState(bJSON.getJSONObject("state").toString());
                }

                // set up seq number
                view.addBundle(bundle);
            }
            return view;
        } catch (Exception ex) {
            log.error(ex, "Unable to insert view! ");
        }
        return null;
    }

    private static void replaceSelectedLayers(final Bundle mapfull, final Set<Integer> idSet) {
        if (idSet == null || idSet.isEmpty()) {
            // nothing to setup
            return;
        }
        JSONArray layers = mapfull.getStateJSON().optJSONArray("selectedLayers");
        if (layers == null) {
            layers = new JSONArray();
            JSONHelper.putValue(mapfull.getStateJSON(), "selectedLayers", layers);
        }
        for (Integer id : idSet) {
            layers.put(JSONHelper.createJSONObject("id", id));
        }
    }

}