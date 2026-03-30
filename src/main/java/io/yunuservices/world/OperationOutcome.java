package io.yunuservices.world;

public record OperationOutcome<T>(boolean success, String message, T value) {

    public static <T> OperationOutcome<T> success(final String message, final T value) {
        return new OperationOutcome<>(true, message, value);
    }

    public static <T> OperationOutcome<T> failure(final String message) {
        return new OperationOutcome<>(false, message, null);
    }
}
