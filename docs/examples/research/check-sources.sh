#!/bin/sh
# An example sources check for a research kind (m4-23). Loop ships it as documentation, not as a check type: a workflow
# copies it into its own repository and runs it as a `command` check, for example
#
#   verify:
#     require_sections: [Summary, Sources]
#     checks:
#       - { type: command, run: "sh /path/to/check-sources.sh report.md", timeout_ms: 120000 }
#
# It reads the section named Sources (or the second argument) of the report (the first argument, report.md by
# default), the lines under its heading down to the next heading of the same or a higher level, outside ``` and ~~~
# fences like Loop's own heading reader. It requests each http or https link found there once with curl, following
# redirects, with a timeout of SOURCES_TIMEOUT seconds (10 by default). A link answers when its final status is below
# 400. It prints one line a link and exits 1 when a link does not answer, when the section holds no link, or when the
# report or the section is missing; 0 when every link answers.
#
# Limits of the example: a link ends at a blank, a quote or one of ()<>[] and loses trailing punctuation, so a URL
# holding parentheses is cut; a site that refuses curl's user agent reads as unreachable.

set -f
report=${1:-report.md}
section=${2:-Sources}
timeout=${SOURCES_TIMEOUT:-10}

if ! command -v curl >/dev/null 2>&1; then
    echo "curl is not installed, the sources cannot be checked"
    exit 1
fi
if [ ! -f "$report" ]; then
    echo "no report at $report"
    exit 1
fi

# The lines of the section, then a last line "found" when a heading named it, so a missing section is told apart from
# an empty one.
lines=$(awk -v name="$section" '
    function trim(s) { sub(/^[ \t]+/, "", s); sub(/[ \t]+$/, "", s); return s }
    {
        marker = substr($0, 1, 3)
        if (marker == "```" || marker == "~~~") {
            if (fence == "") fence = marker; else if (fence == marker) fence = ""
            if (inside) print
            next
        }
        if (fence == "" && match($0, /^#+[ \t]/)) {
            level = RLENGTH - 1
            if (level <= 6) {
                if (inside && level <= depth) inside = 0
                if (!inside && tolower(trim(substr($0, RLENGTH + 1))) == tolower(trim(name))) {
                    inside = 1
                    depth = level
                    found = 1
                }
                next
            }
        }
        if (inside) print
    }
    END { if (found) print "found" }
' "$report")

case "$lines" in
    *found) ;;
    *)
        echo "no $section section in $report"
        exit 1
        ;;
esac

links=$(printf '%s\n' "$lines" | grep -oE 'https?://[^][:space:]<>()"`]+' | sed 's/[.,;:!?]*$//' | sort -u)
if [ -z "$links" ]; then
    echo "no link in the $section section of $report"
    exit 1
fi

failed=0
for link in $links; do
    status=$(curl -sS -o /dev/null -L --max-time "$timeout" -w '%{http_code}' "$link" 2>/dev/null)
    code=$?
    if [ "$code" -eq 0 ] && [ "$status" -ge 200 ] && [ "$status" -lt 400 ]; then
        echo "reachable: $link ($status)"
    elif [ "$code" -eq 0 ]; then
        echo "unreachable: $link (status $status)"
        failed=1
    else
        echo "unreachable: $link (no answer, curl exit $code)"
        failed=1
    fi
done
exit "$failed"
