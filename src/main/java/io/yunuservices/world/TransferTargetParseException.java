package io.yunuservices.world;

public final class TransferTargetParseException extends IllegalArgumentException {

    private final String messageKey;

    public TransferTargetParseException(final String messageKey) {
        super(messageKey);
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return this.messageKey;
    }
}
