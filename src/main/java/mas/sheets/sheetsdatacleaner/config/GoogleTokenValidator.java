package mas.sheets.sheetsdatacleaner.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@Slf4j
public class GoogleTokenValidator {

    private final String clientId;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GoogleTokenValidator(@Value("${cleaner.audience}") String clientId) {
        this.clientId = clientId;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    public boolean validateToken(String accessToken) {
        try {
            String url = "https://oauth2.googleapis.com/tokeninfo?access_token=" + accessToken;
            String response = restTemplate.getForObject(url, String.class);

            if (response == null) {
                log.warn("Empty response from Google tokeninfo");
                return false;
            }

            JsonNode tokenInfo = objectMapper.readTree(response);

            String aud = tokenInfo.has("aud") ? tokenInfo.get("aud").asText() : null;
            String azp = tokenInfo.has("azp") ? tokenInfo.get("azp").asText() : null;

            boolean validAudience = clientId.equals(aud) || clientId.equals(azp);

            if (!validAudience) {
                log.warn("Invalid audience. Expected: {}, Got aud: {}, azp: {}", clientId, aud, azp);
                return false;
            }

            if (tokenInfo.has("exp")) {
                long exp = tokenInfo.get("exp").asLong();
                long now = System.currentTimeMillis() / 1000;
                if (exp < now) {
                    log.warn("Token expired. exp: {}, now: {}", exp, now);
                    return false;
                }
            }

            log.debug("Token validation successful for client: {}", clientId);

            return true;
        } catch (Exception e) {
            log.warn("Token validation failed: {}", e.getMessage());
            return false;
        }
    }
}
