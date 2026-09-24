const assert = require("node:assert/strict");
const test = require("node:test");
const { createHarness, deferred, flush, Element } = require("./frontend-harness.cjs");

function option(value, label) {
  const el = new Element("option");
  el.value = value;
  el.textContent = label;
  return el;
}

function seedSelects(h) {
  h.el("#browse-country").append(option("", "Any country"));
  h.el("#browse-tag").append(option("", "Any genre"));
  h.el("#browse-codec").append(
    option("", "Any format"), option("MP3", "MP3"), option("FLAC", "FLAC")
  );
  h.el("#browse-bitrate").append(
    option("", "Any bitrate"), option("128", "128k"), option("320", "320k")
  );
}

function directoryStation(name, url, votes = 0) {
  return { name, url, votes, country: "", tags: "", favicon: "", tag: "" };
}

function browseHarness(stationReply) {
  return createHarness({ invoke(command, args) {
    if (command === "browse_countries" || command === "browse_tags") return [];
    if (command === "browse_stations") return stationReply(args);
    return undefined;
  } });
}

test("More pages the submitted text query rather than an unsubmitted edit", async () => {
  const requests = [];
  const h = browseHarness(({ query }) => {
    requests.push({ name: query.name, offset: query.offset });
    return requests.length === 1
      ? { offered: 40, hasMore: true, stations: [directoryStation("A one", "https://a.test/1")] }
      : { offered: 1, hasMore: false, stations: [directoryStation("A two", "https://a.test/2")] };
  });
  seedSelects(h);
  h.el("#browse-query").value = "A";
  await h.evaluate("browseSearch(false)");
  h.el("#browse-query").value = "B";
  await h.el("#browse-list").children.at(-1).children[0].dispatch("click");

  assert.deepEqual(requests, [{ name: "A", offset: 0 }, { name: "A", offset: 40 }]);
  assert.equal(h.evaluate("JSON.stringify(browseResults.map(station => station.name))"), '["A one","A two"]');
});

test("a cleaned-empty page retains More when the backend says another page exists", async () => {
  let request = 0;
  const h = browseHarness(() => ++request === 1
    ? { offered: 40, hasMore: true, stations: [] }
    : { offered: 1, hasMore: false, stations: [directoryStation("Later", "https://later.test/live")] });
  seedSelects(h);
  await h.evaluate("browseSearch(false)");

  assert.equal(h.evaluate("browseMore"), true);
  assert.equal(h.el("#browse-note").textContent, "Nothing usable was on this page — try More stations.");
  const more = h.el("#browse-list").children.at(-1).children[0];
  assert.equal(more.textContent, "More stations");
  await more.dispatch("click");
  assert.equal(h.evaluate("browseResults[0].name"), "Later");
});

test("legacy page replies cannot paginate beyond the explicit offset cap", async () => {
  const h = browseHarness(({ query }) => {
    assert.equal(query.offset, 1000);
    return { offered: 40, stations: [directoryStation("Cap", "https://cap.test/live")] };
  });
  seedSelects(h);
  h.evaluate('browseName = "cap"; browseOffset = 1000; browseMore = true');
  await h.evaluate("browseSearch(true)");
  assert.equal(h.evaluate("browseMore"), false);
});

test("later pages replace duplicate streams only when their submission has more votes", async () => {
  let request = 0;
  const h = browseHarness(() => ++request === 1
    ? { offered: 40, hasMore: true, stations: [directoryStation("Low vote", "https://same.test/live", 1)] }
    : { offered: 40, hasMore: false, stations: [directoryStation("Popular", "http://same.test/live/", 11)] });
  seedSelects(h);
  await h.evaluate("browseSearch(false)");
  await h.el("#browse-list").children.at(-1).children[0].dispatch("click");

  assert.equal(h.evaluate("browseResults.length"), 1);
  assert.equal(h.evaluate("browseResults[0].name"), "Popular");
  assert.equal(h.evaluate("browseResults[0].votes"), 11);
});

test("a failed More request keeps its retry and offset", async () => {
  let request = 0;
  const h = browseHarness(() => {
    request += 1;
    if (request === 1) return {
      offered: 40, hasMore: true, stations: [directoryStation("First", "https://first.test/live")],
    };
    if (request === 2) throw new Error("temporary outage");
    return { offered: 1, hasMore: false, stations: [directoryStation("Second", "https://second.test/live")] };
  });
  seedSelects(h);
  await h.evaluate("browseSearch(false)");
  await h.el("#browse-list").children.at(-1).children[0].dispatch("click");

  assert.equal(h.evaluate("browseOffset"), 40);
  assert.equal(h.evaluate("browseMore"), true);
  const retry = h.el("#browse-list").children.at(-1).children[0];
  assert.equal(retry.textContent, "More stations");
  await retry.dispatch("click");
  assert.equal(h.evaluate("browseResults.length"), 2);
});

