//! Origins named by an HLS playlist, including its variable references.

use std::collections::HashMap;

use url::Url;

const MAX_VARIABLES: usize = 64;
const MAX_URI_BYTES: usize = 4096;
const MAX_RESOURCES: usize = 4096;

pub type Variables = HashMap<String, String>;

pub struct Resource {
    pub url: Url,
    /// Variant and rendition playlists can import the master's definitions.
    pub variant: bool,
}

pub struct Playlist {
    pub variables: Variables,
    pub resources: Vec<Resource>,
}

/// Match a variant to its declaration when hls.js adds live reload directives.
/// Retain the order and encoding of the station's own query parameters.
pub fn playlist_identity(url: &Url) -> Url {
    let mut key = url.clone();
    key.set_fragment(None);
    if let Some(query) = url.query() {
        let query = query
            .split('&')
            .filter(|pair| {
                let name = pair.split_once('=').map_or(*pair, |(name, _)| name);
                !is_delivery_directive(name)
            })
            .collect::<Vec<_>>()
            .join("&");
        key.set_query((!query.is_empty()).then_some(&query));
    }
    key
}

fn is_delivery_directive(name: &str) -> bool {
    matches!(name, "_HLS_msn" | "_HLS_part" | "_HLS_skip")
}

/// Parse the references relevant to playback. Malformed definitions and
/// unresolved substitutions provide no origin evidence for this response.
pub fn parse_playlist(
    body: &str,
    base: &Url,
    inherited: Option<&Variables>,
    query_variables_allowed: bool,
) -> Option<Playlist> {
    let mut lines = body.lines();
    if lines.next()? != "#EXTM3U" {
        return None;
    }
    let mut variables = Variables::new();
    let mut resources = Vec::new();
    let mut next_uri = None;
    for line in lines.map(str::trim).filter(|line| !line.is_empty()) {
        if !line.starts_with('#') {
            if let Some(variant) = next_uri.take() {
                add_resource(&mut resources, &variables, base, line, variant)?;
            }
            continue;
        }
        let Some((tag, value)) = line.split_once(':') else {
            continue;
        };
        match tag {
            "#EXTINF" => next_uri = Some(false),
            "#EXT-X-STREAM-INF" => next_uri = Some(true),
            "#EXT-X-DEFINE" => define(
                value,
                &mut variables,
                inherited,
                query_variables_allowed.then_some(base),
            )?,
            "#EXT-X-MEDIA" | "#EXT-X-I-FRAME-STREAM-INF" | "#EXT-X-IMAGE-STREAM-INF" => {
                if let Some(uri) = attribute(&quoted_attributes(value)?, "URI") {
                    add_resource(&mut resources, &variables, base, uri, true)?;
                }
            }
            "#EXT-X-KEY"
            | "#EXT-X-MAP"
            | "#EXT-X-PART"
            | "#EXT-X-PRELOAD-HINT"
            | "#EXT-X-RENDITION-REPORT"
            | "#EXT-X-SESSION-KEY"
            | "#EXT-X-SESSION-DATA" => {
                if let Some(uri) = attribute(&quoted_attributes(value)?, "URI") {
                    add_resource(&mut resources, &variables, base, uri, false)?;
                }
            }
            "#EXT-X-CONTENT-STEERING" | "#EXT-X-DATERANGE" => {
                for (key, uri) in quoted_attributes(value)? {
                    if matches!(
                        (tag, key),
                        ("#EXT-X-CONTENT-STEERING", "SERVER-URI")
                            | ("#EXT-X-DATERANGE", "X-ASSET-URI" | "X-ASSET-LIST")
                    ) {
                        add_resource(&mut resources, &variables, base, uri, false)?;
                    }
                }
            }
            _ => {}
        }
    }
    Some(Playlist {
        variables,
        resources,
    })
}

fn add_resource(
    resources: &mut Vec<Resource>,
    variables: &Variables,
    base: &Url,
    uri: &str,
    variant: bool,
) -> Option<()> {
    if resources.len() >= MAX_RESOURCES {
        return None;
    }
    let uri = expand(uri, variables)?;
    if uri.is_empty() {
        return None;
    }
    let url = base.join(&uri).ok()?;
    resources.push(Resource { url, variant });
    Some(())
}

fn define(
    list: &str,
    variables: &mut Variables,
    inherited: Option<&Variables>,
    query_source: Option<&Url>,
) -> Option<()> {
    let attributes = quoted_attributes(list)?;
    let (name, value) = match attributes.as_slice() {
        [("IMPORT", name)] => (*name, inherited?.get(*name)?.clone()),
        [("QUERYPARAM", name)] if !is_delivery_directive(name) => {
            let (_, value) = query_source?.query_pairs().find(|(key, _)| key == *name)?;
            (*name, value.into_owned())
        }
        [_, _] => {
            let name = attribute(&attributes, "NAME")?;
            let value = attribute(&attributes, "VALUE")?;
            (name, expand(value, variables)?)
        }
        _ => return None,
    };
    if !valid_name(name)
        || value.len() > MAX_URI_BYTES
        || value.contains("{$")
        || variables.len() >= MAX_VARIABLES
        || variables.contains_key(name)
    {
        return None;
    }
    variables.insert(name.to_string(), value);
    Some(())
}

fn valid_name(name: &str) -> bool {
    !name.is_empty()
        && name
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
}

fn expand(mut text: &str, variables: &Variables) -> Option<String> {
    if text.len() > MAX_URI_BYTES {
        return None;
    }
    let mut expanded = String::new();
    while let Some((prefix, reference)) = text.split_once("{$") {
        let (name, rest) = reference.split_once('}')?;
        if !valid_name(name) {
            return None;
        }
        let value = variables.get(name)?;
        if expanded.len() + prefix.len() + value.len() > MAX_URI_BYTES {
            return None;
        }
        expanded.push_str(prefix);
        expanded.push_str(value);
        text = rest;
    }
    if expanded.len() + text.len() > MAX_URI_BYTES {
        return None;
    }
    expanded.push_str(text);
    Some(expanded)
}

