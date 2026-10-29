/* Page logic: load schema.json, fill the field tables, check/clean dropped logs.
 * All parsing happens locally (evlog.js); nothing is sent anywhere. */
(function () {
  "use strict";
  var ISSUE_URL = "https://github.com/willemcvu/egmp-aa-ev-feat/issues/new";
  var MAX_BYTES = 50 * 1024 * 1024;
  var schema = null;
  var cleaned = []; // {name, text, summary}

  function $(id) { return document.getElementById(id); }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  function fmt(x) { return String(Math.round(x * 10) / 10); }

  function fillTables() {
    function fill(tbl, items, nameKey) {
      var body = $(tbl).tBodies[0];
      items.forEach(function (it) {
        var tr = el("tr");
        var td = el("td");
        td.appendChild(el("code", null, it[nameKey]));
        tr.appendChild(td);
        tr.appendChild(el("td", null, it.desc + (it.required ? " (required)" : "")));
        body.appendChild(tr);
      });
    }
    fill("tbl-header", schema.header, "key");
    fill("tbl-columns", schema.columns, "name");
    $("n-header").textContent = schema.header.length;
    $("n-columns").textContent = schema.columns.length;
    schema.never.forEach(function (n) { $("never").appendChild(el("li", null, n)); });
  }

  function status(msg, isErr) {
    var s = $("status");
    s.textContent = msg;
    s.className = "status" + (isErr ? " err" : "");
  }

  function summaryRows(s) {
    var r = [
      ["Car", s.car + (s.model_year ? " " + s.model_year : "") + (s.car === "unknown" ? "" : " (guessed from software)")],
      ["Software", s.product],
      ["VIN model", s.vin_model],
      ["Date", s.date],
      ["Duration", Math.round(s.duration_s / 60) + " min (" + s.rows + " rows)"]
    ];
    if (s.out_temp_c) r.push(["Outside", fmt(s.out_temp_c[0]) + " to " + fmt(s.out_temp_c[1]) + " °C"]);
    if (s.soc_pct) r.push(["Battery", fmt(s.soc_pct[0]) + " % → " + fmt(s.soc_pct[1]) + " %"]);
    if (s.distance_km != null) r.push(["Distance", fmt(s.distance_km) + " km"]);
    return r;
  }

  function uniqueName(log, origName, index) {
    var m = EvLog.NAME_RE.exec(origName);
    var seq = m ? parseInt(m[1], 10) : index + 1;
    var name = EvLog.cleanName(log, seq);
    while (cleaned.some(function (c) { return c.name === name; })) name = EvLog.cleanName(log, ++seq);
    return name;
  }

  function download(name, text) {
    var url = URL.createObjectURL(new Blob([text], { type: "text/csv" }));
    var a = el("a");
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(function () { URL.revokeObjectURL(url); }, 10000);
  }

  function renderCard(origName, result) {
    var card = el("article", "card" + (result.error ? " bad" : ""));
    var head = el("div", "card-head");
    head.appendChild(el("h3", null, origName));
    card.appendChild(head);
    if (result.error) {
      card.appendChild(el("p", "err", "Can't use this file: " + result.error + ". Is it a drive log from egmp-aa-ev-feat?"));
      return card;
    }
    var dl = el("dl", "summary");
    summaryRows(result.summary).forEach(function (r) {
      dl.appendChild(el("dt", null, r[0]));
      dl.appendChild(el("dd", null, r[1]));
    });
    card.appendChild(dl);
    if (result.removed.length) {
      var d = el("details", "removed");
      d.open = true;
      d.appendChild(el("summary", null, "Removed " + result.removed.length + " item(s)"));
      var ul = el("ul");
      result.removed.forEach(function (r) { ul.appendChild(el("li", null, r)); });
      d.appendChild(ul);
      card.appendChild(d);
    } else {
      card.appendChild(el("p", "ok", "Nothing to remove."));
    }
    if (!result.rows) {
      card.appendChild(el("p", "err", "No usable data rows left; this file won't be included."));
      return card;
    }
    var row = el("div", "card-foot");
    var b = el("button", null, "Download " + result.name);
    b.type = "button";
    b.addEventListener("click", function () { download(result.name, result.text); });
    row.appendChild(b);
    card.appendChild(row);
    return card;
  }

  function issueLink() {
    var cars = {}, years = {}, products = {}, lines = [];
    cleaned.forEach(function (c) {
      var s = c.summary;
      if (s.car !== "unknown") cars[s.car] = 1;
      if (s.model_year) years[s.model_year] = 1;
      if (s.product !== "?") products[s.product] = 1;
      var l = "- " + c.name + ": " + s.date + ", " + Math.round(s.duration_s / 60) + " min";
      if (s.out_temp_c) l += ", " + fmt(s.out_temp_c[0]) + " to " + fmt(s.out_temp_c[1]) + " °C";
      if (s.soc_pct) l += ", SOC " + fmt(s.soc_pct[0]) + "→" + fmt(s.soc_pct[1]) + " %";
      if (s.distance_km != null) l += ", " + fmt(s.distance_km) + " km";
      lines.push(l);
    });
    var carList = Object.keys(cars), yearList = Object.keys(years);
    var title = "Drive logs: " + (carList.join(", ") || "car") + (yearList.length ? " " + yearList.join("/") : "") +
      ", " + cleaned.length + " session" + (cleaned.length === 1 ? "" : "s");
    var p = new URLSearchParams();
    p.set("template", "log-submission.yml");
    p.set("title", title);
    if (carList.length === 1) p.set("car", carList[0]);
    if (yearList.length) p.set("model_year", yearList.join(", "));
    if (Object.keys(products).length) p.set("product", Object.keys(products).join(", "));
    p.set("sessions", lines.slice(0, 60).join("\n") + (lines.length > 60 ? "\n- … and " + (lines.length - 60) + " more" : ""));
    return ISSUE_URL + "?" + p.toString();
  }

  function refreshActions() {
    $("actions").hidden = cleaned.length === 0;
    if (cleaned.length) {
      $("dl-all").textContent = cleaned.length === 1 ? "Download cleaned log" : "Download " + cleaned.length + " cleaned logs";
      $("submit").href = issueLink();
    }
  }

  function readText(file) {
    if (file.text) return file.text();
    return new Promise(function (ok, bad) {
      var r = new FileReader();
      r.onload = function () { ok(r.result); };
      r.onerror = function () { bad(r.error); };
      r.readAsText(file, "utf-8");
    });
  }

  function handle(list) {
    var files = Array.prototype.slice.call(list).sort(function (a, b) { return a.name < b.name ? -1 : a.name > b.name ? 1 : 0; });
    if (!files.length) return;
    $("results").textContent = "";
    cleaned = [];
    status("Reading " + files.length + " file(s)…");
    var chain = Promise.resolve();
    files.forEach(function (file, i) {
      chain = chain.then(function () {
        if (file.size > MAX_BYTES) return { error: "file is larger than 50 MB" };
        return readText(file).then(function (text) {
          try {
            var log = EvLog.parse(schema, text);
            var name = uniqueName(log, file.name, i);
            var res = { summary: EvLog.summary(log), removed: log.removed, rows: log.rows.length,
              name: name, text: EvLog.serialize(schema, log) };
            if (res.rows) cleaned.push({ name: name, text: res.text, summary: res.summary });
            return res;
          } catch (e) {
            if (e instanceof EvLog.LogError) return { error: e.message };
            throw e;
          }
        });
      }).then(function (res) {
        $("results").appendChild(renderCard(file.name, res));
      });
    });
    chain.then(function () {
      var bad = files.length - cleaned.length;
      status(cleaned.length + " of " + files.length + " file(s) ready" + (bad ? "; " + bad + " can't be used" : "") + ".", cleaned.length === 0);
      refreshActions();
    }).catch(function (e) { status("Error: " + e, true); });
  }

  function setupDrop() {
    var drop = $("drop");
    $("pick").addEventListener("change", function (e) { handle(e.target.files); e.target.value = ""; });
    ["dragenter", "dragover"].forEach(function (t) {
      drop.addEventListener(t, function (e) { e.preventDefault(); drop.classList.add("over"); });
    });
    ["dragleave", "drop"].forEach(function (t) {
      drop.addEventListener(t, function (e) { e.preventDefault(); drop.classList.remove("over"); });
    });
    drop.addEventListener("drop", function (e) { if (e.dataTransfer) handle(e.dataTransfer.files); });
    // Don't let a file dropped next to the box open in the tab.
    window.addEventListener("dragover", function (e) { e.preventDefault(); });
    window.addEventListener("drop", function (e) { e.preventDefault(); });
    $("dl-all").addEventListener("click", function () {
      cleaned.forEach(function (c, i) { setTimeout(function () { download(c.name, c.text); }, i * 400); });
    });
    $("try-sample").addEventListener("click", function (e) {
      e.preventDefault();
      fetch("sample.csv").then(function (r) { return r.blob(); }).then(function (b) {
        handle([new File([b], "sample.csv", { type: "text/csv" })]);
      });
    });
  }

  fetch("schema.json").then(function (r) {
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.json();
  }).then(function (s) {
    schema = EvLog.prepareSchema(s);
    fillTables();
    setupDrop();
  }).catch(function (e) {
    status("Couldn't load schema.json (" + e.message + "). Open this page from a web server, not as a local file.", true);
    $("pick").disabled = true;
  });
})();
