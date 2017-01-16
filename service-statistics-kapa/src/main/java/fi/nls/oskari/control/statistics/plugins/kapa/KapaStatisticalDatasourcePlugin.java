package fi.nls.oskari.control.statistics.plugins.kapa;

import fi.nls.oskari.control.statistics.plugins.StatisticalDatasourcePlugin;
import fi.nls.oskari.control.statistics.data.StatisticalIndicator;
import fi.nls.oskari.control.statistics.plugins.db.DatasourceLayer;
import fi.nls.oskari.control.statistics.plugins.db.StatisticalDatasource;
import fi.nls.oskari.control.statistics.plugins.kapa.parser.KapaIndicatorsParser;
import fi.nls.oskari.control.statistics.plugins.kapa.requests.KapaRequest;
import fi.nls.oskari.domain.User;
import fi.nls.oskari.log.LogFactory;
import fi.nls.oskari.log.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KapaStatisticalDatasourcePlugin  extends StatisticalDatasourcePlugin {
    private final static Logger LOG = LogFactory.getLogger(KapaStatisticalDatasourcePlugin.class);
    private KapaIndicatorsParser indicatorsParser;

    /**
     * Maps the KaPa layer identifiers to Oskari layers.
     */
    private Map<String, Long> layerMappings;

    public KapaStatisticalDatasourcePlugin() {
        indicatorsParser = new KapaIndicatorsParser();
    }

    @Override
    public List<StatisticalIndicator> getIndicators(User user) {
        // Getting the general information of all the indicator layers.
        KapaRequest request = new KapaRequest();
        String jsonResponse = request.getIndicators();
        List<StatisticalIndicator> indicators = indicatorsParser.parse(jsonResponse, layerMappings);
        return indicators;
    }

    @Override
    public void init(StatisticalDatasource source) {
        super.init(source);
        // Fetching the layer mapping from the database.

        final List<DatasourceLayer> layerRows = source.getLayers();
        layerMappings = new HashMap<>();

        for (DatasourceLayer row : layerRows) {
            layerMappings.put(row.getConfig("regionType").toLowerCase(), row.getMaplayerId());
        }
        LOG.debug("KaPa layer mappings: ", layerMappings);
    }
}
