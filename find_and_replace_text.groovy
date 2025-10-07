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

// ===== CONFIG =====
final String projectKeyOrName = 'Example Project'      // e.g., 'EC' or 'Example Project'
final String searchText       = 'Example Phrase'   // strict phrase to find
final String replacementText  = 'TEST'
final boolean DRY_RUN         = true                    // preview first!
final boolean CASE_SENSITIVE  = false                   // strict literal phrase: case-sensitive or not

// ===== Build JQL (phrase in quotes; quote project to allow spaces) =====
final String projectTerm = "\"${projectKeyOrName.replace('"','\\\"')}\""
final String phrase      = "\"${searchText.replace('"','\\\"')}\""
final String jql         = "project = ${projectTerm} AND (summary ~ ${phrase} OR description ~ ${phrase}) ORDER BY created ASC"

// ===== Strict phrase pattern (literal) =====
final int flags = CASE_SENSITIVE ? 0 : Pattern.CASE_INSENSITIVE
final Pattern PHRASE = Pattern.compile(Pattern.quote(searchText), flags)

// ===== Helpers =====
def slurper = new JsonSlurper()

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

String replaceAllLiteral(String s, Pattern p, String repl) {
  if (s == null) return null
  return p.matcher(s).replaceAll(Matcher.quoteReplacement(repl))
}

Map deepCopy(Map m) {
  if (!m) return null
  return (Map) slurper.parseText(JsonOutput.toJson(m))
}

/** Replace occurrences in ADF text nodes using the strict literal pattern. Returns number of text-node edits. */
int replaceInAdf(Object node, Pattern p, String repl) {
  int changes = 0
  if (node instanceof Map) {
    Map m = (Map) node
    if (m.type == 'text' && m.text instanceof String) {
      String before = (String) m.text
      String after  = p.matcher(before).replaceAll(Matcher.quoteReplacement(repl))
      if (after != before) { m.text = after; changes++ }
    }
    def content = m.get('content')
    if (content instanceof List) content.each { changes += replaceInAdf(it, p, repl) }
  } else if (node instanceof List) {
    node.each { changes += replaceInAdf(it, p, repl) }
  }
  return changes
}

// ===== Search + strict filter + (optional) update =====
final int maxResults = 100
String nextPageToken = null
int guard = 0

int scanned = 0
int matched = 0
int skipped = 0
int updated = 0
List<Map> touched = []
List<String> errors = []

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

  Map data = resp.body as Map
  List issues = (data.issues ?: []) as List
  scanned += issues.size()

  issues.each { issue ->
    String key = issue.key as String
    Map fields = issue.fields as Map ?: [:]

    String oldSummary = fields.summary as String ?: ''
    String descPlain  = adfToPlainText(fields.description)

    boolean strictHit = PHRASE.matcher(oldSummary).find() || PHRASE.matcher(descPlain).find()
    if (!strictHit) { skipped++; return }   // do NOT change unless strict phrase really appears
    matched++

    // Prepare replacements (literal, same strict pattern)
    String newSummary = replaceAllLiteral(oldSummary, PHRASE, replacementText)
    boolean summaryChanged = (newSummary != oldSummary)

    Map oldDesc = fields.description as Map
    Map newDesc = null
    int adfChanges = 0
    if (oldDesc) {
      newDesc = deepCopy(oldDesc)
      adfChanges = replaceInAdf(newDesc, PHRASE, replacementText)
    }
    boolean descriptionChanged = (adfChanges > 0)

    if (!(summaryChanged || descriptionChanged)) { skipped++; return }

    Map fieldsToSet = [:]
    if (summaryChanged)       fieldsToSet.summary     = newSummary
    if (descriptionChanged)   fieldsToSet.description = newDesc

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
      // Optional throttle to be kind to rate limits:
      // sleep(150)
    }
  }

  if (data.isLast as boolean) break
  nextPageToken = (data.nextPageToken ?: null) as String
  if (!nextPageToken) break
  if (++guard > 100) break
}

// ===== Report =====
def lines = touched.collect { "- ${it.key} (summary: ${it.summary?'changed':'—'}, description: ${it.description?'changed':'—'})" }
return """
${DRY_RUN ? 'DRY RUN — no updates made.' : 'DONE — updates applied.'}

Project: ${projectKeyOrName}
Strict phrase: "${searchText}"  →  "${replacementText}"
Case-sensitive: ${CASE_SENSITIVE}

Scanned: ${scanned}
Strict matches: ${matched}
To change: ${touched.size()} | Skipped: ${skipped} ${DRY_RUN ? '' : "| Updated: ${updated} | Errors: ${errors.size()}"}

${lines.isEmpty() ? '(no changes)' : lines.join('\n')}
${errors.isEmpty() ? '' : "\nErrors:\n" + errors.join('\n')}
"""
