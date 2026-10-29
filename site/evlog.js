/* EV-log parser/cleaner, formats v1 and v2 (docs/LOGGING.md).
 * A line-by-line port of tools/evlog.py: both must produce byte-identical
 * cleaned output (tools/test_evlog.sh compares them when node is available).
 * Works in the browser (window.EvLog) and in node (module.exports). */
(function (root) {
  "use strict";

  var NAME_RE = /^egmp-[A-Za-z0-9]+-[0-9]{8}-([0-9]+)\.csv$/;
  var INT_RE = /^-?[0-9]+$/;
  var BOOL_RE = /^[01]$/;
  var YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789";
  var CAR_GUESS = { NE: "Ioniq 5", CE: "Ioniq 6", CV: "EV6", MV: "EV9", JW: "GV60" };

  function LogError(msg) { this.message = msg; }

  function prepareSchema(s) {
    s._num = new RegExp(s.number_pattern);
    s._hdr = {};
    s.header.forEach(function (h) {
      s._hdr[h.key] = h;
      if (h.pattern) h._re = new RegExp(h.pattern);
    });
    s._col = {};
    s.columns.forEach(function (c) {
      s._col[c.name] = c;
      if (c.pattern) c._re = new RegExp(c.pattern);
    });
    return s;
  }

  function has(o, k) { return Object.prototype.hasOwnProperty.call(o, k); }

  function splitCsv(line) {
    var out = [], cur = "", q = false, n = line.length;
    for (var i = 0; i < n; i++) {
      var ch = line[i];
      if (q) {
        if (ch === '"') {
          if (i + 1 < n && line[i + 1] === '"') { cur += '"'; i++; } else q = false;
        } else cur += ch;
      } else if (ch === '"') q = true;
      else if (ch === ",") { out.push(cur); cur = ""; }
      else cur += ch;
    }
    out.push(cur);
    return out;
  }

  function validValue(s, spec, v) {
    if (spec._re) return spec._re.test(v);
    var type = spec.type;
    if (type === "int") return INT_RE.test(v);
    if (type === "bool") return BOOL_RE.test(v);
    return s._num.test(v);
  }

  function rstrip(x) { return x.replace(/\s+$/, ""); }

  function parse(s, text) {
    if (text.charAt(0) === "﻿") text = text.slice(1);
    var lines = text.split("\n").map(function (l) {
      return l.charAt(l.length - 1) === "\r" ? l.slice(0, -1) : l;
    }).filter(function (l) { return l.trim() !== ""; });
    var accept = s.accept_magic || [s.magic];
    if (!lines.length || accept.indexOf(rstrip(lines[0])) < 0)
      throw new LogError("first line is not one of: " + accept.map(function (m) { return "'" + m + "'"; }).join(", "));
    var magic = rstrip(lines[0]);
    var removed = [], header = {}, i = 1, m;
    while (i < lines.length && lines[i].charAt(0) === "#") {
      var line = lines[i++];
      m = /^# ([A-Za-z0-9_]+)=(.*)$/.exec(line);
      if (!m) { removed.push("header: comment/free-text line"); continue; }
      var key = m[1], val = m[2].trim();
      var spec = has(s._hdr, key) ? s._hdr[key] : null;
      if (!spec) { removed.push("header: key '" + key + "' (not on allowlist)"); continue; }
      var ok = spec.type === "number" ? s._num.test(val) : spec._re.test(val);
      if (!ok) { removed.push("header: value of '" + key + "' (not a valid " + spec.type + ")"); continue; }
      if (has(header, key)) { removed.push("header: duplicate '" + key + "'"); continue; }
      header[key] = val;
    }
    if (i >= lines.length) throw new LogError("no column header row");
    var names = splitCsv(lines[i++]).map(function (c) { return c.trim(); });
    var src = {};
    names.forEach(function (name, idx) {
      if (!has(s._col, name)) removed.push("column '" + name + "' (not on allowlist)");
      else if (has(src, name)) removed.push("column '" + name + "' (duplicate)");
      else src[name] = idx;
    });
    if (!has(src, "t_s")) throw new LogError("no t_s column");
    var cols = s.columns.map(function (c) { return c.name; }).filter(function (n) { return has(src, n); });
    var rows = [], bad = {}, dropped = { comment: 0, malformed: 0, t_s: 0, order: 0 }, lastT = null;
    for (; i < lines.length; i++) {
      var l = lines[i];
      if (l.charAt(0) === "#") { dropped.comment++; continue; }
      var f = splitCsv(l);
      if (f.length > names.length) { dropped.malformed++; continue; }
      while (f.length < names.length) f.push("");
      var row = cols.map(function (c) {
        var v = f[src[c]].trim();
        if (v !== "" && !validValue(s, s._col[c], v)) { bad[c] = (bad[c] || 0) + 1; v = ""; }
        return v;
      });
      if (row[0] === "") { dropped.t_s++; continue; }
      var tf = parseFloat(row[0]);
      if (lastT !== null && tf <= lastT) { dropped.order++; continue; }
      lastT = tf;
      rows.push(row);
    }
    cols.forEach(function (c) {
      if (bad[c]) removed.push("column '" + c + "': " + bad[c] + " invalid value(s) blanked");
    });
    var msgs = { comment: "comment line(s) in data", malformed: "row(s) with too many fields",
      t_s: "row(s) without a valid t_s", order: "row(s) with t_s not increasing" };
    ["comment", "malformed", "t_s", "order"].forEach(function (k) {
      if (dropped[k]) removed.push(dropped[k] + " " + msgs[k] + " dropped");
    });
    return { magic: magic, header: header, columns: cols, rows: rows, removed: removed };
  }

  function serialize(s, log) {
    var out = [log.magic || s.magic];
    s.header.forEach(function (h) {
      if (has(log.header, h.key)) out.push("# " + h.key + "=" + log.header[h.key]);
    });
    out.push(log.columns.join(","));
    log.rows.forEach(function (r) { out.push(r.join(",")); });
    return out.join("\n") + "\n";
  }

  function col(log, name) {
    var k = log.columns.indexOf(name);
    if (k < 0) return [];
    return log.rows.filter(function (r) { return r[k] !== ""; }).map(function (r) { return parseFloat(r[k]); });
  }

  function summary(log) {
    var h = log.header, out = {};
    var prod = h.product || "", vm = h.vin_model || "";
    out.car = CAR_GUESS[prod.slice(0, 2).toUpperCase()] || "unknown";
    if (vm.length === 9 && YEAR_CODES.indexOf(vm[8]) >= 0) out.model_year = 2010 + YEAR_CODES.indexOf(vm[8]);
    out.product = prod || "?";
    out.vin_model = vm || "?";
    out.date = h.date || "?";
    var t = col(log, "t_s");
    out.rows = log.rows.length;
    out.duration_s = t.length ? t[t.length - 1] - t[0] : 0;
    var ot = col(log, "out_temp_c");
    if (ot.length) out.out_temp_c = [Math.min.apply(null, ot), Math.max.apply(null, ot)];
    var soc = col(log, "soc_half_pct");
    if (soc.length) out.soc_pct = [soc[0] / 2, soc[soc.length - 1] / 2];
    var d = col(log, "odo_delta_km");
    if (d.length) out.distance_km = Math.max.apply(null, d);
    return out;
  }

  function cleanName(log, seq) {
    var short = ((log.header.product || "").split(".")[0].replace(/[^A-Za-z0-9]/g, "")) || "unknown";
    var date = (log.header.date || "").replace(/-/g, "") || "00000000";
    return "egmp-" + short + "-" + date + "-" + ("00" + seq).slice(-Math.max(3, String(seq).length)) + ".csv";
  }

  var api = {
    LogError: LogError, NAME_RE: NAME_RE, prepareSchema: prepareSchema, splitCsv: splitCsv,
    parse: parse, serialize: serialize, summary: summary, cleanName: cleanName
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.EvLog = api;
})(this);
