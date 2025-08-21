package mas.sheets.sheetsdatacleaner.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Profile("test")
public class TestSecurityConfig {

    @Bean
    @Order(0)
    SecurityFilterChain testPermitAll(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }
}


