package com.singularity.todo.test.stubs

/**
 * The A2UI payload an end-to-end flow asks the WhatsNew sheet to show.
 *
 * A column with the note and a "Got it" button whose action is `dismiss` — the action the
 * sheet already maps onto its own dismissal. A flow can therefore close the sheet the way a
 * payload is *meant* to close it, instead of tapping a coordinate or the scrim.
 *
 * It lives in commonMain rather than beside the Android seed activity, because the one
 * property that has to hold of it is that the parser accepts it — and a test that parses it
 * can only exist where the builder is. A payload nobody parses is a payload that renders an
 * empty sheet the first time a flow reaches it.
 */
internal fun whatsNewDemoPayload(
    text: String,
    buttonLabel: String = "Got it",
): String =
    """{"createSurface":{"surfaceId":"whatsnew","rootId":"root","components":[""" +
        """{"id":"root","kind":"column","children":["t1","b1"]},""" +
        """{"id":"t1","kind":"text","value":"${escapeJson(text)}"},""" +
        """{"id":"b1","kind":"button","label":"${escapeJson(buttonLabel)}","action":"dismiss"}]}}"""

private fun escapeJson(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")
