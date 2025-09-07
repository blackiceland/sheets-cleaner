package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.config.ContextProperties;
import mas.sheets.sheetsdatacleaner.config.TokenProperties;
import mas.sheets.sheetsdatacleaner.dto.DatasetContext;
import mas.sheets.sheetsdatacleaner.exception.ContextNotFoundException;
import mas.sheets.sheetsdatacleaner.service.impl.CaffeineDatasetContextStore;
import mas.sheets.sheetsdatacleaner.service.impl.DatasetContextServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DatasetContextServiceImplTest {

    @Test
    void save_and_load_then_delete_context() {
        ContextProperties contextProperties = new ContextProperties();
        contextProperties.setTtlSeconds(60);
        contextProperties.setMaxBytes(1048576);

        DatasetContextStore<DatasetContext> store = new CaffeineDatasetContextStore<>(contextProperties);

        TokenProperties tokenProperties = new TokenProperties();
        tokenProperties.setSecret("0123456789abcdef0123456789abcdef");

        DatasetContextService service = new DatasetContextServiceImpl(store, tokenProperties, contextProperties);

        DatasetContext datasetContext = new DatasetContext(1, List.of());
        String savedContext = service.saveContext(datasetContext);

        DatasetContext loadContextOrLegacy = service.loadContextOrLegacy(savedContext);
        assertThat(loadContextOrLegacy).isNotNull();

        assertThatThrownBy(() -> service.loadContextOrLegacy(savedContext))
                .isInstanceOf(ContextNotFoundException.class);

    }
}


