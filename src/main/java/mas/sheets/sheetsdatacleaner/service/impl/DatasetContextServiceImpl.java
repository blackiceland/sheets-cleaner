package mas.sheets.sheetsdatacleaner.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.config.TokenProperties;
import mas.sheets.sheetsdatacleaner.config.ContextProperties;
import mas.sheets.sheetsdatacleaner.dto.DatasetContext;
import mas.sheets.sheetsdatacleaner.dto.DatasetPayload;
import mas.sheets.sheetsdatacleaner.exception.ContextNotFoundException;
import mas.sheets.sheetsdatacleaner.exception.ContextValidationException;
import mas.sheets.sheetsdatacleaner.service.DatasetContextService;
import mas.sheets.sheetsdatacleaner.service.DatasetContextStore;
import mas.sheets.sheetsdatacleaner.util.DatasetTokenUtil;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetContextServiceImpl implements DatasetContextService {

    private static final int CURRENT_SCHEMA_VERSION = 1;

    private final DatasetContextStore<DatasetContext> store;
    private final TokenProperties tokenProperties;
    private final ContextProperties contextProperties;

    @Override
    public String saveContext(DatasetContext context) {
        int approxSize = approximateSizeBytes(context);

        if (approxSize > contextProperties.getMaxBytes()) {
            throw new ContextValidationException("Context too large");
        }

        return store.save(context);
    }

    @Override
    public DatasetContext loadContextOrLegacy(String tokenOrId) {
        if (isUuid(tokenOrId)) {
            Optional<DatasetContext> loaded = store.getAndDelete(tokenOrId);

            if (loaded.isPresent()) {
                return loaded.get();
            }

            throw new ContextNotFoundException("Context not found or expired");
        }

        byte[] secretBytes = tokenProperties.getSecret() == null
                ? new byte[0]
                : tokenProperties.getSecret().getBytes(StandardCharsets.UTF_8);

        DatasetPayload payload;

        try {
            payload = DatasetTokenUtil.decode(tokenOrId, secretBytes);
        } catch (IllegalArgumentException ex) {
            String msg = ex.getMessage() == null ? "Invalid dataset token" : ex.getMessage();

            if (msg.toLowerCase().contains("expired")) {
                throw new ContextNotFoundException("Context not found or expired");
            }

            throw new ContextValidationException(msg);
        }

        return new DatasetContext(CURRENT_SCHEMA_VERSION, payload.rows());
    }

    private boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private int approximateSizeBytes(DatasetContext context) {
        int rowsCount = context.rows() == null ? 0 : context.rows().size();
        int perRowEstimate = 256;

        return 64 + rowsCount * perRowEstimate;
    }
}