test("retrying a failed More from an empty page keeps its offset and submitted name", async () => {
  const requests = [];
  const h = browseHarness(({ query }) => {
    requests.push({ name: query.name, offset: query.offset });
    if (requests.length === 1) return { offered: 40, hasMore: true, stations: [] };
    if (requests.length === 2) throw new Error("temporary page outage");
    return {
      offered: 1, hasMore: false,
      stations: [directoryStation("Later", "https://later.test/live")],
    };
  });
  seedSelects(h);
  h.el("#browse-query").value = "ambient radio";
  await h.evaluate("browseSearch(false)");
  assert.equal(h.evaluate("browseOffset"), 40);
  assert.equal(h.evaluate("browseMore"), true);
  assert.equal(h.el("#browse-list").children[0].textContent, "Nothing usable was on this page.");

  await h.el("#browse-list").children.at(-1).children[0].dispatch("click");
  assert.equal(h.evaluate("browseOffset"), 40);
  assert.equal(h.evaluate("browseMore"), true);
  const retry = h.el("#browse-list").querySelector(".browse-retry");
  assert.ok(retry);
  h.el("#browse-query").value = "unsubmitted draft";
  await retry.dispatch("click");

  assert.deepEqual(requests, [
    { name: "ambient radio", offset: 0 },
    { name: "ambient radio", offset: 40 },
    { name: "ambient radio", offset: 40 },
  ]);
  assert.equal(h.evaluate("browseOffset"), 41);
  assert.equal(h.evaluate("browseResults[0].name"), "Later");
  assert.equal(h.el("#browse-query").value, "unsubmitted draft");
});

test("More appends new rows without moving the old countryless tail", async () => {
  const requests = [];
  const station = (name, url, country, votes = 0) => ({ ...directoryStation(name, url, votes), country });
  const h = browseHarness(({ query }) => {
    requests.push(query.offset);
    if (query.offset === 0) return {
      offered: 40, hasMore: true, stations: [
        station("Avant", "https://avant.test/live", ""),
        station("Bitlis", "https://bitlis.test/live", "Türkiye", 1),
      ],
    };
    if (query.offset === 40) return {
      offered: 40, hasMore: true, stations: [
        station("Drone", "https://drone.test/live", ""),
        station("France", "https://france.test/live", "France"),
        station("Bitlis updated", "http://bitlis.test/live/", "Türkiye", 10),
        station("Avant", "http://avant.test/live/", ""),
      ],
    };
    return {
      offered: 1, hasMore: false, stations: [
        station("Australia", "https://australia.test/live", "Australia"),
      ],
    };
  });
  seedSelects(h);
  await h.evaluate("browseSearch(false)");
  assert.equal(h.evaluate("JSON.stringify(browseResults.map(s => s.name))"), '["Bitlis","Avant"]');
  await h.el("#browse-list").children.at(-1).children[0].dispatch("click");
  assert.equal(h.evaluate("JSON.stringify(browseResults.map(s => s.name))"), '["Bitlis updated","Avant","France","Drone"]');
  await h.el("#browse-list").children.at(-1).children[0].dispatch("click");
  assert.equal(h.evaluate("JSON.stringify(browseResults.map(s => s.name))"), '["Bitlis updated","Avant","France","Drone","Australia"]');
  assert.deepEqual(requests, [0, 40, 80]);
});

test("Clear all supersedes pending search and facet replies with one unfiltered request", async () => {
  const stalePage = deferred();
  const staleFacets = deferred();
  const stationQueries = [];
  const h = createHarness({ invoke(command, args) {
    if (command === "browse_countries") return [];
    if (command === "browse_tags") return [];
    if (command === "browse_facets") {
      return args.query.countryCode ? staleFacets.promise : {
        countries: [], tags: [], codecs: [], bitrates: [], sampled: false,
      };
    }
    if (command === "browse_stations") {
      stationQueries.push(JSON.parse(JSON.stringify(args.query)));
      return stationQueries.length === 1
        ? stalePage.promise
        : { offered: 0, hasMore: false, stations: [] };
    }
    return undefined;
  } });
  seedSelects(h);
  h.evaluate(`browseCountries = []; browseTags = [];
    browseCountry = "FR"; browseCountryLabel = "France";
    browseTag = "jazz"; browseTagLabel = "Jazz"; browseCodec = "MP3"; browseBitrate = "128"`);
  h.el("#browse-query").value = "stale query";
  h.el("#browse-quality").open = true;
  h.evaluate("wire()");
  const oldSearch = h.evaluate("browseSearch(false)");
  await flush();

  await h.el("#browse-reset").dispatch("click");
  assert.equal(stationQueries.length, 2);
  assert.deepEqual(stationQueries[1], {
    name: "", tag: "", countryCode: "", codec: "", bitrate: "", limit: 40, offset: 0,
  });
  assert.equal(h.el("#browse-query").value, "");
  assert.equal(h.el("#browse-quality").open, true);
  assert.equal(h.el("#browse-quality-summary").textContent, "Any");

  stalePage.resolve({ offered: 40, hasMore: true, stations: [directoryStation("Stale", "https://stale.test/live")] });
  staleFacets.resolve({
    countries: [{ code: "FR", name: "France", stations: 20 }],
    tags: [{ value: "jazz", name: "Jazz", stations: 20 }],
    codecs: [{ key: "MP3", stations: 20 }], bitrates: [{ key: "128", stations: 20 }], sampled: false,
  });
  await oldSearch;
  await flush();
  assert.equal(h.evaluate("browseResults.length"), 0);
  assert.equal(h.evaluate("browseMore"), false);
  assert.equal(h.el("#browse-active-filters").children.length, 0);
  assert.deepEqual(h.el("#browse-country").options.map((entry) => entry.value), [""]);
  assert.equal(h.el("#browse-codec").options.find((entry) => entry.value === "MP3").textContent, "MP3");
  assert.equal(stationQueries.length, 2);
});

