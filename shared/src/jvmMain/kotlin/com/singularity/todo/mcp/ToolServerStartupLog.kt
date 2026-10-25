package com.singularity.todo.mcp

import co.touchlab.kermit.Logger

/**
 * The server's log.
 *
 * The tag is the one the entry point has always used: the tag is part of every line
 * that whoever reads the client's standard error sees, so it is not a thing this
 * split may change.
 *
 * One logger for the whole start-up, because the steps are the start-up: a step that
 * logged under a tag of its own would put a second tag in front of the client.
 */
internal val startupLog = Logger.withTag("Main")
