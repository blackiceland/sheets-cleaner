package mas.sheets.sheetsdatacleaner.advice;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import mas.sheets.sheetsdatacleaner.exception.TooManyRequestsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.PayloadTooLargeException;

@RestControllerAdvice
public class ErrorHandler {

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse badRequest(Exception ex) {
        return new ErrorResponse("BAD_REQUEST", ex.getMessage());
    }

    @ExceptionHandler({PayloadTooLargeException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    ErrorResponse payloadTooLarge(Exception ex) {
        return new ErrorResponse("PAYLOAD_TOO_LARGE", ex.getMessage());
    }

    @ExceptionHandler({BulkheadFullException.class, TooManyRequestsException.class})   // <-- расширяем список
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    ErrorResponse tooManyRequests() {
        return new ErrorResponse("SERVICE_BUSY", "service busy, try later");
    }

}