test("removing one filter preserves the submitted query, its draft, and other filters", async () => {
  const stationQueries = [];
  const h = createHarness({ invoke(command, args) {
    if (command === "browse_countries" || command === "browse_tags") return [];
    if (command === "browse_facets") return {
      countries: [], tags: [], codecs: [], bitrates: [], sampled: false,
    };
    if (command === "browse_stations") {
      stationQueries.push(JSON.parse(JSON.stringify(args.query)));
      return { offered: 0, hasMore: false, stations: [] };
    }
    return undefined;
  } });
  seedSelects(h);
  h.el("#browse-country").append(option("FR", "France"));
  h.el("#browse-tag").append(option("jazz", "Jazz"));
  h.el("#browse-country").options[1].dataset.label = "France";
  h.el("#browse-tag").options[1].dataset.label = "Jazz";
  h.el("#browse-country").value = "FR";
  h.el("#browse-tag").value = "jazz";
  h.el("#browse-codec").value = "MP3";
  h.el("#browse-bitrate").value = "128";
  h.el("#browse-query").value = "draft text";
  h.evaluate(`browseCountries = []; browseTags = []; browseName = "submitted";
    browseCountry = "FR"; browseCountryLabel = "France";
    browseTag = "jazz"; browseTagLabel = "Jazz"; browseCodec = "MP3"; browseBitrate = "128";
    renderBrowseFilters()`);
  h.evaluate("wire()");

  const codecChip = h.el("#browse-active-filters").children.find((chip) => chip.dataset.filter === "codec");
  assert.ok(codecChip);
  await codecChip.dispatch("click");

  assert.equal(stationQueries.length, 1);
  assert.equal(stationQueries[0].name, "submitted");
  assert.equal(stationQueries[0].countryCode, "FR");
  assert.equal(stationQueries[0].tag, "jazz");
  assert.equal(stationQueries[0].codec, "");
  assert.equal(stationQueries[0].bitrate, "128");
  assert.equal(h.el("#browse-query").value, "draft text");
  assert.equal(h.evaluate("browseName"), "submitted");
});

test("a failed first search offers a retry for the submitted query", async () => {
  const stationQueries = [];
  const h = createHarness({ invoke(command, args) {
    if (command === "browse_countries" || command === "browse_tags") return [];
    if (command === "browse_facets") return {
      countries: [], tags: [], codecs: [], bitrates: [], sampled: false,
    };
    if (command === "browse_stations") {
      stationQueries.push(JSON.parse(JSON.stringify(args.query)));
      if (stationQueries.length === 1) throw new Error("directory unavailable");
      return { offered: 1, hasMore: false, stations: [directoryStation("Jazz FM", "https://jazz.test/live")] };
    }
    return undefined;
  } });
  seedSelects(h);
  h.evaluate("browseCountries = []; browseTags = []");
  h.el("#browse-query").value = "jazz";
  h.evaluate("wire()");
  await h.evaluate("browseSearch(false)");

  const retry = h.el("#browse-list").querySelector(".browse-retry");
  assert.ok(retry);
  assert.match(h.evaluate("browseError"), /directory unavailable/);
  h.el("#browse-query").value = "unsubmitted draft";
  await retry.dispatch("click");

  assert.deepEqual(stationQueries.map((query) => query.name), ["jazz", "jazz"]);
  assert.equal(h.evaluate("browseError"), "");
  assert.equal(h.evaluate("browseResults[0].name"), "Jazz FM");
  assert.equal(h.el("#browse-query").value, "unsubmitted draft");
});

test("Browse count reports loaded rows rather than stations offered by the directory", async () => {
  const nextPage = deferred();
  let requests = 0;
  const h = createHarness({ invoke(command) {
    if (command === "browse_countries" || command === "browse_tags") return [];
    if (command === "browse_facets") return {
      countries: [], tags: [], codecs: [], bitrates: [], sampled: false,
    };
    if (command === "browse_stations") {
      requests += 1;
      if (requests === 1) return {
        offered: 40, hasMore: true, stations: [directoryStation("First", "https://first.test/live")],
      };
      return nextPage.promise;
    }
    return undefined;
  } });
  seedSelects(h);
  h.evaluate("browseCountries = []; browseTags = []");
  await h.evaluate("browseSearch(false)");
  assert.equal(h.el("#browse-count").textContent, "1 station loaded");

  const more = h.el("#browse-list").children.at(-1).children[0];
  const pending = more.dispatch("click");
  await flush();
  assert.equal(requests, 2);
  const loadingMore = h.el("#browse-list").children.at(-1).children[0];
  assert.equal(loadingMore.disabled, true);
  assert.match(loadingMore.textContent, /Loading/);
  await loadingMore.dispatch("click");
  assert.equal(requests, 2);

  nextPage.resolve({ offered: 40, hasMore: false, stations: [directoryStation("Second", "https://second.test/live")] });
  await pending;
  assert.equal(h.el("#browse-count").textContent, "2 stations loaded");
});

