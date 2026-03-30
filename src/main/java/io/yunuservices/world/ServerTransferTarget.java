package io.yunuservices.world;

public record ServerTransferTarget(String host, int port) {

    public static ServerTransferTarget parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            throw new TransferTargetParseException("service.transfer_target_invalid_format");
        }

        final String value = raw.strip();
        final int separator = value.lastIndexOf(':');
        if (separator < 0) {
            return new ServerTransferTarget(validateHost(value), 25565);
        }

        final String host = validateHost(value.substring(0, separator));
        final String portValue = value.substring(separator + 1).strip();
        if (portValue.isEmpty()) {
            throw new TransferTargetParseException("service.transfer_target_empty_port");
        }

        try {
            final int port = Integer.parseInt(portValue);
            if (port < 1 || port > 65535) {
                throw new TransferTargetParseException("service.transfer_target_port_range");
            }
            return new ServerTransferTarget(host, port);
        } catch (final NumberFormatException ex) {
            throw new TransferTargetParseException("service.transfer_target_port_number");
        }
    }

    public String asConfigValue() {
        return this.host + ":" + this.port;
    }

    private static String validateHost(final String rawHost) {
        final String host = rawHost == null ? "" : rawHost.strip();
        if (host.isEmpty()) {
            throw new TransferTargetParseException("service.transfer_target_empty_host");
        }
        return host;
    }
}
