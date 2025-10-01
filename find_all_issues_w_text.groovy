/**
 * Created By Skyler Hochmuth - 01October2025
 * ScriptRunner Cloud (Groovy) — find all issues in a project where
 * Summary OR Description contains the exact phrase "Ensight Clinical UI".
 * Uses the new /rest/api/3/search/jql endpoint (nextPageToken pagination).
 */

final String projectKey = 'Example Project'
final String searchText = 'Example Text'

// Build exact-phrase JQL (wrap in quotes)
final String phrase = "\"${searchText.replace('"','\\\"')}\""
final String jql = "project = ${projectKey} AND (summary ~ ${phrase} OR description ~ ${phrase}) ORDER BY created ASC"

// Page size (100 is safe on Cloud)
final int maxResults = 100

List<Map> results = []
String nextPageToken = null
int guard = 0

while (true) {
    def body = [
        jql        : jql,
        maxResults : maxResults,
        fields     : ['summary']
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
    results.addAll(issues.collect { [key: it.key, summary: (it.fields?.summary ?: '') as String] })

    // New API uses isLast + nextPageToken
    if ((data.isLast as boolean)) break
    nextPageToken = (data.nextPageToken ?: null) as String
    if (!nextPageToken) break          // safety: stop if token missing
    if (++guard > 100) break           // safety: avoid infinite loops
}

// Pretty print
if (results.isEmpty()) {
    return "No matches found in project ${projectKey} for phrase: \"${searchText}\""
}

def lines = results.collect { "- ${it.key}: ${it.summary}" }
return """Found ${results.size()} issue(s) in project ${projectKey} containing "${searchText}" in Summary or Description:

${lines.join('\n')}
"""