test("More restores focus to a new Listen button unless the user moved away", async () => {
  const secondPage = deferred();
  const thirdPage = deferred();
  const offsets = [];
  const h = browseHarness(({ query }) => {
    offsets.push(query.offset);
    if (offsets.length === 1) return {
      offered: 40, hasMore: true, stations: [directoryStation("First", "https://first.test/live")],
    };
    return offsets.length === 2 ? secondPage.promise : thirdPage.promise;
  });
  seedSelects(h);
  await h.evaluate("browseSearch(false)");

  const more = h.el("#browse-list").querySelector(".browse-more");
  more.focus();
  const loadingSecond = more.dispatch("click");
  await flush();
  assert.deepEqual(offsets, [0, 40]);
  secondPage.resolve({
    offered: 40, hasMore: true, stations: [directoryStation("Second", "https://second.test/live")],
  });
  await loadingSecond;

  const rows = h.el("#browse-list").children.filter((entry) => entry.classList.contains("browse-row"));
  assert.equal(rows.length, 2);
  assert.equal(rows[1].querySelector(".name").querySelector("b").textContent, "Second");
  assert.equal(h.document.activeElement, rows[1].querySelector(".browse-listen"));

  const moreAgain = h.el("#browse-list").querySelector(".browse-more");
  moreAgain.focus();
  const loadingThird = moreAgain.dispatch("click");
  await flush();
  h.el("#browse-query").focus();
  thirdPage.resolve({
    offered: 1, hasMore: false, stations: [directoryStation("Third", "https://third.test/live")],
  });
  await loadingThird;

  assert.deepEqual(offsets, [0, 40, 80]);
  assert.equal(h.document.activeElement, h.el("#browse-query"));
});

test("submitted name and selected filters produce matching counts and empty state", async () => {
  const facetQueries = [];
  const stationQueries = [];
  const h = createHarness({ invoke(command, args) {
    if (command === "browse_facets") {
      const query = JSON.parse(JSON.stringify(args.query));
      facetQueries.push(query);
      const codecIsOmitted = query.countryCode === "DE" && !query.codec;
      return {
        countries: query.codec ? [] : [{ code: "DE", name: "Germany", stations: 111 }],
        tags: query.codec ? [] : [{ value: "techno", name: "Techno", stations: 111 }],
        codecs: codecIsOmitted
          ? [{ key: "MP3", stations: 103 }, { key: "OGG", stations: 0 }]
          : [],
        bitrates: [],
        sampled: false,
      };
    }
    if (command === "browse_stations") {
      stationQueries.push(JSON.parse(JSON.stringify(args.query)));
      return { offered: 0, hasMore: false, stations: [] };
    }
    return undefined;
  } });
  seedSelects(h);
  h.el("#browse-codec").append(option("OGG", "OGG"));
  h.el("#browse-country").value = "DE";
  h.el("#browse-codec").value = "OGG";
  h.el("#browse-query").value = "techno";
  h.evaluate(`browseCountries = [{code:"DE",name:"Germany",stations:50}];
    browseTags = [{value:"techno",name:"Techno",stations:80}];
    browseCountry = "DE"; browseCountryLabel = "Germany"; browseCodec = "OGG"`);

  await h.evaluate("browseSearch(false)");
  await h.evaluate("loadBrowseFilters()");

  assert.equal(facetQueries.every((query) => query.name === "techno"), true);
  assert.equal(facetQueries.some((query) => query.countryCode === "DE" && !query.codec), true);
  assert.equal(facetQueries.some((query) => query.codec === "OGG" && !query.countryCode), true);
  assert.equal(facetQueries.some((query) => query.countryCode === "DE" && query.codec === "OGG"), true);
  assert.equal(h.el("#browse-country").selectedOptions[0].textContent, "Germany (0)");
  assert.equal(h.el("#browse-country").selectedOptions[0].disabled, false);
  assert.equal(h.el("#browse-codec").selectedOptions[0].textContent, "OGG (0)");
  assert.equal(h.el("#browse-codec").selectedOptions[0].disabled, false);
  assert.equal(h.el("#browse-list").children[0].textContent, "No stations match this search.");
  assert.equal(h.el("#browse-note").textContent, "Nothing in the directory matches that.");
  assert.equal(stationQueries[0].name, "techno");
  assert.equal(stationQueries[0].countryCode, "DE");
  assert.equal(stationQueries[0].codec, "OGG");
});

