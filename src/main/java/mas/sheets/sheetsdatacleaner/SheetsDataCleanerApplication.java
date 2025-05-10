package mas.sheets.sheetsdatacleaner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SheetsDataCleanerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SheetsDataCleanerApplication.class, args);
    }

}
