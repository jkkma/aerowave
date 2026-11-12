//! Destination rules for broadcaster-controlled HTTP resources.

use std::net::{IpAddr, SocketAddr};
use url::Url;

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
pub fn public_http_destination_allowed(scheme: &str, host: &str) -> bool {
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

/// An ordinary station may be entered on a LAN. A public station cannot use
/// redirects, playlists or DNS to acquire that permission for itself.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum StationNetwork {
    Lan,
    Public,
}

impl StationNetwork {
    pub fn for_source(url: &Url) -> Self {
        if url.host_str().is_some_and(explicit_lan_host) {
            Self::Lan
        } else {
            Self::Public
        }
    }

    /// Once a chain reaches a public broadcaster, keep all its later hops
    /// public, including a retry using a different transport.
    pub fn admit(&mut self, url: &Url) -> Result<(), &'static str> {
        let host = url.host_str().ok_or("that station URL has no host")?;
        if !matches!(url.scheme(), "http" | "https") {
            return Err("only HTTP and HTTPS stations are supported");
        }
        if *self == Self::Lan && explicit_lan_host(host) {
            return Ok(());
        }
        *self = Self::Public;
        if explicit_lan_host(host) || !public_http_destination_allowed(url.scheme(), host) {
            return Err("a public station cannot connect to a private address");
        }
        Ok(())
    }

    pub fn addresses_allowed(self, addrs: &[SocketAddr]) -> bool {
        match self {
            Self::Lan => !addrs.is_empty() && addrs.iter().all(|addr| lan_address(addr.ip())),
            Self::Public => public_addresses(addrs),
        }
    }
}

fn explicit_lan_host(host: &str) -> bool {
    let host = host.trim_start_matches('[').trim_end_matches(']');
    if let Ok(ip) = host.parse::<IpAddr>() {
        return lan_address(ip);
    }
    let name = host.trim_end_matches('.');
    // These names are normally entered for a local service. For any other
    // hostname, a private DNS answer cannot opt the source into LAN access.
    !name.contains('.')
        || name.ends_with(".localhost")
        || name.ends_with(".local")
        || name.ends_with(".home.arpa")
}

fn lan_address(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => ip.is_loopback() || ip.is_private() || ip.is_link_local(),
        IpAddr::V6(ip) => {
            if let Some(v4) = ip.to_ipv4_mapped() {
                return lan_address(v4.into());
            }
            let first = ip.segments()[0];
            ip.is_loopback() || first & 0xfe00 == 0xfc00 || first & 0xffc0 == 0xfe80
        }
    }
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
            assert!(!public_http_destination_allowed("https", host), "{host}");
        }
        assert!(!public_http_destination_allowed("file", "example.com"));
        assert!(!public_http_destination_allowed("https", ""));
    }

    #[test]
    fn public_broadcasters_and_cdns_remain_reachable() {
        for host in [
            "radio.example.com",
            "8.8.8.8",
            "[2606:4700:4700::1111]",
            "[::ffff:8.8.8.8]",
        ] {
            assert!(public_http_destination_allowed("https", host), "{host}");
        }
    }

    #[test]
    fn artwork_initial_and_redirect_destinations_use_the_same_public_rule() {
        assert!(public_http_destination_allowed("https", "images.example.com"));
        for private_target in ["127.0.0.1", "169.254.169.254", "radio.localhost"] {
            assert!(!public_http_destination_allowed("http", private_target));
            assert!(!public_http_destination_allowed("https", private_target));
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

    #[test]
    fn a_public_hop_ends_lan_permission_for_the_rest_of_the_station_chain() {
        let local = Url::parse("http://radio.home.arpa/listen").unwrap();
        let public = Url::parse("https://station.example/listen").unwrap();
        let mut chain = StationNetwork::for_source(&local);
        assert_eq!(chain.admit(&local), Ok(()));
        assert_eq!(chain.admit(&public), Ok(()));
        assert!(chain.admit(&local).is_err());
        assert!(chain
            .admit(&Url::parse("http://[::1]/stream").unwrap())
            .is_err());
    }

    #[test]
    fn dns_cannot_grant_a_public_station_access_to_private_addresses() {
        let local = ["127.0.0.1:80".parse().unwrap()];
        let public = ["8.8.8.8:80".parse().unwrap()];
        for (url, allowed, refused) in [
            ("http://station.example/", public, local),
            ("http://radio.local/", local, public),
        ] {
            let url = Url::parse(url).unwrap();
            let mut chain = StationNetwork::for_source(&url);
            assert_eq!(chain.admit(&url), Ok(()));
            assert!(chain.addresses_allowed(&allowed));
            assert!(!chain.addresses_allowed(&refused));
            assert!(!chain.addresses_allowed(&[local[0], public[0]]));
        }
    }
}
