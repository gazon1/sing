#!/usr/bin/env bash
# check-detekt-rules-classpath.sh — verify the custom detekt rules are on the classpath.
#
# Why this exists (#372): detekt loads custom rules via a classpath dependency on
# :detekt-rules. Gradle's `detektPlugins(project(":detekt-rules"))` wires a task
# dependency on `:detekt-rules:jar`, so the JAR is always fresh when Gradle
# executes the detekt task. However, the Kotlin daemon caches compiled classes, and
# if a rule source changes without an explicit rebuild, the daemon may serve stale
# bytecode — the JAR's manifest is updated but the cached .class files inside are
# not recompiled.
#
# This check catches that by verifying the JAR contains compiled classes for every
# rule in the ServiceLoader registry. If a rule's source was modified but the JAR
# was not rebuilt, the class will be absent from the JAR and the gate fails.
#
# Usage: ./scripts/check-detekt-rules-classpath.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/detekt-rules/build/libs/detekt-rules.jar"
SERVICE="$ROOT/detekt-rules/src/main/resources/META-INF/services/dev.detekt.api.RuleSetProvider"

if [[ ! -f "$JAR" ]]; then
    echo "ERROR: detekt-rules JAR not found at $JAR"
    echo "Run './gradlew :detekt-rules:jar' first"
    exit 1
fi

if [[ ! -f "$SERVICE" ]]; then
    echo "ERROR: ServiceLoader file not found at $SERVICE"
    exit 1
fi

ERRORS=0
err() { echo "ERROR: $*"; ERRORS=$((ERRORS + 1)); }

# Extract provider class names from the ServiceLoader file
# Format: one fully-qualified class name per line (blank lines and comments ignored)
providers=$(grep -vE '^\s*(#|$)' "$SERVICE")

for fqcn in $providers; do
    # Convert FQCN to JAR path: com.singularity.todo.detekt.FooProvider -> com/singularity/todo/detekt/FooProvider.class
    # RuleSetProvider is the interface; concrete providers are the rule classes themselves.
    class_path="${fqcn//.//}.class"

    if jar tf "$JAR" | grep -qF "$class_path"; then
        :  # class is present
    else
        err "Rule class '$fqcn' is registered in ServiceLoader but not found in $JAR — run './gradlew :detekt-rules:classes' to rebuild"
    fi
done

if ((ERRORS > 0)); then
    echo ""
    echo "check-detekt-rules-classpath.sh: $ERRORS rule(s) missing from JAR"
    exit 1
fi

count=$(echo "$providers" | grep -cvE '^\s*$' || true)
echo "check-detekt-rules-classpath.sh: OK — $count rule(s) present in JAR"
