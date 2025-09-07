package mas.sheets.sheetsdatacleaner.controller;

import mas.sheets.sheetsdatacleaner.dto.response.ErrorResponse;
import mas.sheets.sheetsdatacleaner.exception.ContextNotFoundException;
import mas.sheets.sheetsdatacleaner.exception.ForbiddenContextAccessException;
import mas.sheets.sheetsdatacleaner.exception.ContextValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ContextNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleContextNotFound(ContextNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(new ErrorResponse("CONTEXT_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(ContextValidationException.class)
    public ResponseEntity<ErrorResponse> handleInvalidContext(ContextValidationException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("INVALID_CONTEXT", ex.getMessage()));
    }

    @ExceptionHandler(ForbiddenContextAccessException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenContextAccessException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("FORBIDDEN", ex.getMessage()));
    }
}