test("submitted name reaches every facet with AAC and 192k selected", async () => {
  const facetQueries = [];
  const h = createHarness({ invoke(command, args) {
    if (command === "browse_facets") {
      const query = JSON.parse(JSON.stringify(args.query));
      facetQueries.push(query);
      return {
        countries: [],
        tags: [],
        codecs: query.codec ? [] : [{ key: "AAC", stations: 0 }],
        bitrates: query.bitrate ? [] : [{ key: "192", stations: 0 }],
        sampled: false,
      };
    }
    if (command === "browse_stations") return { offered: 0, hasMore: false, stations: [] };
    return undefined;
  } });
  seedSelects(h);
  h.el("#browse-codec").append(option("AAC", "AAC"));
  h.el("#browse-bitrate").append(option("192", "192k"));
  h.el("#browse-country").value = "DE";
  h.el("#browse-codec").value = "AAC";
  h.el("#browse-bitrate").value = "192";
  h.el("#browse-query").value = "techno";
  h.evaluate(`browseCountries = [{code:"DE",name:"Germany",stations:50}]; browseTags = [];
    browseCountry = "DE"; browseCountryLabel = "Germany";
    browseCodec = "AAC"; browseBitrate = "192"`);

  await h.evaluate("browseSearch(false)");
  await h.evaluate("loadBrowseFilters()");

  assert.equal(facetQueries.every((query) => query.name === "techno"), true);
  assert.equal(facetQueries.some((query) => !query.countryCode && query.codec === "AAC" && query.bitrate === "192"), true);
  assert.equal(facetQueries.some((query) => query.countryCode === "DE" && !query.codec && query.bitrate === "192"), true);
  assert.equal(facetQueries.some((query) => query.countryCode === "DE" && query.codec === "AAC" && !query.bitrate), true);
  assert.equal(h.el("#browse-country").selectedOptions[0].textContent, "Germany (0)");
  assert.equal(h.el("#browse-codec").selectedOptions[0].textContent, "AAC (0)");
  assert.equal(h.el("#browse-bitrate").selectedOptions[0].textContent, "192k (0)");
  assert.equal(h.el("#browse-country").selectedOptions[0].disabled, false);
  assert.equal(h.el("#browse-codec").selectedOptions[0].disabled, false);
  assert.equal(h.el("#browse-bitrate").selectedOptions[0].disabled, false);
  assert.equal(h.el("#browse-list").children[0].textContent, "No stations match this search.");
});

test("a filter change commits the edited name before it refreshes counts", async () => {
  const facetQueries = [];
  const h = createHarness({ invoke(command, args) {
    if (command === "browse_facets") {
      facetQueries.push(JSON.parse(JSON.stringify(args.query)));
      return { countries: [], tags: [], codecs: [], bitrates: [], sampled: false };
    }
    if (command === "browse_stations") return { offered: 0, hasMore: false, stations: [] };
    return undefined;
  } });
  seedSelects(h);
  h.el("#browse-country").append(option("DE", "Germany"));
  h.el("#browse-country").value = "DE";
  h.el("#browse-query").value = "techno";
  h.evaluate(`browseName = "old";
    browseCountries = [{code:"DE",name:"Germany",stations:50}]; browseTags = [];
    wire()`);

  await h.el("#browse-country").dispatch("change");
  await h.evaluate("loadBrowseFilters()");

  assert.equal(facetQueries.length > 0, true);
  assert.equal(facetQueries.every((query) => query.name === "techno"), true);
  assert.equal(facetQueries.some((query) => query.name === "old"), false);
});

test("a name-only search narrows every filter from one shared facet tally", async () => {
  const facetQueries = [];
  const h = createHarness({ invoke(command, args) {
    if (command === "browse_facets") {
      facetQueries.push(JSON.parse(JSON.stringify(args.query)));
      return {
        countries: [{ code: "DE", name: "Germany", stations: 111 }],
        tags: [{ value: "techno", name: "Techno", stations: 111 }],
        codecs: [{ key: "MP3", stations: 103 }, { key: "FLAC", stations: 0 }],
        bitrates: [{ key: "128", stations: 17 }, { key: "320", stations: 2 }],
        sampled: false,
      };
    }
    if (command === "browse_stations") return { offered: 0, hasMore: false, stations: [] };
    return undefined;
  } });
  seedSelects(h);
  h.el("#browse-query").value = "techno";

  await h.evaluate("browseSearch(false)");
  await h.evaluate("loadBrowseFilters()");

  assert.deepEqual(facetQueries, [{ name: "techno" }]);
  assert.equal(h.el("#browse-country").options[1].textContent, "Germany (111)");
  assert.equal(h.el("#browse-tag").options[1].textContent, "Techno (111)");
  assert.equal(h.el("#browse-codec").options[1].textContent, "MP3 (103)");
  assert.equal(h.el("#browse-codec").options[2].textContent, "FLAC (0)");
  assert.equal(h.el("#browse-bitrate").options[1].textContent, "128k (17)");
  assert.equal(h.el("#browse-bitrate").options[2].textContent, "320k (2)");
});

