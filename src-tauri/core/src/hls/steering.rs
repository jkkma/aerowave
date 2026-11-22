use std::collections::{HashMap, HashSet};

use serde::Deserialize;
use url::Url;

use super::{
    identity_ignoring_query_parameters, Resource, ResourceKind, MAX_RESOURCES, MAX_URI_BYTES,
};
use crate::network::{clear_userinfo, inherit_userinfo, public_http_destination_allowed};

const MAX_RELOAD_URLS: usize = 64;

pub struct Steering {
    documents: HashSet<Url>,
    pathways: HashMap<String, Vec<Track>>,
    tracks: usize,
}

#[derive(Clone)]
struct Track {
    url: Url,
    stable_id: Option<String>,
    rendition: bool,
    suppress_userinfo: bool,
}

pub struct SteeredPlaylist {
    pub url: Url,
    /// hls.js can copy URL credentials while replacing a playlist's host.
    pub suppress_userinfo: bool,
}

#[derive(Deserialize)]
struct Document {
    #[serde(rename = "VERSION")]
    version: u32,
    #[serde(rename = "RELOAD-URI")]
    reload: Option<String>,
    #[serde(rename = "PATHWAY-CLONES", default)]
    clones: Vec<PathwayClone>,
}

#[derive(Deserialize)]
struct PathwayClone {
    #[serde(rename = "BASE-ID")]
    base: String,
    #[serde(rename = "ID")]
    id: String,
    #[serde(rename = "URI-REPLACEMENT")]
    replacement: Replacement,
}

#[derive(Deserialize)]
struct Replacement {
    #[serde(rename = "HOST")]
    host: Option<String>,
    #[serde(rename = "PARAMS", default)]
    params: HashMap<String, String>,
    #[serde(rename = "PER-VARIANT-URIS", default)]
    variants: HashMap<String, String>,
    #[serde(rename = "PER-RENDITION-URIS", default)]
    renditions: HashMap<String, String>,
}

impl Steering {
    pub fn from_master(resources: &[Resource]) -> Option<Self> {
        let url = &resources
            .iter()
            .rev()
            .find(|resource| matches!(resource.kind, ResourceKind::Steering))?
            .url;
        if !public_url(url) {
            return None;
        }
        let mut pathways: HashMap<String, Vec<Track>> = HashMap::new();
        let mut renditions: HashMap<_, Vec<_>> = HashMap::new();
        for (index, resource) in resources.iter().enumerate() {
            if let ResourceKind::Rendition {
                group: Some((kind, group)),
                stable_id,
            } = &resource.kind
            {
                renditions
                    .entry((kind.as_str(), group.as_str()))
                    .or_default()
                    .push((index, &resource.url, stable_id));
            }
        }
        let mut tracks = 0;
        let mut attached = HashSet::new();
        for resource in resources {
            let ResourceKind::Variant {
                pathway,
                stable_id,
                audio,
                subtitles,
            } = &resource.kind
            else {
                continue;
            };
            if tracks >= MAX_RESOURCES {
                break;
            }
            let group = pathways.entry(pathway.clone()).or_default();
            group.push(Track::new(&resource.url, stable_id, false));
            tracks += 1;
            for (kind, name) in [("AUDIO", audio), ("SUBTITLES", subtitles)] {
                let Some(renditions) = name
                    .as_deref()
                    .and_then(|name| renditions.get(&(kind, name)))
                else {
                    continue;
                };
                for (index, url, stable_id) in renditions {
                    if tracks >= MAX_RESOURCES {
                        break;
                    }
                    if attached.insert((pathway, index)) {
                        group.push(Track::new(url, stable_id, true));
                        tracks += 1;
                    }
                }
            }
        }
        Some(Self {
            documents: HashSet::from([steering_identity(url)]),
            pathways,
            tracks,
        })
    }

    pub fn observes(&self, url: &Url) -> bool {
        self.documents.contains(&steering_identity(url))
    }

