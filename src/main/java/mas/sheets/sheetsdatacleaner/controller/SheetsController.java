package mas.sheets.sheetsdatacleaner.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test")
public class SheetsController {

    @PostMapping
    public String test(@RequestBody String input) {
        return input.toUpperCase();
    }

}
