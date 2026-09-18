package com.miko3.mode.voice;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * The relay's host:port as typed on the settings page (KTD10, R11), and the
 * local-network rule it has to satisfy (R13): the relay decides where the
 * microphone's audio goes, so only a private, link-local, or loopback
 * address is accepted.
 *
 * Two checks, because a hostname can't be judged until it resolves:
 * parse() refuses a literal IP outside those ranges when the form is
 * saved, and accepts a well-formed hostname on syntax alone (resolving on
 * the HTTP thread would stall the page on a slow DNS answer). Whoever
 * connects then calls resolveAllowed(), which resolves the hostname and
 * applies isAllowedRelayAddress() to the address it will actually dial.
 *
 * Loopback is allowed on purpose: `adb reverse tcp:8790 tcp:8790` lets a
 * relay on the dev machine reach the robot over the adb tunnel for
 * testing, and a loopback connection never leaves the device, so R13 still
 * holds.
 *
 * Plain Java (no android.*) so scripts/tests can exercise it on the host JVM.
 */
final class RelayAddress {
    /** A value the settings page refuses; the message is shown to the user as-is. */
    static final class InvalidException extends Exception {
        InvalidException(String message) {
            super(message);
        }
    }

    static final String EXAMPLE = "192.168.1.20:8790";

    final String host;
    final int port;
    // Null for a hostname: only known once resolveAllowed() runs.
    private final InetAddress literal;

    private RelayAddress(String host, int port, InetAddress literal) {
        this.host = host;
        this.port = port;
        this.literal = literal;
    }

    boolean isHostname() {
        return literal == null;
    }

    /** host:port, with brackets around an IPv6 literal — the form parse() accepts back. */
    @Override
    public String toString() {
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }

    static RelayAddress parse(String raw) throws InvalidException {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            throw new InvalidException("Enter the relay address as host:port, for example " + EXAMPLE + ".");
        }
        if (s.indexOf('/') >= 0) {
            throw new InvalidException("Enter just host:port, without ws:// or a path, for example "
                    + EXAMPLE + ".");
        }
        String host;
        String portText;
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close < 0) {
                throw new InvalidException("Missing ] after the IPv6 address.");
            }
            host = s.substring(1, close);
            String rest = s.substring(close + 1);
            if (!rest.startsWith(":")) {
                throw new InvalidException("Missing port: enter host:port, for example [fd00::5]:8790.");
            }
            portText = rest.substring(1);
        } else {
            int colon = s.lastIndexOf(':');
            if (colon < 0) {
                throw new InvalidException("Missing port: enter host:port, for example " + EXAMPLE + ".");
            }
            if (s.indexOf(':') != colon) {
                throw new InvalidException("Put an IPv6 address in brackets, for example [fd00::5]:8790.");
            }
            host = s.substring(0, colon);
            portText = s.substring(colon + 1);
        }
        if (host.isEmpty()) {
            throw new InvalidException("Missing host: enter host:port, for example " + EXAMPLE + ".");
        }
        int port = parsePort(portText);

        InetAddress literal = null;
        if (host.indexOf(':') >= 0) {
            literal = parseIpv6(host);
        } else if (host.matches("[0-9.]+")) {
            literal = parseIpv4(host);
        } else if (!isHostnameSyntax(host)) {
            throw new InvalidException("\"" + host + "\" is not a valid host name or IP address.");
        }
        if (literal != null && !isAllowedRelayAddress(literal)) {
            throw new InvalidException(host + " is not a private or link-local address; the relay must be "
                    + "on the local network (10.x, 172.16-31.x, 192.168.x, or 169.254.x).");
        }
        return new RelayAddress(host, port, literal);
    }

    /**
     * The connect-time half of the check: resolves a hostname (a literal
     * needs no lookup) and refuses the result unless isAllowedRelayAddress()
     * accepts it. Blocks on DNS — call it from the connecting thread, never the
     * main thread. Throws UnknownHostException when the name doesn't resolve,
     * and a plain IOException naming the address when it resolves off the
     * local network.
     */
    InetSocketAddress resolveAllowed() throws IOException {
        InetAddress addr = literal != null ? literal : InetAddress.getByName(host);
        if (!isAllowedRelayAddress(addr)) {
            throw new IOException("relay host " + host + " resolved to " + addr.getHostAddress()
                    + ", which is not a private or link-local address (R13)");
        }
        return new InetSocketAddress(addr, port);
    }

    /**
     * R13's rule on a concrete address: RFC 1918 (10/8, 172.16/12,
     * 192.168/16), IPv4 link-local 169.254/16, IPv6 link-local fe80::/10 and
     * unique-local fc00::/7, or loopback (see the class comment). Refuses the
     * wildcard address, multicast, and everything else — including carrier-grade
     * NAT 100.64/10, which is not a LAN.
     */
    static boolean isAllowedRelayAddress(InetAddress addr) {
        if (addr == null || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
            return false;
        }
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
            return true;
        }
        if (addr instanceof Inet4Address) {
            // Inet4Address.isSiteLocalAddress() is exactly the three RFC 1918 blocks.
            return addr.isSiteLocalAddress();
        }
        // Inet6Address.isSiteLocalAddress() means the deprecated fec0::/10, not
        // unique-local; check fc00::/7 directly.
        return (addr.getAddress()[0] & 0xfe) == 0xfc;
    }

    private static int parsePort(String text) throws InvalidException {
        if (text.isEmpty()) {
            throw new InvalidException("Missing port: enter host:port, for example " + EXAMPLE + ".");
        }
        if (!text.matches("[0-9]{1,5}")) {
            throw new InvalidException("The port must be a number from 1 to 65535.");
        }
        int port = Integer.parseInt(text);
        if (port < 1 || port > 65535) {
            throw new InvalidException("The port must be a number from 1 to 65535.");
        }
        return port;
    }

    private static InetAddress parseIpv4(String host) throws InvalidException {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            throw new InvalidException(host + " is not a valid IPv4 address.");
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) {
                throw new InvalidException(host + " is not a valid IPv4 address.");
            }
            int v = Integer.parseInt(parts[i]);
            if (v > 255) {
                throw new InvalidException(host + " is not a valid IPv4 address.");
            }
            bytes[i] = (byte) v;
        }
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            throw new InvalidException(host + " is not a valid IPv4 address.");
        }
    }

    private static InetAddress parseIpv6(String host) throws InvalidException {
        // Only hex digits, colons and dots (an embedded IPv4 tail): with nothing
        // else present, getByName() parses a literal and never touches DNS.
        if (!host.matches("[0-9A-Fa-f:.]+")) {
            throw new InvalidException(host + " is not a valid IPv6 address.");
        }
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new InvalidException(host + " is not a valid IPv6 address.");
        }
    }

    /** RFC 1123 host name: dot-separated labels of letters, digits and inner hyphens. */
    private static boolean isHostnameSyntax(String host) {
        if (host.length() > 253) {
            return false;
        }
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63
                    || !label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")) {
                return false;
            }
        }
        return true;
    }
}