    /// Only a document linked from the master or a previous steering response
    /// can name replacements, and only for an already declared pathway.
    pub fn learn(&mut self, url: &Url, body: &[u8]) -> Option<(Option<Url>, Vec<SteeredPlaylist>)> {
        if !self.observes(url) {
            return None;
        }
        let document: Document = serde_json::from_slice(body).ok()?;
        if document.version != 1 {
            return None;
        }
        let reload = document
            .reload
            .filter(|uri| !uri.is_empty() && uri.len() <= MAX_URI_BYTES)
            .and_then(|uri| url.join(&uri).ok())
            .filter(|url| public_url(url));
        let reload = reload.filter(|url| {
            let key = steering_identity(url);
            self.documents.contains(&key)
                || (self.documents.len() < MAX_RELOAD_URLS && self.documents.insert(key))
        });
        let mut results = Vec::new();
        for clone in document.clones {
            let Some(base) = self.pathways.get(&clone.base) else {
                continue;
            };
            if clone.id.is_empty()
                || self.pathways.contains_key(&clone.id)
                || clone
                    .replacement
                    .params
                    .iter()
                    .map(|(key, value)| key.len() + value.len() + 2)
                    .sum::<usize>()
                    > MAX_URI_BYTES
            {
                continue;
            }
            let tracks: Vec<_> = base
                .iter()
                .filter_map(|track| track.replace(&clone.replacement))
                .take(MAX_RESOURCES - self.tracks)
                .collect();
            if tracks.is_empty() {
                continue;
            }
            self.tracks += tracks.len();
            results.extend(tracks.iter().map(|track| SteeredPlaylist {
                url: track.url.clone(),
                suppress_userinfo: track.suppress_userinfo,
            }));
            self.pathways.insert(clone.id, tracks);
        }
        Some((reload, results))
    }
}

impl Track {
    fn new(url: &Url, stable_id: &Option<String>, rendition: bool) -> Self {
        Self {
            url: url.clone(),
            stable_id: stable_id.clone(),
            rendition,
            suppress_userinfo: false,
        }
    }

    fn replace(&self, replacement: &Replacement) -> Option<Self> {
        let mut result = self.clone();
        let overrides = if self.rendition {
            &replacement.renditions
        } else {
            &replacement.variants
        };
        if let Some(uri) = self.stable_id.as_ref().and_then(|id| overrides.get(id)) {
            result.url = Url::parse(uri).ok()?;
            inherit_userinfo(&self.url, &mut result.url);
            result.suppress_userinfo = false;
        } else {
            if let Some(host) = &replacement.host {
                result.url.set_host(Some(host)).ok()?;
            }
            if result.url.origin() != self.url.origin() {
                clear_userinfo(&mut result.url);
                result.suppress_userinfo = true;
            }
            if !replacement.params.is_empty() {
                let query = replaced_query(result.url.query(), &replacement.params);
                result.url.set_query(Some(&query));
            }
        }
        (result.url.as_str().len() <= MAX_URI_BYTES && public_url(&result.url)).then_some(result)
    }
}

pub fn steering_identity(url: &Url) -> Url {
    identity_ignoring_query_parameters(url, |name| {
        matches!(name, "_HLS_pathway" | "_HLS_throughput")
    })
}

fn public_url(url: &Url) -> bool {
    public_http_destination_allowed(url.scheme(), url.host_str().unwrap_or(""))
}

fn replaced_query(query: Option<&str>, params: &HashMap<String, String>) -> String {
    let mut pairs: Vec<String> = query
        .unwrap_or_default()
        .split('&')
        .filter(|p| !p.is_empty())
        .map(str::to_owned)
        .collect();
    let mut params: Vec<_> = params.iter().filter(|(key, _)| !key.is_empty()).collect();
    params.sort_by(|(a, _), (b, _)| a.encode_utf16().cmp(b.encode_utf16()));
    for (key, value) in params {
        let key = encode_component(key);
        let pair = format!("{key}={}", encode_component(value));
        let mut replaced = false;
        pairs.retain_mut(|item| {
            if item.split_once('=').map_or(item.as_str(), |(key, _)| key) != key {
                return true;
            }
            if replaced {
                return false;
            }
            item.clone_from(&pair);
            replaced = true;
            true
        });
        if !replaced {
            pairs.push(pair);
        }
    }
    pairs.join("&")
}

