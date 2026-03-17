#!/usr/bin/env bash
# ============================================================
# generate_report.sh — Generate a self-contained HTML report
#
# Usage: generate_report.sh <report.txt> <screenshot_dir> <output.html> <package> <device> <test_output>
# ============================================================

set -euo pipefail

REPORT_TXT="${1:-./reports/report.txt}"
SCREENSHOT_DIR="${2:-./reports/screenshots}"
OUTPUT_HTML="${3:-./reports/report.html}"
PACKAGE="${4:-unknown}"
DEVICE="${5:-unknown}"
TEST_OUTPUT="${6:-./reports/.test_output.txt}"
DATE=$(date '+%Y-%m-%d %H:%M:%S')

# ---- Extract data from report ----

# Extract score and grade from test output
SCORE=$(grep -oP 'USABILITY SCORE: \K[0-9]+' "$TEST_OUTPUT" 2>/dev/null | head -1 || echo "?")
GRADE=$(grep -oP 'Grade: \K[A-F]' "$TEST_OUTPUT" 2>/dev/null | head -1 || echo "?")

# Count test results
PASS_COUNT=$(grep -c "INSTRUMENTATION_STATUS_CODE: 0" "$TEST_OUTPUT" 2>/dev/null || echo "0")
FAIL_COUNT=$(grep -c "INSTRUMENTATION_STATUS_CODE: -" "$TEST_OUTPUT" 2>/dev/null || echo "0")
TOTAL_COUNT=$((PASS_COUNT + FAIL_COUNT))

# Extract findings by severity
ISSUE_LINES=$(grep -oP '\[ISSUE\] .*' "$TEST_OUTPUT" 2>/dev/null || true)
WARN_LINES=$(grep -oP '\[WARN\] .*' "$TEST_OUTPUT" 2>/dev/null || true)
OK_LINES=$(grep -oP '\[OK\] .*' "$TEST_OUTPUT" 2>/dev/null || true)
AUDIT_LINES=$(grep -oP '\[AUDIT\] .*' "$TEST_OUTPUT" 2>/dev/null || true)

ISSUE_COUNT=$(echo "$ISSUE_LINES" | grep -c '\S' 2>/dev/null || echo "0")
WARN_COUNT=$(echo "$WARN_LINES" | grep -c '\S' 2>/dev/null || echo "0")
OK_COUNT=$(echo "$OK_LINES" | grep -c '\S' 2>/dev/null || echo "0")

# Extract navigation map
NAV_MAP=$(grep 'NAVIGATION MAP' -A 100 "$TEST_OUTPUT" 2>/dev/null | grep -E '^\[AUDIT\]|→|↔|←|↓|↑|↕|\[' | head -30 || true)

# Extract TOP 3 priorities
TOP3=$(grep -A 5 'TOP 3 PRIORITIES' "$TEST_OUTPUT" 2>/dev/null | grep -E '^\s+[0-9]+\.' | head -3 || true)

# ---- Score color ----
case "$GRADE" in
    A) SCORE_COLOR="#22c55e" ;; # green
    B) SCORE_COLOR="#84cc16" ;; # lime
    C) SCORE_COLOR="#eab308" ;; # yellow
    D) SCORE_COLOR="#f97316" ;; # orange
    F) SCORE_COLOR="#ef4444" ;; # red
    *) SCORE_COLOR="#6b7280" ;; # gray
esac

