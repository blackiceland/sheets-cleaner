package mas.sheets.sheetsdatacleaner.parser;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import mas.sheets.sheetsdatacleaner.exception.InvalidPayloadException;
import mas.sheets.sheetsdatacleaner.exception.PayloadTooLargeException;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Component
public class RowJsonStreamParser {

    private static final int MAX_ROWS = 20_000;
    private final JsonFactory jsonFactory = new JsonFactory();

    public List<List<String>> parse(InputStream in) {
        try (JsonParser parser = jsonFactory.createParser(in)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new InvalidPayloadException();
            }

            List<List<String>> rows = new ArrayList<>();

            while (parser.nextToken() != JsonToken.END_OBJECT) {

                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    throw new InvalidPayloadException();
                }

                String field = parser.currentName();

                if ("rows".equals(field)) {

                    if (parser.nextToken() != JsonToken.START_ARRAY) {
                        throw new InvalidPayloadException();
                    }

                    while (parser.nextToken() != JsonToken.END_ARRAY) {

                        if (parser.currentToken() != JsonToken.START_ARRAY) {
                            throw new InvalidPayloadException();
                        }

                        List<String> cells = new ArrayList<>();

                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                                throw new InvalidPayloadException();
                            }
                            cells.add(parser.getText());
                        }

                        rows.add(cells);

                        if (rows.size() > MAX_ROWS) {
                            throw new PayloadTooLargeException();
                        }
                    }
                } else {
                    parser.nextToken();
                    parser.skipChildren();
                }
            }
            return rows;
        } catch (PayloadTooLargeException | InvalidPayloadException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidPayloadException();
        }
    }
}