package mas.sheets.sheetsdatacleaner.service;

import java.util.Optional;

public interface DatasetContextStore<T> {

    String save(T context);

    Optional<T> getAndDelete(String contextId);
}



