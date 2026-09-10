//! Destination rules for broadcaster-controlled HLS subresources.

use std::net::{IpAddr, SocketAddr};

/// Reject local, special-use and multicast addresses before connecting.
pub fn is_public_address(ip: &IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            let [first, second, _, _] = v4.octets();
            !(v4.is_private()
                || v4.is_loopback()
                || v4.is_link_local()
                || v4.is_broadcast()
                || v4.is_documentation()
                || first == 0
                || first >= 224
                || (first == 100 && second & 0xc0 == 64)
                || (first == 198 && second & 0xfe == 18))
        }
        IpAddr::V6(v6) => {
            if let Some(v4) = v6.to_ipv4_mapped() {
                return is_public_address(&IpAddr::V4(v4));
            }
            // Public IPv6 unicast is 2000::/3. Limiting to it also excludes
            // local translation prefixes that could tunnel a private IPv4.
            v6.segments()[0] & 0xe000 == 0x2000
                && !(v6.segments()[0] == 0x2001 && v6.segments()[1] == 0x0db8)
        }
    }
}

/// Literal addresses bypass a client's DNS resolver, so both the initial URL
/// and every redirect must pass this check as well as DNS validation.
pub fn hls_destination_allowed(scheme: &str, host: &str) -> bool {
    if !matches!(scheme, "http" | "https") || host.is_empty() {
        return false;
    }
    let host = host.trim_start_matches('[').trim_end_matches(']');
    if let Ok(ip) = host.parse::<IpAddr>() {
        return is_public_address(&ip);
    }
    let name = host.trim_end_matches('.').to_ascii_lowercase();
    name != "localhost" && !name.ends_with(".localhost")
}

/// Reject mixed DNS answers too: the connector can choose any returned IP.
pub fn public_addresses(addrs: &[SocketAddr]) -> bool {
    !addrs.is_empty() && addrs.iter().all(|addr| is_public_address(&addr.ip()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn local_and_special_destinations_are_refused() {
        for host in [
            "127.0.0.1",
            "192.168.1.1",
            "10.2.3.4",
            "172.16.2.3",
            "169.254.169.254",
            "100.64.0.1",
            "0.0.0.0",
            "0.1.2.3",
            "224.0.0.1",
            "255.255.255.255",
            "198.18.0.1",
            "192.0.2.1",
            "[::1]",
            "[::ffff:127.0.0.1]",
            "[fc00::1]",
            "[fe80::1]",
            "[ff02::1]",
            "[2001:db8::1]",
            "[64:ff9b::c0a8:101]",
            "localhost",
            "LOCALHOST.",
            "radio.localhost",
            "radio.localhost.",
        ] {
            assert!(!hls_destination_allowed("https", host), "{host}");
        }
        assert!(!hls_destination_allowed("file", "example.com"));
        assert!(!hls_destination_allowed("https", ""));
    }

    #[test]
    fn public_broadcasters_and_cdns_remain_reachable() {
        for host in [
            "radio.example.com",
            "8.8.8.8",
            "[2606:4700:4700::1111]",
            "[::ffff:8.8.8.8]",
        ] {
            assert!(hls_destination_allowed("https", host), "{host}");
        }
    }

    #[test]
    fn every_resolved_address_must_be_public() {
        let public = "8.8.8.8:443".parse().unwrap();
        let private = "127.0.0.1:443".parse().unwrap();
        assert!(!public_addresses(&[]));
        assert!(public_addresses(&[public]));
        assert!(!public_addresses(&[public, private]));
        assert!(!public_addresses(&[private, public]));
    }
}
