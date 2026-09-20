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

test("sampled zero format and bitrate buckets remain selectable", () => {
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

test("Browse Add stays pending until persistence and becomes retryable after failure", async () => {
  const first = deferred();
  let saves = 0;
  const h = createHarness({ invoke(command) {
    if (command === "save_stations") return ++saves === 1 ? first.promise : null;
    return undefined;
  } });
  h.evaluate(`browseResults = [{name:"Candidate",url:"https://candidate.test/live",country:"",tags:"",tag:""}]; renderBrowse()`);
  const pending = h.evaluate("addBrowseStation(browseResults[0])");
  await flush();
  let add = h.el("#browse-list").children[0].children.at(-1);
  assert.equal(h.evaluate("state.stations.length"), 0);
  assert.equal(add.textContent, "…");
  assert.equal(add.disabled, true);
  assert.equal(h.evaluate("addBrowseStation(browseResults[0])"), undefined);
  assert.equal(saves, 1);

  first.reject(new Error("disk full"));
  assert.equal(await pending, false);
  add = h.el("#browse-list").children[0].children.at(-1);
  assert.equal(h.evaluate("state.stations.length"), 0);
  assert.equal(add.textContent, "+");
  assert.equal(add.disabled, false);

  assert.equal(await h.evaluate("addBrowseStation(browseResults[0])"), true);
  add = h.el("#browse-list").children[0].children.at(-1);
  assert.equal(h.evaluate("state.stations.length"), 1);
  assert.equal(add.textContent, "✓");
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
  row.focus();
  assert.equal(row.classList.contains("on"), true);
  h.evaluate("stopPlayback(true)");
  assert.equal(h.el("#browse-list").children[0], row);
  assert.equal(row.classList.contains("on"), false);
  assert.equal(h.document.activeElement, row);

  await h.evaluate("loadState()");
  let add = h.el("#browse-list").children[0].children.at(-1);
  assert.equal(add.textContent, "✓");
  add.focus();

  h.evaluate("wire(); editingStation = state.stations[0]");
  await h.el("#st-delete").dispatch("click");
  assert.equal(h.el("#browse-list").children[0], row);
  assert.equal(add.textContent, "+");
  assert.equal(h.document.activeElement, add);
});