fn encode_component(value: &str) -> String {
    use std::fmt::Write;
    let mut encoded = String::new();
    for byte in value.bytes() {
        if byte.is_ascii_alphanumeric() || b"-_.!~*'()".contains(&byte) {
            encoded.push(char::from(byte));
        } else {
            write!(encoded, "%{byte:02X}").unwrap();
        }
    }
    encoded
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::hls::parse_playlist;

    #[test]
    fn learns_reloads_and_clones_only_declared_pathway_variants_and_renditions() {
        let base = Url::parse("https://radio.example/master.m3u8").unwrap();
        let playlist = parse_playlist(
            concat!(
                "#EXTM3U\n",
                "#EXT-X-CONTENT-STEERING:SERVER-URI=\"./steering.json\"\n",
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"radio\",NAME=\"Radio\",STABLE-RENDITION-ID=\"r1\",URI=\"audio.m3u8\"\n",
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"other\",NAME=\"Other\",URI=\"other-audio.m3u8\"\n",
                "#EXT-X-STREAM-INF:BANDWIDTH=128000,AUDIO=\"radio\",STABLE-VARIANT-ID=\"v1\"\n",
                "https://cdn.example/variant.m3u8?x=a%2fb&token=old&token=another\n",
                "#EXT-X-STREAM-INF:BANDWIDTH=128000,PATHWAY-ID=\"other\",AUDIO=\"other\"\n",
                "https://other-cdn.example/unrelated.m3u8\n"
            ),
            &base,
            None,
            true,
        ).unwrap();
        let mut steering = Steering::from_master(&playlist.resources).unwrap();
        let observed = base
            .join("steering.json?_HLS_pathway=.&_HLS_throughput=500000")
            .unwrap();
        let body = br#"{
            "VERSION":1,"RELOAD-URI":"https://steering.example/renew",
            "PATHWAY-CLONES":[{"ID":"clone","BASE-ID":".","URI-REPLACEMENT":{
                "HOST":"clone.example","PARAMS":{"token":"a + b","a":"added"},
                "PER-RENDITION-URIS":{"r1":"https://rendition.example/audio.m3u8"}
            }}]
        }"#;
        let unrelated = base.join("steering.json?token=changed").unwrap();
        assert!(steering.learn(&unrelated, body).is_none());
        let (reload, tracks) = steering.learn(&observed, body).unwrap();
        assert_eq!(
            tracks
                .iter()
                .map(|track| track.url.as_str())
                .collect::<Vec<_>>(),
            [
                "https://clone.example/variant.m3u8?x=a%2fb&token=a%20%2B%20b&a=added",
                "https://rendition.example/audio.m3u8",
            ]
        );
        assert!(tracks[0].suppress_userinfo);
        assert!(!tracks[1].suppress_userinfo);
        let reload = reload.unwrap();
        assert_eq!(reload.as_str(), "https://steering.example/renew");
        let (_, tracks) = steering.learn(&reload, br#"{
            "VERSION":1,"PATHWAY-CLONES":[
                {"ID":"private","BASE-ID":".","URI-REPLACEMENT":{"HOST":"localhost"}},
                {"ID":"unknown","BASE-ID":"absent","URI-REPLACEMENT":{"HOST":"unrelated.example"}},
                {"ID":"replacement","BASE-ID":".","URI-REPLACEMENT":{"PER-VARIANT-URIS":{"v1":"https://replacement.example/live.m3u8"}}}
            ]
        }"#).unwrap();
        assert_eq!(
            tracks
                .iter()
                .map(|track| track.url.as_str())
                .collect::<Vec<_>>(),
            [
                "https://replacement.example/live.m3u8",
                "https://radio.example/audio.m3u8",
            ]
        );
    }

    #[test]
    fn explicit_replacements_inherit_authentication_only_on_the_same_origin() {
        let url = Url::parse("https://u:p@radio.example/main.m3u8").unwrap();
        let track = Track::new(&url, &Some("v1".into()), false);
        for (destination, expected) in [
            ("https://radio.example/new.m3u8", "u"),
            ("https://cdn.example/new.m3u8", ""),
            ("https://other:pass@cdn.example/new.m3u8", "other"),
        ] {
            let replacement = Replacement {
                host: None,
                params: HashMap::new(),
                variants: HashMap::from([("v1".into(), destination.into())]),
                renditions: HashMap::new(),
            };
            assert_eq!(
                track.replace(&replacement).unwrap().url.username(),
                expected
            );
        }
    }
}