test("unsubmitted name edits leave counts and More alone, while clearing restores globals", async () => {
  const facetQueries = [];
  const stationQueries = [];
  const h = createHarness({ invoke(command, args) {
    if (command === "browse_facets") {
      facetQueries.push(JSON.parse(JSON.stringify(args.query)));
      return {
        countries: [{ code: "DE", name: "Germany", stations: 111 }],
        tags: [{ value: "techno", name: "Techno", stations: 111 }],
        codecs: [{ key: "MP3", stations: 103 }],
        bitrates: [{ key: "128", stations: 17 }], sampled: false,
      };
    }
    if (command === "browse_stations") {
      stationQueries.push({ name: args.query.name, offset: args.query.offset });
      return args.query.offset === 0 && args.query.name
        ? { offered: 40, hasMore: true, stations: [directoryStation("Techno", "https://techno.test/live")] }
        : { offered: 0, hasMore: false, stations: [] };
    }
    return undefined;
  } });
  seedSelects(h);
  h.evaluate(`browseCountries = [{code:"DE",name:"Germany",stations:50}];
    browseTags = [{value:"techno",name:"Techno",stations:80}]`);
  h.el("#browse-query").value = "techno";
  await h.evaluate("browseSearch(false)");
  await h.evaluate("loadBrowseFilters()");
  assert.equal(h.el("#browse-country").options[1].textContent, "Germany (111)");

  h.el("#browse-query").value = "house";
  await h.evaluate("browseSearch(true)");
  assert.deepEqual(stationQueries.slice(0, 2), [
    { name: "techno", offset: 0 }, { name: "techno", offset: 40 },
  ]);
  assert.deepEqual(facetQueries, [{ name: "techno" }]);
  assert.equal(h.el("#browse-country").options[1].textContent, "Germany (111)");

  h.el("#browse-query").value = "";
  await h.evaluate("browseSearch(false)");
  await h.evaluate("loadBrowseFilters()");
  assert.deepEqual(stationQueries.at(-1), { name: "", offset: 0 });
  assert.deepEqual(facetQueries, [{ name: "techno" }]);
  assert.equal(h.el("#browse-country").options[1].textContent, "Germany (50)");
  assert.equal(h.el("#browse-tag").options[1].textContent, "Techno (80)");
  assert.equal(h.el("#browse-codec").options[1].textContent, "MP3");
  assert.equal(h.el("#browse-bitrate").options[1].textContent, "128k");
});

test("changed queries clear stale counts and reject delayed facets while global lists load", async () => {
  const countries = deferred();
  const tags = deferred();
  const oldFacets = deferred();
  const newFacets = deferred();
  const h = createHarness({ invoke(command, args) {
    if (command === "browse_countries") return countries.promise;
    if (command === "browse_tags") return tags.promise;
    if (command === "browse_facets") {
      if (args.query.name === "old") return oldFacets.promise;
      if (args.query.name === "new") return newFacets.promise;
    }
    return undefined;
  } });
  seedSelects(h);
  const loading = h.evaluate('browseName = "old"; loadBrowseFilters()');
  oldFacets.resolve({
    countries: [{ code: "OLD", name: "Old count", stations: 9 }],
    tags: [{ value: "old", name: "Old tag", stations: 9 }],
    codecs: [{ key: "MP3", stations: 9 }],
    bitrates: [{ key: "128", stations: 9 }], sampled: false,
  });
  await flush();
  assert.equal(h.el("#browse-country").options[1].textContent, "Old count (9)");
  assert.equal(h.el("#browse-codec").options[1].textContent, "MP3 (9)");

  h.evaluate('browseName = "new"; loadBrowseFilters()');
  assert.deepEqual(h.el("#browse-country").options.map((entry) => entry.textContent), ["Any country"]);
  assert.equal(h.el("#browse-country").disabled, true);
  assert.equal(h.el("#browse-codec").options[1].textContent, "MP3");
  assert.equal(h.el("#browse-codec").disabled, true);

  h.evaluate('browseName = ""; loadBrowseFilters()');
  assert.deepEqual(h.el("#browse-country").options.map((entry) => entry.textContent), ["Any country"]);
  assert.equal(h.el("#browse-country").disabled, true);
  assert.equal(h.el("#browse-codec").disabled, false);
  newFacets.resolve({
    countries: [{ code: "NEW", name: "New count", stations: 7 }],
    tags: [{ value: "new", name: "New tag", stations: 7 }],
    codecs: [{ key: "MP3", stations: 7 }],
    bitrates: [{ key: "128", stations: 7 }], sampled: false,
  });
  await flush();
  assert.deepEqual(h.el("#browse-country").options.map((entry) => entry.textContent), ["Any country"]);
  assert.equal(h.el("#browse-codec").options[1].textContent, "MP3");

  countries.resolve([{ code: "DE", name: "Germany", stations: 50 }]);
  tags.resolve([{ value: "techno", name: "Techno", stations: 80 }]);
  await loading;
  assert.deepEqual(h.el("#browse-country").options.map((entry) => entry.textContent), ["Any country", "Germany (50)"]);
  assert.deepEqual(h.el("#browse-tag").options.map((entry) => entry.textContent), ["Any genre", "Techno (80)"]);
});

