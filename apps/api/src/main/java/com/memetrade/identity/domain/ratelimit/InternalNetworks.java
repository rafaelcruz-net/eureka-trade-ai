package com.memetrade.identity.domain.ratelimit;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * CIDR list of the internal networks exempt from the limit of the legal documents (security-design.md Q1, NFR6.3).
 * Only IP LITERALS are parsed (never a host name: no DNS lookup ever happens). An IPv4 address mapped in IPv6
 * ({@code ::ffff:a.b.c.d}) is compared as the IPv4 address. An invalid entry stops the api at start-up.
 */
public final class InternalNetworks {

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private final List<Range> ranges;

    private InternalNetworks(List<Range> ranges) {
        this.ranges = List.copyOf(ranges);
    }

    public static InternalNetworks none() {
        return new InternalNetworks(List.of());
    }

    /**
     * @param cidrs entries such as {@code 172.28.1.0/24} or {@code fd00::/8}; a bare address is a /32 or /128
     * @throws IllegalArgumentException naming the setting when an entry is not a CIDR of IP literals
     */
    public static InternalNetworks parse(List<String> cidrs) {
        List<Range> ranges = new ArrayList<>();
        for (String cidr : cidrs) {
            ranges.add(Range.parse(cidr.strip()));
        }
        return new InternalNetworks(ranges);
    }

    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    /** True when the address (an IP literal, as {@code getRemoteAddr()} gives it) is inside one of the ranges. */
    public boolean contains(String address) {
        if (ranges.isEmpty() || address == null) {
            return false;
        }
        Optional<byte[]> bytes = literal(address.strip());
        return bytes.isPresent() && ranges.stream().anyMatch(range -> range.contains(bytes.get()));
    }

    /** The bytes of an IP literal, IPv4-mapped IPv6 reduced to its 4 IPv4 bytes; empty for anything else. */
    static Optional<byte[]> literal(String value) {
        String candidate =
                value.startsWith("[") && value.endsWith("]") ? value.substring(1, value.length() - 1) : value;
        boolean ipv4 = IPV4.matcher(candidate).matches();
        if (!ipv4 && !(candidate.contains(":") && IPV6.matcher(candidate).matches())) {
            return Optional.empty();
        }
        try {
            // Safe: the value is a literal (checked above), so getByName never resolves a name.
            byte[] bytes = InetAddress.getByName(candidate).getAddress();
            return Optional.of(unmapped(bytes));
        } catch (UnknownHostException notAnAddress) {
            return Optional.empty();
        }
    }

    private static byte[] unmapped(byte[] bytes) {
        if (bytes.length != 16) {
            return bytes;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return bytes;
            }
        }
        if (bytes[10] != (byte) 0xff || bytes[11] != (byte) 0xff) {
            return bytes;
        }
        return new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
    }

    /** One CIDR range. */
    private record Range(byte[] network, int prefix) {

        static Range parse(String cidr) {
            String[] parts = cidr.split("/", -1);
            if (parts.length > 2) {
                throw invalid(cidr);
            }
            byte[] address = literal(parts[0]).orElseThrow(() -> invalid(cidr));
            // An IPv4-mapped IPv6 network (::ffff:a.b.c.d/96..128) becomes the IPv4 network with 96 bits less.
            boolean mapped = parts[0].contains(":") && address.length == 4;
            int bits = mapped ? 128 : address.length * 8;
            int prefix = parts.length == 2 ? prefix(parts[1], bits, cidr) : bits;
            if (mapped) {
                if (prefix < 96) {
                    throw invalid(cidr);
                }
                prefix -= 96;
            }
            return new Range(address, prefix);
        }

        boolean contains(byte[] candidate) {
            if (candidate.length != network.length) {
                return false;
            }
            int bits = network.length * 8;
            BigInteger mask = BigInteger.ONE
                    .shiftLeft(bits)
                    .subtract(BigInteger.ONE)
                    .shiftRight(prefix)
                    .not()
                    .and(BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE));
            return new BigInteger(1, candidate).and(mask).equals(new BigInteger(1, network).and(mask));
        }

        private static int prefix(String value, int bits, String cidr) {
            try {
                int prefix = Integer.parseInt(value);
                if (prefix < 0 || prefix > bits) {
                    throw invalid(cidr);
                }
                return prefix;
            } catch (NumberFormatException notANumber) {
                throw invalid(cidr);
            }
        }

        private static IllegalArgumentException invalid(String cidr) {
            return new IllegalArgumentException("memetrade.legal.rate-limit.internal-networks"
                    + " (MEMETRADE_LEGAL_RATE_LIMIT_INTERNAL_NETWORKS) has an entry that is not a CIDR of IP literals: "
                    + cidr);
        }
    }
}