# ---- Build HTML ----
cat > "$OUTPUT_HTML" <<'HTMLHEAD'
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>D-pad Test Report</title>
<style>
:root { --bg: #0f172a; --card: #1e293b; --text: #e2e8f0; --muted: #94a3b8; --border: #334155; }
@media (prefers-color-scheme: light) {
  :root { --bg: #f8fafc; --card: #ffffff; --text: #1e293b; --muted: #64748b; --border: #e2e8f0; }
}
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: var(--bg); color: var(--text); line-height: 1.6; padding: 2rem; max-width: 1200px; margin: 0 auto; }
h1 { font-size: 1.5rem; margin-bottom: 0.5rem; }
h2 { font-size: 1.2rem; margin: 1.5rem 0 0.75rem; padding-bottom: 0.5rem; border-bottom: 1px solid var(--border); }
h3 { font-size: 1rem; margin: 1rem 0 0.5rem; }
.header { display: flex; align-items: center; gap: 2rem; margin-bottom: 2rem; }
.score-circle { width: 120px; height: 120px; border-radius: 50%; display: flex; flex-direction: column; align-items: center; justify-content: center; color: #fff; font-weight: bold; flex-shrink: 0; }
.score-num { font-size: 2.5rem; line-height: 1; }
.score-grade { font-size: 1.2rem; opacity: 0.9; }
.meta { color: var(--muted); font-size: 0.875rem; }
.card { background: var(--card); border: 1px solid var(--border); border-radius: 8px; padding: 1rem; margin-bottom: 1rem; }
.severity-counts { display: flex; gap: 1rem; margin: 1rem 0; }
.sev-badge { padding: 0.25rem 0.75rem; border-radius: 4px; font-size: 0.875rem; font-weight: 600; }
.sev-high { background: #ef4444; color: #fff; }
.sev-warn { background: #eab308; color: #000; }
.sev-ok { background: #22c55e; color: #fff; }
.finding { padding: 0.5rem 0; border-bottom: 1px solid var(--border); font-size: 0.875rem; }
.finding:last-child { border-bottom: none; }
.tag-issue { color: #ef4444; font-weight: 600; }
.tag-warn { color: #eab308; font-weight: 600; }
.tag-ok { color: #22c55e; font-weight: 600; }
.tag-audit { color: #38bdf8; }
.nav-map { font-family: monospace; font-size: 0.8rem; white-space: pre; line-height: 1.4; padding: 1rem; background: #0f172a; color: #38bdf8; border-radius: 6px; overflow-x: auto; }
@media (prefers-color-scheme: light) { .nav-map { background: #f1f5f9; color: #1e40af; } }
.priority { padding: 0.5rem 0.75rem; margin: 0.5rem 0; border-left: 3px solid #ef4444; font-size: 0.9rem; }
.screenshots { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 1rem; }
.screenshot { border: 1px solid var(--border); border-radius: 6px; overflow: hidden; }
.screenshot img { width: 100%; display: block; }
.screenshot .caption { padding: 0.5rem; font-size: 0.75rem; color: var(--muted); text-align: center; }
details { margin: 0.5rem 0; }
summary { cursor: pointer; font-weight: 600; padding: 0.5rem 0; }
.test-bar { display: flex; height: 8px; border-radius: 4px; overflow: hidden; margin: 0.5rem 0; }
.test-bar .pass { background: #22c55e; }
.test-bar .fail { background: #ef4444; }
</style>
</head>
<body>
HTMLHEAD

# Header with score
cat >> "$OUTPUT_HTML" <<EOF
<div class="header">
  <div class="score-circle" style="background:${SCORE_COLOR}">
    <div class="score-num">${SCORE}</div>
    <div class="score-grade">${GRADE}</div>
  </div>
  <div>
    <h1>D-pad Test Report</h1>
    <div class="meta">
      <div>App: <strong>${PACKAGE}</strong></div>
      <div>Device: ${DEVICE} &nbsp;|&nbsp; ${DATE}</div>
      <div>Tests: ${PASS_COUNT} passed, ${FAIL_COUNT} failed of ${TOTAL_COUNT}</div>
    </div>
    <div class="test-bar" style="width:200px">
      <div class="pass" style="width:$(( TOTAL_COUNT > 0 ? PASS_COUNT * 100 / TOTAL_COUNT : 100 ))%"></div>
      <div class="fail" style="width:$(( TOTAL_COUNT > 0 ? FAIL_COUNT * 100 / TOTAL_COUNT : 0 ))%"></div>
    </div>
  </div>
</div>

<div class="severity-counts">
  <span class="sev-badge sev-high">${ISSUE_COUNT} Issues</span>
  <span class="sev-badge sev-warn">${WARN_COUNT} Warnings</span>
  <span class="sev-badge sev-ok">${OK_COUNT} Passed</span>
</div>
EOF

# TOP 3 Priorities
if [ -n "$TOP3" ]; then
    echo '<div class="card">' >> "$OUTPUT_HTML"
    echo '<h3>Top Priorities</h3>' >> "$OUTPUT_HTML"
    echo "$TOP3" | while IFS= read -r line; do
        [ -z "$line" ] && continue
        echo "<div class=\"priority\">$line</div>" >> "$OUTPUT_HTML"
    done
    echo '</div>' >> "$OUTPUT_HTML"
fi

# Navigation Map
if [ -n "$NAV_MAP" ]; then
    echo '<h2>Navigation Map</h2>' >> "$OUTPUT_HTML"
    echo '<div class="nav-map">' >> "$OUTPUT_HTML"
    echo "$NAV_MAP" | sed 's/\[AUDIT\]//g; s/</\&lt;/g; s/>/\&gt;/g' >> "$OUTPUT_HTML"
    echo '</div>' >> "$OUTPUT_HTML"
fi

# Issues section
echo '<h2>Issues</h2>' >> "$OUTPUT_HTML"
echo '<div class="card">' >> "$OUTPUT_HTML"
if [ -n "$ISSUE_LINES" ]; then
    echo "$ISSUE_LINES" | while IFS= read -r line; do
        [ -z "$line" ] && continue
        _escaped=$(echo "$line" | sed 's/</\&lt;/g; s/>/\&gt;/g; s/\[ISSUE\]/<span class="tag-issue">[ISSUE]<\/span>/')
        echo "<div class=\"finding\">${_escaped}</div>" >> "$OUTPUT_HTML"
    done
else
    echo '<div class="finding">No issues found.</div>' >> "$OUTPUT_HTML"
fi
echo '</div>' >> "$OUTPUT_HTML"

# Warnings section
echo '<h2>Warnings</h2>' >> "$OUTPUT_HTML"
echo '<details><summary>'"${WARN_COUNT} warnings"'</summary><div class="card">' >> "$OUTPUT_HTML"
if [ -n "$WARN_LINES" ]; then
    echo "$WARN_LINES" | while IFS= read -r line; do
        [ -z "$line" ] && continue
        _escaped=$(echo "$line" | sed 's/</\&lt;/g; s/>/\&gt;/g; s/\[WARN\]/<span class="tag-warn">[WARN]<\/span>/')
        echo "<div class=\"finding\">${_escaped}</div>" >> "$OUTPUT_HTML"
    done
fi
echo '</div></details>' >> "$OUTPUT_HTML"

# Audit details
echo '<h2>D-pad Analysis Details</h2>' >> "$OUTPUT_HTML"
echo '<details><summary>Full audit output</summary><div class="card">' >> "$OUTPUT_HTML"
if [ -n "$AUDIT_LINES" ]; then
    echo "$AUDIT_LINES" | while IFS= read -r line; do
        [ -z "$line" ] && continue
        _escaped=$(echo "$line" | sed 's/</\&lt;/g; s/>/\&gt;/g; s/\[AUDIT\]/<span class="tag-audit">[AUDIT]<\/span>/; s/===/\&mdash;\&mdash;\&mdash;/g')
        echo "<div class=\"finding\">${_escaped}</div>" >> "$OUTPUT_HTML"
    done
fi
echo '</div></details>' >> "$OUTPUT_HTML"

# Passed checks
echo '<h2>Passed Checks</h2>' >> "$OUTPUT_HTML"
echo '<details><summary>'"${OK_COUNT} checks passed"'</summary><div class="card">' >> "$OUTPUT_HTML"
if [ -n "$OK_LINES" ]; then
    echo "$OK_LINES" | while IFS= read -r line; do
        [ -z "$line" ] && continue
        _escaped=$(echo "$line" | sed 's/</\&lt;/g; s/>/\&gt;/g; s/\[OK\]/<span class="tag-ok">[OK]<\/span>/')
        echo "<div class=\"finding\">${_escaped}</div>" >> "$OUTPUT_HTML"
    done
fi
echo '</div></details>' >> "$OUTPUT_HTML"

# Screenshots
echo '<h2>Screenshots</h2>' >> "$OUTPUT_HTML"
echo '<div class="screenshots">' >> "$OUTPUT_HTML"

if [ -d "$SCREENSHOT_DIR" ]; then
    for img in "$SCREENSHOT_DIR"/*.png; do
        [ -f "$img" ] || continue
        _basename=$(basename "$img")
        _b64=$(base64 -w 0 "$img" 2>/dev/null || base64 "$img" 2>/dev/null || echo "")
        if [ -n "$_b64" ]; then
            cat >> "$OUTPUT_HTML" <<EOF
<div class="screenshot">
  <img src="data:image/png;base64,${_b64}" alt="${_basename}" loading="lazy">
  <div class="caption">${_basename}</div>
</div>
EOF
        fi
    done
fi

echo '</div>' >> "$OUTPUT_HTML"

# Footer
cat >> "$OUTPUT_HTML" <<'HTMLFOOT'
<div style="margin-top:2rem;padding-top:1rem;border-top:1px solid var(--border);color:var(--muted);font-size:0.75rem">
  Generated by Android D-pad Testing Toolkit
</div>
</body>
</html>
HTMLFOOT

echo "HTML report generated: $OUTPUT_HTML"
