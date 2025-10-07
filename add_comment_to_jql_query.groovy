/**
 * Created By Skyler Hochmuth - 07October2025
 * ScriptRunner Cloud — Add a comment to all issues matching a JQL query.
 *
 * HOW TO USE
 * 1) Set JQL_QUERY and COMMENT_TEXT below.
 * 2) Run once with DRY_RUN = true to preview.
 * 3) Set DRY_RUN = false to actually add comments.
 *
 * Notes:
 * - Run as the ScriptRunner app user for best permissions.
 * - COMMENT_TEXT is posted as ADF (paragraphs split by newlines).
 * - If AVOID_DUPLICATE is true, script will skip issues that already contain the exact same plain-text comment.
 */

import java.util.regex.*
import groovy.json.JsonOutput

// ===== CONFIG =====
final String JQL_QUERY       = "JQL Query Here"
final String COMMENT_TEXT    = "Comment here"
final boolean DRY_RUN        = true
final boolean AVOID_DUPLICATE = true          // set false to always add comment, even if same text exists
final int SEARCH_PAGE_SIZE   = 100            // Jira Cloud supports up to 100 per page

// ===== Helpers =====

// Convert plain text (with newlines) to a simple ADF doc with one paragraph per line
Map textToAdf(String text) {
  def paras = (text ?: "").split("\\r?\\n", -1).collect { line ->
    [type: "paragraph", content: (line ? [[type: "text", text: line]] : [])]
  }
  return [type: "doc", version: 1, content: paras]
}

// Flatten a comment ADF doc to plain text (for duplicate detection)
String adfToPlain(Object node) {
  if (node == null) return ""
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
  // normalize whitespace
  return sb.toString().replaceAll("\\s+\$", "")
}

// Check if issue already has an identical plain-text comment
boolean issueHasDuplicateComment(String issueKey, String plainText) {
  def resp = get("/rest/api/3/issue/${issueKey}/comment")
      .queryString('maxResults', '1000')   // adjust if you have issues with >1000 comments
      .asObject(Map)
  if (resp.status != 200) return false
  List comments = (resp.body?.comments ?: []) as List
  for (def c : comments) {
    String bodyText = adfToPlain(c.body)
    if (bodyText == plainText) return true
  }
  return false
}

// ===== Main loop: paginate search + add comments =====
String nextPageToken = null
int guard = 0

int scanned = 0
int toComment = 0
int commented = 0
List<String> touched = []
List<String> skippedDup = []
List<String> errors = []

while (true) {
  def body = [
    jql        : JQL_QUERY,
    maxResults : SEARCH_PAGE_SIZE,
    fields     : ['summary']         // minimal fields; we only need keys
  ]
  if (nextPageToken) body.nextPageToken = nextPageToken

  def sresp = post('/rest/api/3/search/jql')
      .header('Content-Type','application/json')
      .body(body)
      .asObject(Map)

  if (sresp.status != 200) {
    return "Search failed: HTTP ${sresp.status} - ${sresp.body}"
  }

  Map data = sresp.body as Map
  List issues = (data.issues ?: []) as List
  scanned += issues.size()

  for (def issue : issues) {
    String key = issue.key as String
    boolean dup = false
    if (AVOID_DUPLICATE) {
      try {
        dup = issueHasDuplicateComment(key, COMMENT_TEXT)
      } catch (Exception e) {
        // if duplicate check fails for any reason, proceed (safer than skipping)
        dup = false
      }
    }
    if (dup) {
      skippedDup << key
      continue
    }

    toComment++
    touched << key

    if (!DRY_RUN) {
      def cresp = post("/rest/api/3/issue/${key}/comment")
          .header('Content-Type','application/json')
          .body([ body: textToAdf(COMMENT_TEXT) ])
          .asObject(Map)

      if (cresp.status in [200,201]) {
        commented++
      } else {
        errors << "Add comment to ${key} failed: HTTP ${cresp.status} - ${cresp.body}"
      }
      // Optional throttle for rate limits:
      // sleep(150)
    }
  }

  if (data.isLast as boolean) break
  nextPageToken = (data.nextPageToken ?: null) as String
  if (!nextPageToken) break
  if (++guard > 200) break     // safety
}

// ===== Report =====
def lines = touched.collect { "- ${it}" }
return """
${DRY_RUN ? 'DRY RUN — no comments posted.' : 'DONE — comments added.'}

JQL: ${JQL_QUERY}
Comment (first 80 chars): ${COMMENT_TEXT.take(80)}${COMMENT_TEXT.size() > 80 ? '…' : ''}

Scanned issues: ${scanned}
Selected to comment: ${toComment}
${DRY_RUN ? '' : "Successfully commented: ${commented}\n"}
Skipped (duplicate present): ${skippedDup.size()}
${skippedDup.isEmpty() ? '' : skippedDup.collect { "  - ${it}" }.join("\\n")}

Targets:
${lines.isEmpty() ? '(none)' : lines.join('\\n')}

${errors.isEmpty() ? '' : "\nErrors:\n" + errors.join('\\n')}
"""
