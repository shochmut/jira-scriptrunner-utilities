/**
 * Created By Skyler Hochmuth - 01October2025
 * ScriptRunner Cloud — Bulk find & replace in Summary and Description (ADF)
 * 1) Set projectKeyOrName, searchText, replacementText
 * 2) Run with DRY_RUN = true to preview
 * 3) Set DRY_RUN = false to apply updates
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.regex.*

final String projectKeyOrName  = 'Example Project'  // e.g., 'PAUL' or 'Example Project'
final String searchText        = 'Example Search Text'
final String replacementText   = 'Example Replacement Text'
final boolean DRY_RUN          = true
final boolean CASE_INSENSITIVE = true

// Build JQL (quote project to support names with spaces)
final String projectTerm = "\"${projectKeyOrName.replace('"','\\\"')}\""
final String phrase      = "\"${searchText.replace('"','\\\"')}\""
final String jql         = "project = ${projectTerm} AND (summary ~ ${phrase} OR description ~ ${phrase}) ORDER BY created ASC"

// ----- helpers -----
int flags = CASE_INSENSITIVE ? Pattern.CASE_INSENSITIVE : 0
final Pattern PATTERN = Pattern.compile(Pattern.quote(searchText), flags)

def replaceAllLiteral = { String s ->
  if (s == null) return null
  PATTERN.matcher(s).replaceAll(Matcher.quoteReplacement(replacementText))
}

def deepCopy = { Map m ->
  if (!m) return null
  new JsonSlurper().parseText(JsonOutput.toJson(m)) as Map
}

def replaceInAdf
replaceInAdf = { Object node ->
  int changes = 0
  if (node instanceof Map) {
    if (node.type == 'text' && node.text instanceof String) {
      String before = node.text
      String after  = replaceAllLiteral(before)
      if (after != before) { node.text = after; changes++ }
    }
    def content = node.content
    if (content instanceof List) content.each { changes += replaceInAdf(it) }
  } else if (node instanceof List) {
    node.each { changes += replaceInAdf(it) }
  }
  return changes
}

// ----- search + update loop (uses /rest/api/3/search/jql with nextPageToken) -----
int maxResults = 100
String nextPageToken = null
int guard = 0

List<Map> touched = []
List<String> errors = []
int scanned = 0, skipped = 0, updated = 0

while (true) {
  def body = [ jql: jql, maxResults: maxResults, fields: ['summary','description'] ]
  if (nextPageToken) body.nextPageToken = nextPageToken

  def resp = post('/rest/api/3/search/jql')
    .header('Content-Type','application/json')
    .body(body)
    .asObject(Map)

  if (resp.status != 200) {
    return "Search failed: HTTP ${resp.status} - ${resp.body}"
  }

  def data = resp.body as Map
  def issues = (data.issues ?: []) as List
  scanned += issues.size()

  issues.each { issue ->
    String key = issue.key as String
    Map fields = issue.fields as Map ?: [:]

    String oldSummary = fields.summary as String ?: ''
    String newSummary = replaceAllLiteral(oldSummary)
    boolean summaryChanged = (newSummary != oldSummary)

    Map oldDesc = fields.description as Map
    Map newDesc = null
    int adfChanges = 0
    if (oldDesc) {
      newDesc = deepCopy(oldDesc)
      adfChanges = replaceInAdf(newDesc)
    }
    boolean descriptionChanged = (adfChanges > 0)

    if (!(summaryChanged || descriptionChanged)) { skipped++; return }

    Map fieldsToSet = [:]
    if (summaryChanged)   fieldsToSet.summary = newSummary
    if (descriptionChanged) fieldsToSet.description = newDesc

    if (DRY_RUN) {
      touched << [key:key, summary:summaryChanged, description:descriptionChanged]
    } else {
      def upd = put("/rest/api/3/issue/${key}")
        .queryString('notifyUsers','false')
        .header('Content-Type','application/json')
        .body([fields: fieldsToSet])
        .asString()

      if (!(upd.status in [200,204])) {
        errors << "Update ${key} failed: HTTP ${upd.status} - ${upd.body}"
      } else {
        updated++
        touched << [key:key, summary:summaryChanged, description:descriptionChanged]
      }
      // sleep(150) // optional throttle
    }
  }

  if (data.isLast as boolean) break
  nextPageToken = data.nextPageToken as String
  if (!nextPageToken) break
  if (++guard > 100) break
}

// ----- report -----
def lines = touched.collect { "- ${it.key} (summary: ${it.summary?'changed':'—'}, description: ${it.description?'changed':'—'})" }
return """
${DRY_RUN ? 'DRY RUN — no updates made.' : 'DONE — updates applied.'}

Project: ${projectKeyOrName}
Find: "${searchText}"  →  "${replacementText}"
Case-insensitive: ${CASE_INSENSITIVE}

Scanned: ${scanned}
To change: ${touched.size()} | Skipped: ${skipped} ${DRY_RUN ? '' : "| Updated: ${updated} | Errors: ${errors.size()}"}

${lines.isEmpty() ? '(no changes)' : lines.join('\n')}
${errors.isEmpty() ? '' : "\nErrors:\n" + errors.join('\n')}
"""