test("sampled zero format, bitrate and selected dynamic buckets remain selectable", () => {
  const h = createHarness();
  seedSelects(h);
  h.evaluate(`annotateFixed("#browse-codec", [{key:"MP3",stations:5000}], true);
    annotateFixed("#browse-bitrate", [{key:"128",stations:5000}], true)`);
  assert.equal(h.el("#browse-codec").options[2].disabled, false);
  assert.equal(h.el("#browse-bitrate").options[2].disabled, false);
  assert.equal(h.el("#browse-codec").options[2].textContent, "FLAC (0+)");
  assert.equal(h.el("#browse-bitrate").options[2].textContent, "320k (0+)");

  h.evaluate(`annotateFixed("#browse-codec", [{key:"MP3",stations:10}], false);
    annotateFixed("#browse-bitrate", [{key:"128",stations:10}], false)`);
  assert.equal(h.el("#browse-codec").options[2].disabled, true);
  assert.equal(h.el("#browse-bitrate").options[2].disabled, true);

  h.evaluate(`setBrowseOptions("#browse-country", [], asCountry, "DE", "Germany", true)`);
  assert.equal(h.el("#browse-country").selectedOptions[0].textContent, "Germany (0+)");
  assert.equal(h.el("#browse-country").selectedOptions[0].disabled, false);
});

test("a failed facet update replaces stale counts with uncounted global choices", async () => {
  const h = createHarness({ invoke(command, args) {
    if (command !== "browse_facets") return undefined;
    if (args.query.countryCode === "FR") return {
      tags: [{ value: "jazz", name: "Jazz", stations: 10 }], countries: [],
      codecs: [{ key: "MP3", stations: 10 }], bitrates: [{ key: "128", stations: 10 }], sampled: false,
    };
    if (args.query.countryCode === "US") throw new Error("temporary facet outage");
    return { tags: [], countries: [], codecs: [], bitrates: [], sampled: false };
  } });
  seedSelects(h);
  h.evaluate(`browseTags = [{value:"jazz",name:"Jazz",stations:50},{value:"rock",name:"Rock",stations:40}];
    browseCountries = [{code:"FR",name:"France",stations:100},{code:"US",name:"United States",stations:100}];
    browseCountry = "FR"`);
  await h.evaluate("refreshBrowseFilters()");
  assert.equal(h.el("#browse-tag").options[1].textContent, "Jazz (10)");

  h.evaluate('browseCountry = "US"');
  await h.evaluate("refreshBrowseFilters()");
  assert.deepEqual(h.el("#browse-tag").options.map((entry) => entry.textContent), ["Any genre", "Jazz", "Rock"]);
  assert.equal(h.el("#browse-tag").disabled, false);
});

test("missing initial lists retry independently and concurrent entry points coalesce", async () => {
  const retry = deferred();
  let countryCalls = 0;
  let tagCalls = 0;
  const h = createHarness({ invoke(command) {
    if (command === "browse_countries") {
      countryCalls += 1;
      if (countryCalls === 1) throw new Error("first country failure");
      return retry.promise;
    }
    if (command === "browse_tags") {
      tagCalls += 1;
      return [{ value: "jazz", name: "Jazz", stations: 4 }];
    }
    if (command === "browse_stations") return { offered: 0, hasMore: false, stations: [] };
    return undefined;
  } });
  seedSelects(h);
  await h.evaluate("loadBrowseFilters()");
  assert.equal(countryCalls, 1);
  assert.equal(tagCalls, 1);

  h.evaluate("browseLooked = true; browseFirstLook()");
  const search = h.evaluate("browseSearch(false)");
  assert.equal(countryCalls, 2);
  assert.equal(tagCalls, 1);
  retry.resolve([{ code: "US", name: "United States", stations: 5 }]);
  await search;
  await flush();
  assert.equal(countryCalls, 2);
  assert.deepEqual(h.el("#browse-country").options.map((entry) => entry.value), ["", "US"]);
});

test("clearing a fixed filter removes a narrowed subset when its global list is unavailable", async () => {
  const h = createHarness({ invoke(command) {
    if (command === "browse_facets") return {
      tags: [{ value: "jazz", name: "Jazz", stations: 3 }],
      countries: [{ code: "US", name: "United States", stations: 3 }],
      codecs: [{ key: "MP3", stations: 3 }], bitrates: [], sampled: false,
    };
    return undefined;
  } });
  seedSelects(h);
  h.evaluate('browseTags = null; browseCountries = null; browseCodec = "MP3"');
  await h.evaluate("refreshBrowseFilters()");
  assert.deepEqual(h.el("#browse-country").options.map((entry) => entry.value), ["", "US"]);

  h.evaluate('browseCodec = ""');
  await h.evaluate("refreshBrowseFilters()");
  assert.deepEqual(h.el("#browse-country").options.map((entry) => entry.value), [""]);
  assert.deepEqual(h.el("#browse-tag").options.map((entry) => entry.value), [""]);
});

