package mas.sheets.sheetsdatacleaner.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import mas.sheets.sheetsdatacleaner.config.ContextProperties;
import mas.sheets.sheetsdatacleaner.service.DatasetContextStore;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CaffeineDatasetContextStore<T> implements DatasetContextStore<T> {

    private final ContextProperties contextProperties;

    private volatile Cache<String, T> cache;

    private Cache<String, T> getCache() {
        Cache<String, T> c = cache;
        if (c == null) {
            synchronized (this) {
                if (cache == null) {
                    cache = Caffeine.newBuilder()
                            .expireAfterWrite(Duration.ofSeconds(contextProperties.getTtlSeconds()))
                            .maximumSize(50_000)
                            .build();
                }
                c = cache;
            }
        }
        return c;
    }

    @Override
    public String save(T context) {
        String id = UUID.randomUUID().toString();
        getCache().put(id, context);

        return id;
    }

    @Override
    public Optional<T> getAndDelete(String contextId) {
        T value = getCache().asMap().remove(contextId);

        return Optional.ofNullable(value);
    }
}


