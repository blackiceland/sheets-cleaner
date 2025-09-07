package mas.sheets.sheetsdatacleaner.service.impl;

import lombok.RequiredArgsConstructor;
import mas.sheets.sheetsdatacleaner.config.ContextProperties;
import mas.sheets.sheetsdatacleaner.service.DatasetContextStore;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RedisDatasetContextStore<T> implements DatasetContextStore<T> {

    private static final String KEY_PREFIX = "ds:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ContextProperties contextProperties;

    @Override
    public String save(T context) {
        String contextId = UUID.randomUUID().toString();
        String key = buildKey(contextId);
        Duration ttl = Duration.ofSeconds(contextProperties.getTtlSeconds());
        redisTemplate.opsForValue().set(key, context, ttl);

        return contextId;
    }

    @Override
    public Optional<T> getAndDelete(String contextId) {
        String key = buildKey(contextId);
        @SuppressWarnings("unchecked")
        T value = (T) redisTemplate.opsForValue().getAndDelete(key);

        return Optional.ofNullable(value);
    }

    private String buildKey(String contextId) {
        return KEY_PREFIX + "{" + contextId + "}";
    }
}