test("Browse Save stays pending until persistence and becomes retryable after failure", async () => {
  const first = deferred();
  let saves = 0;
  const h = createHarness({ invoke(command) {
    if (command === "save_stations") return ++saves === 1 ? first.promise : null;
    return undefined;
  } });
  h.evaluate(`browseResults = [{name:"Candidate",url:"https://candidate.test/live",country:"",tags:"",tag:""}]; renderBrowse()`);
  const pending = h.evaluate("addBrowseStation(browseResults[0])");
  await flush();
  let add = h.el("#browse-list").children[0].querySelector(".browse-save");
  assert.equal(h.evaluate("state.stations.length"), 0);
  assert.equal(add.textContent, "Saving…");
  assert.equal(add.disabled, true);
  assert.equal(h.evaluate("addBrowseStation(browseResults[0])"), undefined);
  assert.equal(saves, 1);

  first.reject(new Error("disk full"));
  assert.equal(await pending, false);
  add = h.el("#browse-list").children[0].querySelector(".browse-save");
  assert.equal(h.evaluate("state.stations.length"), 0);
  assert.equal(add.textContent, "+ Save");
  assert.equal(add.disabled, false);

  assert.equal(await h.evaluate("addBrowseStation(browseResults[0])"), true);
  add = h.el("#browse-list").children[0].querySelector(".browse-save");
  assert.equal(h.evaluate("state.stations.length"), 1);
  assert.equal(add.textContent, "Saved");
  assert.equal(saves, 2);
});

test("a failed pending Add cannot leak into an ordinary queued station save", async () => {
  const addWrite = deferred();
  let saves = 0;
  const h = createHarness({ invoke(command) {
    if (command !== "save_stations") return undefined;
    saves += 1;
    return saves === 1 ? addWrite.promise : null;
  } });
  h.evaluate(`state.stations = [{id:"existing",name:"Existing",url:"https://existing.test/live",favorite:false}];
    browseResults = [{name:"Candidate",url:"https://candidate.test/live",country:"",tags:"",tag:""}]; renderBrowse()`);
  const add = h.evaluate("addBrowseStation(browseResults[0])");
  await flush();
  const ordinary = h.evaluate("state.stations[0].favorite = true; saveStations()");
  addWrite.reject(new Error("first write failed"));
  assert.equal(await add, false);
  assert.equal(await ordinary, true);

  const writes = h.calls.filter(({ command }) => command === "save_stations");
  assert.equal(writes.length, 2);
  assert.equal(writes[0].args.stations.some(({ url }) => url.includes("candidate")), true);
  assert.equal(writes[1].args.stations.some(({ url }) => url.includes("candidate")), false);
  assert.equal(writes[1].args.stations[0].favorite, true);
});

test("two pending Adds serialize and a failed second Add does not undo the first", async () => {
  const first = deferred();
  const second = deferred();
  let saves = 0;
  const h = createHarness({ invoke(command) {
    if (command !== "save_stations") return undefined;
    saves += 1;
    if (saves === 1) return first.promise;
    if (saves === 2) return second.promise;
    return null;
  } });
  h.evaluate(`browseResults = [
    {name:"One",url:"https://one.test/live",country:"",tags:"",tag:""},
    {name:"Two",url:"https://two.test/live",country:"",tags:"",tag:""}
  ]; renderBrowse()`);
  const one = h.evaluate("addBrowseStation(browseResults[0])");
  const two = h.evaluate("addBrowseStation(browseResults[1])");
  await flush();
  assert.equal(saves, 1);
  assert.equal(h.evaluate("state.stations.length"), 0);

  first.resolve(null);
  assert.equal(await one, true);
  await flush();
  assert.equal(saves, 2);
  second.reject(new Error("second write failed"));
  assert.equal(await two, false);
  assert.equal(h.evaluate("JSON.stringify(state.stations.map(station => station.name))"), '["One"]');

  const writes = h.calls.filter(({ command }) => command === "save_stations");
  assert.equal(JSON.stringify(writes[0].args.stations.map(({ name }) => name)), '["One"]');
  assert.equal(JSON.stringify(writes[1].args.stations.map(({ name }) => name)), '["One","Two"]');
  assert.equal(await h.evaluate("addBrowseStation(browseResults[1])"), true);
  assert.equal(h.evaluate("JSON.stringify(state.stations.map(station => station.name))"), '["One","Two"]');
});

test("stop, state reload and station deletion immediately refresh Browse indicators", async () => {
  const h = createHarness({ invoke(command) {
    if (command === "get_state") return {
      stations: [{ id: "saved", name: "Saved", url: "https://radio.test/live", favorite: false }],
      alarms: [], settings: {},
    };
    return undefined;
  } });
  h.evaluate(`browseResults = [{name:"Candidate",url:"https://radio.test/live",country:"",tags:""}];
    player.source = {kind:"station",url:"https://radio.test/live"}; renderBrowse()`);
  const row = h.el("#browse-list").children[0];
  const listen = row.querySelector(".browse-listen");
  listen.focus();
  assert.equal(row.classList.contains("on"), true);
  h.evaluate("stopPlayback(true)");
  assert.equal(h.el("#browse-list").children[0], row);
  assert.equal(row.classList.contains("on"), false);
  assert.equal(h.document.activeElement, listen);

  await h.evaluate("loadState()");
  let add = h.el("#browse-list").children[0].querySelector(".browse-save");
  assert.equal(add.textContent, "Saved");
  add.focus();

  h.evaluate("wire(); editingStation = state.stations[0]");
  await h.el("#st-delete").dispatch("click");
  assert.equal(h.el("#browse-list").children[0], row);
  assert.equal(add.textContent, "+ Save");
  assert.equal(h.document.activeElement, add);
});