fn attribute<'a>(attributes: &[(&str, &'a str)], name: &str) -> Option<&'a str> {
    attributes
        .iter()
        .find(|(key, _)| *key == name)
        .map(|(_, value)| *value)
}

/// Quoted strings may contain commas. Parse actual attribute boundaries so
/// text within another attribute cannot be mistaken for a resource URI.
fn quoted_attributes(mut list: &str) -> Option<Vec<(&str, &str)>> {
    if list.len() > MAX_URI_BYTES {
        return None;
    }
    let mut quoted = Vec::new();
    while !list.is_empty() {
        let (key, value) = list.split_once('=')?;
        let key = key.trim();
        if key.is_empty() || key.contains(',') {
            return None;
        }
        if let Some(value) = value.strip_prefix('"') {
            let (value, rest) = value.split_once('"')?;
            if attribute(&quoted, key).is_some() {
                return None;
            }
            quoted.push((key, value));
            list = if rest.is_empty() {
                rest
            } else {
                rest.strip_prefix(',')?
            };
        } else {
            list = value.split_once(',').map_or("", |(_, rest)| rest);
        }
    }
    Some(quoted)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn named_and_query_variables_expand_with_playlist_url_decoding() {
        let base = Url::parse(
            "https://station.example/live.m3u8?cdn=https%3A%2F%2Fcdn.example&name=hi%2Bthere",
        )
        .unwrap();
        let body = "#EXTM3U\n#EXT-X-DEFINE:QUERYPARAM=\"cdn\"\n#EXT-X-DEFINE:QUERYPARAM=\"name\"\n#EXT-X-DEFINE:VALUE=\"{$cdn}/live\",NAME=\"base\"\n#EXT-X-MEDIA:TYPE=AUDIO,NAME=\"voice, radio\",URI=\"{$base}/{$name}.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=64000\n{$base}/primary.m3u8\n";
        let got = parse_playlist(body, &base, None, true).unwrap();
        assert!(got.resources.iter().all(|resource| resource.variant));
        assert_eq!(
            got.resources
                .iter()
                .map(|r| r.url.as_str())
                .collect::<Vec<_>>(),
            [
                "https://cdn.example/live/hi+there.m3u8",
                "https://cdn.example/live/primary.m3u8",
            ]
        );
        assert!(parse_playlist(body, &base, None, false).is_none());
    }

    #[test]
    fn a_media_playlist_must_import_parent_names_to_use_them() {
        let base = Url::parse("https://station.example/audio.m3u8").unwrap();
        let names = Variables::from([("cdn".into(), "https://cdn.example".into())]);
        let child = "#EXTM3U\n#EXT-X-DEFINE:IMPORT=\"cdn\"\n#EXT-X-KEY:METHOD=AES-128,URI=\"{$cdn}/secret\"\n#EXTINF:4,\n{$cdn}/001.ts\n";
        let got = parse_playlist(child, &base, Some(&names), false).unwrap();
        assert_eq!(got.resources[0].url.as_str(), "https://cdn.example/secret");
        assert_eq!(got.resources[1].url.as_str(), "https://cdn.example/001.ts");
        assert!(parse_playlist(child, &base, None, false).is_none());
        assert!(
            parse_playlist("#EXTM3U\n#EXTINF:4,\n{$cdn}/001.ts\n", &base, Some(&names), false).is_none()
        );
    }

    #[test]
    fn rejects_duplicate_invalid_unbound_and_oversized_definitions() {
        let base = Url::parse("https://station.example/audio.m3u8").unwrap();
        let names = Variables::from([("cdn".into(), "https://cdn.example".into())]);
        for invalid in [
            "#EXT-X-DEFINE:NAME=\"cdn\",VALUE=\"bad\"\n#EXT-X-DEFINE:IMPORT=\"cdn\"",
            "#EXT-X-DEFINE:NAME=\"cdn\",VALUE=\"one\"\n#EXT-X-DEFINE:NAME=\"cdn\",VALUE=\"two\"",
            "#EXT-X-DEFINE:NAME=\"bad.name\",VALUE=\"https://cdn.example\"",
            "#EXT-X-DEFINE:QUERYPARAM=\"missing\"",
            "#EXT-X-DEFINE:IMPORT=\"missing\"",
            "#EXT-X-DEFINE:NAME=\"cdn\",VALUE=\"{$unbound}\"",
            "#EXT-X-DEFINE:NAME=\"cdn\",VALUE=\"unterminated",
            "#EXTINF:4,\nhttps://{$unclosed",
        ] {
            let body = format!("#EXTM3U\n{invalid}\n");
            assert!(
                parse_playlist(&body, &base, Some(&names), true).is_none(),
                "{invalid}"
            );
        }
        let large = "x".repeat(2300);
        let body = format!(
            "#EXTM3U\n#EXT-X-DEFINE:NAME=\"x\",VALUE=\"{large}\"\n#EXTINF:4,\n{{$x}}{{$x}}\n"
        );
        assert!(parse_playlist(&body, &base, None, true).is_none());
    }

    #[test]
    fn only_hls_reload_directives_are_removed_from_variant_identity() {
        let url = Url::parse("https://cdn.example/media.m3u8?x=a%2fb&_HLS_msn=9&x=b+z&_HLS_part=2&_HLS_skip=YES#fragment").unwrap();
        assert_eq!(
            playlist_identity(&url).as_str(),
            "https://cdn.example/media.m3u8?x=a%2fb&x=b+z"
        );
    }
}
