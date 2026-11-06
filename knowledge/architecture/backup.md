---
type: Architecture
title: Backup Archive
description: Backup archive container and chain handling.

canonical_for:
- K-backup-001

sources:
- id: archive-container
  resource: 
    repo:/shared/src/commonMain/kotlin/com/singularity/todo/core/backup/ArchiveContainer.kt
- id: archive-chain-writer
  resource: 
    repo:/shared/src/commonMain/kotlin/com/singularity/todo/core/backup/ArchiveChainWriter.kt
generated:
- by: agent
  source_state:
    archive-container: 59035e71e7dc317a170e4e26260961ec6decc7d6
    archive-chain-writer: c84b72a94c95cf6693ff5be6d9d91bf21f68182a
  body_state: 5da9418dd70128412e21699f2d9fb86b7fd09fde05651da15eb6ac457ada962b
verified:
- by: human:maintainer
  source_state:
    archive-container: 59035e71e7dc317a170e4e26260961ec6decc7d6
    archive-chain-writer: c84b72a94c95cf6693ff5be6d9d91bf21f68182a
  body_state: 5da9418dd70128412e21699f2d9fb86b7fd09fde05651da15eb6ac457ada962b
---

# Backup

ArchiveContainer and ArchiveChainWriter handle backup archives.
