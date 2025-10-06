/**
 * Created By Skyler Hochmuth - 01October2025
 * ScriptRunner Cloud (Groovy) — find all issues in a project where
 * Summary OR Description contains the exact phrase "Ensight Clinical UI".
 * Uses /rest/api/3/search/jql (nextPageToken pagination) and then
 * post-filters results by a strict literal phrase match.
 */

import java.util.regex.Pattern
import java.util.regex.Matcher

final String projectKey     = 'EC'
final String searchText     = 'Clinical UI'
final boolean CASE_SENSITIVE = false   // true = case-sensitive literal match
final int CONTEXT_CHARS      = 60      // chars of context around each hit
final int MAX_SNIPPETS_FIELD = 2       // max snippets per field

// Compile the literal phrase pattern
final int flags = CASE_SENSITIVE ? 0 : Pattern.CASE_INSENSITIVE
final Pattern PHRASE = Pattern.compile(Pattern.quote(searchText), flags)

// --- helpers ---
String adfToPlainText(Object node) {
    if (node == null) return ''
    StringBuilder sb = new StringBuilder()
    def walk
    walk = { Object n ->
        if (n instanceof Map) {
            if (n.type == 'text' && n.text instanceof String) sb.append(n.text)
            def content = n.content
            if (content instanceof List) content.each { walk(it) }
            if (n.type in ['paragraph','heading','bulletList','orderedList']) sb.append('\n')
        } else if (n instanceof List) {
            n.each { walk(it) }
        }
    }
    walk(node)
    return sb.toString()
}

List<String> makeSnippets(String text, Pattern p, int ctx, int maxSnips) {
    if (!text) return []
    List<String> out = []
    Matcher m = p.matcher(text)
    while (m.find() && out.size() < maxSnips) {
        int s = Math.max(0, m.start() - ctx)
        int e = Math.min(text.length(), m.end() + ctx)
        String pre  = text.substring(s, m.start())
        String hit  = text.substring(m.start(), m.end())
        String post = text.substring(m.end(), e)
        String snip = (s > 0 ? "…" : "") + pre + "**" + hit + "**" + post + (e < text.length() ? "…" : "")
        snip = snip.replaceAll("\\s+", " ").trim()
        out << snip
    }
    return out
}

// Build exact-phrase JQL (wrap in quotes; also quote project in case it’s a name)
final String projectTerm = "\"${projectKey.replace('"','\\\"')}\""
final String phrase      = "\"${searchText.replace('"','\\\"')}\""
final String jql         = "project = ${projectTerm} AND (summary ~ ${phrase} OR description ~ ${phrase}) ORDER BY created ASC"

// Page size (100 is safe on Cloud)
final int maxResults = 100

List<Map> results = []
String nextPageToken = null
int guard = 0

while (true) {
    def body = [
        jql        : jql,
        maxResults : maxResults,
        fields     : ['summary','description']   // needed for snippet extraction
    ]
    if (nextPageToken) body.nextPageToken = nextPageToken

    def resp = post('/rest/api/3/search/jql')
        .header('Content-Type', 'application/json')
        .body(body)
        .asObject(Map)

    if (resp.status != 200) {
        return "Search failed: HTTP ${resp.status} - ${resp.body}"
    }

    def data = resp.body as Map
    def issues = (data.issues ?: []) as List

    issues.each { issue ->
        def f = issue.fields ?: [:]
        String sum       = (f.summary ?: '') as String
        String descPlain = adfToPlainText(f.description)

        boolean hitSum  = PHRASE.matcher(sum).find()
        boolean hitDesc = PHRASE.matcher(descPlain).find()

        if (hitSum || hitDesc) {
            def sumSnips = makeSnippets(sum, PHRASE, CONTEXT_CHARS, MAX_SNIPPETS_FIELD)
            def desSnips = makeSnippets(descPlain, PHRASE, CONTEXT_CHARS, MAX_SNIPPETS_FIELD)
            results << [key: issue.key, summary: sum, sumSnips: sumSnips, desSnips: desSnips]
        }
    }

    if ((data.isLast as boolean)) break
    nextPageToken = (data.nextPageToken ?: null) as String
    if (!nextPageToken) break
    if (++guard > 100) break
}

// Pretty print
if (results.isEmpty()) {
    return "No strict matches found in project ${projectKey} for phrase: \"${searchText}\" (caseSensitive=${CASE_SENSITIVE})"
}

def lines = results.collect { r ->
    def blocks = []
    blocks << "- ${r.key}: ${r.summary}"
    if (!r.sumSnips.isEmpty()) blocks << "  Summary hit(s): " + r.sumSnips.collect { "\"${it}\"" }.join(" | ")
    if (!r.desSnips.isEmpty()) blocks << "  Description hit(s): " + r.desSnips.collect { "\"${it}\"" }.join(" | ")
    blocks.join('\n')
}

return """Found ${results.size()} strict match(es) in project ${projectKey} containing the exact phrase "${searchText}" in Summary or Description (caseSensitive=${CASE_SENSITIVE}):

${lines.join('\n')}
"""
