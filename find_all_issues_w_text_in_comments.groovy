/**
 * Created By Skyler Hochmuth - 01October2025
 * ScriptRunner Cloud (Groovy) — find all issues in a project where
 * any comment contains one or more target phrases.
 * Uses /rest/api/3/search/jql with nextPageToken pagination.
 */

final String projectKeyOrName = 'exampleproject'   // e.g., 'PAUL' or 'Example Project'
final List<String> phrases    = ['example', 'test phrase', 'another one']  // phrases to search for in comments

// Quote the project value (works for keys or names, including spaces)
final String projectTerm = "\"${projectKeyOrName.replace('"','\\\"')}\""

// Build OR block of phrase searches in comments
final String orClause = phrases.collect { s ->
    def q = "\"${s.replace('"','\\\"')}\""   // wrap each phrase in quotes
    "comment ~ ${q}"
}.join(' OR ')

final String jql = "project = ${projectTerm} AND (${orClause}) ORDER BY created ASC"

// Page size (100 is safe on Cloud)
final int maxResults = 100

List<Map> results = []
String nextPageToken = null
int guard = 0

while (true) {
    def body = [
        jql        : jql,
        maxResults : maxResults,
        fields     : ['summary']   // minimal fields for display
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

    if (data.isLast as boolean) break
    nextPageToken = (data.nextPageToken ?: null) as String
    if (!nextPageToken) break
    if (++guard > 100) break // safety
}

// Pretty print
if (results.isEmpty()) {
    return """No matches found in project ${projectKeyOrName} for these comment phrases:
- ${phrases.join('\n- ')}"""
}

def lines = results.collect { "- ${it.key}: ${it.summary}" }
return """Found ${results.size()} issue(s) in project ${projectKeyOrName} with a **comment** containing any of the following phrases:
- ${phrases.join('\n- ')}

${lines.join('\n')}
"""
